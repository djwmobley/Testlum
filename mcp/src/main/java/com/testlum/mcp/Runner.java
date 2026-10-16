package com.testlum.mcp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Runs the engine jar headless and turns its exit code and JUnit file into a short report. */
final class Runner {

    private static final int TAIL_LINES = 40;
    private static final int MAX_FAILURE_CHARS = 4000;
    private static final Map<Integer, String> EXIT = Map.of(
            0, "all scenarios passed",
            1, "at least one scenario failed",
            2, "no tests found (skipped silently, treat as a failure)",
            3, "invalid config or folder layout",
            4, "tests failed to load (skipped silently, treat as a failure)");

    private final String engineJar;
    private final Checker checker;

    Runner(final String engineJar, final Checker checker) {
        this.engineJar = engineJar;
        this.checker = checker;
    }

    String run(final String dirArg, final String config, final String tags, final int expected,
               final int timeoutSeconds) {
        if (engineJar == null || engineJar.isBlank() || !Files.isRegularFile(Path.of(engineJar))) {
            throw new Tool.ToolError("Set TESTLUM_ENGINE_JAR to the engine jar (testlum-final.jar from the "
                    + "ghcr.io/testlumframework/testlum:2.0.0 image, or the fork build).");
        }
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve(config))) {
            throw new Tool.ToolError("No " + config + " in " + root + ". Run testlum_scaffold first.");
        }
        Checker.Scan scan = checker.scan(root);
        Path runConfig = null;
        try {
            String useConfig = config;
            if (!tags.isEmpty()) {
                runConfig = root.resolve(".mcp-run-" + config);
                writeTagConfig(root.resolve(config), runConfig, tags);
                useConfig = runConfig.getFileName().toString();
            }
            FileTime start = FileTime.fromMillis(System.currentTimeMillis() - 1000);
            Result r = exec(root, useConfig, timeoutSeconds);
            return report(root, r, start, scan, expected);
        } catch (Tool.ToolError e) {
            throw e;
        } catch (Exception e) {
            throw new Tool.ToolError("Run failed: " + e);
        } finally {
            if (runConfig != null) {
                try {
                    Files.deleteIfExists(runConfig);
                } catch (IOException ignored) {
                    // leftover run config is harmless and overwritten next run
                }
            }
        }
    }

    private record Result(int exit, boolean timedOut, List<String> tail) { }

    private Result exec(final Path root, final String config, final int timeoutSeconds) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-jar", engineJar, "--config=" + config,
                "--path=" + root.toString().replace('\\', '/'));
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C.UTF-8");
        Process p = pb.start();
        Deque<String> tail = new ArrayDeque<>();
        Thread pump = new Thread(() -> {
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8))) {
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
        }, "engine-output");
        pump.setDaemon(true);
        pump.start();
        boolean done = p.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        if (!done) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
        pump.join(2000);
        synchronized (tail) {
            return new Result(done ? p.exitValue() : -1, !done, new ArrayList<>(tail));
        }
    }

    private String report(final Path root, final Result r, final FileTime start, final Checker.Scan scan,
                          final int expected) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (r.timedOut()) {
            sb.append("TIMEOUT: engine killed\n");
        } else {
            sb.append("exit ").append(r.exit()).append(": ").append(EXIT.getOrDefault(r.exit(), "unknown")).append('\n');
        }
        Path junit = newestJunit(root, start);
        boolean gateFailed = false;
        if (junit == null) {
            sb.append("No JUnit file found, so no per-scenario results (needs the djwmobley fork engine).\n");
        } else {
            Document d = Xml.parse(junit);
            Element suite = d.getDocumentElement();
            List<Element> cases = Xml.descendants(suite, "testcase");
            int failed = 0;
            int skipped = 0;
            StringBuilder detail = new StringBuilder();
            for (Element tc : cases) {
                Element f = Xml.child(tc, "failure");
                if (f != null) {
                    failed++;
                    detail.append("FAIL ").append(tc.getAttribute("name")).append(" (").append(tc.getAttribute("classname"))
                            .append(")\n").append(cap(f.getTextContent().isBlank() ? f.getAttribute("message")
                                    : f.getTextContent())).append('\n');
                } else if (Xml.child(tc, "skipped") != null) {
                    skipped++;
                }
            }
            int executed = cases.size() - skipped;
            sb.append("scenarios: ").append(cases.size()).append(" total, ").append(executed - failed)
                    .append(" passed, ").append(failed).append(" failed, ").append(skipped).append(" skipped\n");
            sb.append("junit: ").append(junit).append('\n');
            sb.append(detail);
            if (expected >= 0) {
                int counted = executed - scan.includeTagged();
                if (counted != expected) {
                    gateFailed = true;
                    sb.append("GATE FAILED: ").append(counted).append(" scenarios ran (").append(executed)
                            .append(" minus ").append(scan.includeTagged()).append(" include-tagged), expected ")
                            .append(expected).append('\n');
                } else {
                    sb.append("gate ok: ").append(counted).append(" scenarios ran, as expected\n");
                }
            }
        }
        if (junit == null && expected >= 0) {
            sb.append("GATE NOT CHECKED: needs the JUnit file for a scenario count\n");
        }
        if (r.exit() != 0 || junit == null || gateFailed) {
            sb.append("--- last ").append(r.tail().size()).append(" console lines ---\n");
            r.tail().forEach(l -> sb.append(stripAnsi(l)).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

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
        try (Stream<Path> s = Files.walk(report, 3)) {
            return s.filter(f -> f.getFileName().toString().equals("junit.xml"))
                    .filter(f -> {
                        try {
                            return Files.getLastModifiedTime(f).compareTo(since) >= 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max(java.util.Comparator.comparing(f -> {
                        try {
                            return Files.getLastModifiedTime(f);
                        } catch (IOException e) {
                            return FileTime.fromMillis(0);
                        }
                    })).orElse(null);
        }
    }

    /** Copies the global config with run-by-tag switched on for exactly the given tags. */
    static void writeTagConfig(final Path from, final Path to, final String tags) throws Exception {
        Document d = Xml.parse(from);
        Element by = Xml.descendants(d.getDocumentElement(), "runScenariosByTag").stream().findFirst()
                .orElseThrow(() -> new Tool.ToolError("global config has no runScenariosByTag"));
        by.setAttribute("enabled", "true");
        while (by.getFirstChild() != null) {
            by.removeChild(by.getFirstChild());
        }
        for (String t : tags.split(",")) {
            if (!t.isBlank()) {
                Element tag = d.createElementNS(by.getNamespaceURI(), "tag");
                tag.setAttribute("name", t.trim());
                tag.setAttribute("enabled", "true");
                by.appendChild(tag);
            }
        }
        Transformer tr = TransformerFactory.newInstance().newTransformer();
        tr.setOutputProperty(OutputKeys.INDENT, "yes");
        tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        tr.transform(new DOMSource(d), new StreamResult(to.toFile()));
    }
}
