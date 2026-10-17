package com.testlum.testing.framework.mcp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import java.util.stream.Stream;

/** Runs the engine jar headless and turns its exit code and JUnit file into a short report. */
@RequiredArgsConstructor
final class Runner {

    private static final int TAIL_LINES = 40;
    private static final int MAX_FAILURE_CHARS = 4000;
    private static final long CLOCK_SKEW_MILLIS = 1000;
    private static final long KILL_WAIT_SECONDS = 10;
    private static final long PUMP_WAIT_MILLIS = 2000;
    private static final int JUNIT_DEPTH = 3;
    private static final String FAILURE = "failure";
    private static final String SKIPPED = "skipped";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final String NO_JUNIT = "No JUnit file found, so no per-scenario results (add "
            + "<junit enabled=\"true\"/> to the report in the global config; needs an engine with the JUnit "
            + "report).\n";
    private static final String NO_GATE = "GATE NOT CHECKED: needs the JUnit file for a scenario count\n";
    private static final Map<Integer, String> EXIT = Map.of(
            0, "all scenarios passed",
            1, "at least one scenario failed",
            2, "no tests found (skipped silently, treat as a failure)",
            3, "invalid config or folder layout",
            4, "tests failed to load (skipped silently, treat as a failure)");

    private final String engineJar;
    private final Checker checker;

    /** What one engine run is asked to do. */
    record Job(String dir, String config, String tags, int expected, int timeoutSeconds) { }

    private record Result(int exit, boolean timedOut, List<String> tail, FileTime start) { }

    String run(final Job job) {
        Path root = requireReady(job);
        Checker.Scan scan = checker.scan(root);
        Path runConfig = job.tags().isEmpty() ? null : root.resolve(".mcp-run-" + job.config());
        try {
            String used = runConfig == null ? job.config() : tagConfig(root.resolve(job.config()), runConfig, job);
            return report(root, exec(root, used, job.timeoutSeconds()), scan, job.expected());
        } catch (Tool.ToolError e) {
            throw e;
        } catch (Exception e) {
            throw new Tool.ToolError(ToolMessage.RUN_FAILED, e);
        } finally {
            deleteQuietly(runConfig);
        }
    }

    private Path requireReady(final Job job) {
        if (engineJar == null || engineJar.isBlank() || !Files.isRegularFile(Path.of(engineJar))) {
            throw new Tool.ToolError(ToolMessage.ENGINE_JAR_NOT_SET);
        }
        Path root = Path.of(job.dir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve(job.config()))) {
            throw new Tool.ToolError(ToolMessage.NO_CONFIG, job.config(), root);
        }
        return root;
    }

    private static String tagConfig(final Path from, final Path to, final Job job) throws Exception {
        writeTagConfig(from, to, job.tags());
        return to.getFileName().toString();
    }

    private static void deleteQuietly(final Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // leftover run config is harmless and overwritten next run
        }
    }

    private Result exec(final Path root, final String config, final int timeoutSeconds) throws Exception {
        FileTime start = FileTime.fromMillis(System.currentTimeMillis() - CLOCK_SKEW_MILLIS);
        Process p = start(root, config);
        Deque<String> tail = new ArrayDeque<>();
        Thread pump = startPump(p, tail);
        boolean done = p.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        if (!done) {
            kill(p);
        }
        pump.join(PUMP_WAIT_MILLIS);
        synchronized (tail) {
            return new Result(done ? p.exitValue() : -1, !done, new ArrayList<>(tail), start);
        }
    }

    private Process start(final Path root, final String config) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-jar", engineJar, "--config=" + config,
                "--path=" + root.toString().replace('\\', '/'));
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C.UTF-8");
        return pb.start();
    }

    private static void kill(final Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static Thread startPump(final Process p, final Deque<String> tail) {
        Thread pump = new Thread(() -> pump(p, tail), "engine-output");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    private static void pump(final Process p, final Deque<String> tail) {
        try (var br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > TAIL_LINES) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private String report(final Path root, final Result r, final Checker.Scan scan, final int expected)
            throws Exception {
        StringBuilder sb = new StringBuilder(r.timedOut() ? "TIMEOUT: engine killed\n" : "exit " + r.exit() + ": "
                + EXIT.getOrDefault(r.exit(), "unknown") + "\n");
        Path junit = newestJunit(root, r.start());
        boolean gateFailed = junit != null && junitSummary(sb, junit, scan, expected);
        if (junit == null) {
            sb.append(NO_JUNIT).append(expected >= 0 ? NO_GATE : "");
        }
        if (r.exit() != 0 || junit == null || gateFailed) {
            sb.append("--- last ").append(r.tail().size()).append(" console lines ---\n");
            r.tail().forEach(l -> sb.append(stripAnsi(l)).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    /** Appends the scenario counts and lines; returns true when the run-count gate failed. */
    private static boolean junitSummary(final StringBuilder sb, final Path junit, final Checker.Scan scan,
                                        final int expected) throws Exception {
        List<Element> cases = Xml.descendants(Xml.parse(junit).getDocumentElement(), "testcase");
        int failed = count(cases, FAILURE);
        int skipped = count(cases, SKIPPED);
        int executed = cases.size() - skipped;
        sb.append("scenarios: ").append(cases.size()).append(" total, ").append(executed - failed)
                .append(" passed, ").append(failed).append(" failed, ").append(skipped).append(" skipped\n");
        sb.append("junit: ").append(junit).append('\n').append(scenarioLines(cases));
        int counted = executed - scan.includeTagged();
        if (expected >= 0) {
            sb.append(counted == expected ? "gate ok: " + counted + " runs ran, as expected\n"
                    : gateFailed(counted, executed, scan, expected));
        }
        return expected >= 0 && counted != expected;
    }

    private static String gateFailed(final int counted, final int executed, final Checker.Scan scan,
                                     final int expected) {
        return "GATE FAILED: " + counted + " runs ran (" + executed + " minus " + scan.includeTagged()
                + " include-tagged), expected " + expected + "\n";
    }

    private static int count(final List<Element> cases, final String child) {
        return (int) cases.stream().filter(tc -> Xml.child(tc, child) != null).count();
    }

    /** One line per scenario (PASS, FAIL or SKIP), then the full text of each failure. */
    static String scenarioLines(final List<Element> cases) {
        StringBuilder lines = new StringBuilder();
        StringBuilder failures = new StringBuilder();
        for (Element tc : cases) {
            Element f = Xml.child(tc, FAILURE);
            String mark = f != null ? "FAIL " : Xml.child(tc, SKIPPED) != null ? "SKIP " : "PASS ";
            lines.append(mark).append(tc.getAttribute("name")).append('\n');
            if (f != null) {
                failures.append(failure(tc, f));
            }
        }
        return lines.append(failures).toString();
    }

    private static String failure(final Element tc, final Element f) {
        return "--- " + tc.getAttribute("name") + " (" + tc.getAttribute("classname") + ")\n"
                + cap(f.getTextContent().isBlank() ? f.getAttribute("message") : f.getTextContent()) + "\n";
    }

    /** Removes terminal color and cursor codes from console lines. */
    static String stripAnsi(final String line) {
        return ANSI.matcher(line).replaceAll("");
    }

    private static String cap(final String s) {
        return s.length() <= MAX_FAILURE_CHARS ? s.strip() : s.substring(0, MAX_FAILURE_CHARS).strip() + "\n[cut]";
    }

    private static Path newestJunit(final Path root, final FileTime since) throws IOException {
        Path report = root.resolve("report");
        if (!Files.isDirectory(report)) {
            return null;
        }
        try (Stream<Path> s = Files.walk(report, JUNIT_DEPTH)) {
            return s.filter(f -> f.getFileName().toString().equals("junit.xml"))
                    .filter(f -> modified(f).compareTo(since) >= 0)
                    .max(Comparator.comparing(Runner::modified)).orElse(null);
        }
    }

    private static FileTime modified(final Path f) {
        try {
            return Files.getLastModifiedTime(f);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    /** Copies the global config with run-by-tag switched on for exactly the given tags. */
    static void writeTagConfig(final Path from, final Path to, final String tags) throws Exception {
        Document d = Xml.parse(from);
        Element by = Xml.descendants(d.getDocumentElement(), "runScenariosByTag").stream().findFirst()
                .orElseThrow(() -> new Tool.ToolError(ToolMessage.NO_RUN_BY_TAG));
        by.setAttribute("enabled", "true");
        while (by.getFirstChild() != null) {
            by.removeChild(by.getFirstChild());
        }
        Arrays.stream(tags.split(",")).filter(t -> !t.isBlank()).forEach(t -> by.appendChild(tag(d, by, t.trim())));
        Transformer tr = TransformerFactory.newInstance().newTransformer();
        tr.setOutputProperty(OutputKeys.INDENT, "yes");
        tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        tr.transform(new DOMSource(d), new StreamResult(to.toFile()));
    }

    private static Element tag(final Document d, final Element by, final String name) {
        Element tag = d.createElementNS(by.getNamespaceURI(), "tag");
        tag.setAttribute("name", name);
        tag.setAttribute("enabled", "true");
        return tag;
    }
}
