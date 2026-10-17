package com.testlum.testing.framework.mcp;

import org.springframework.stereotype.Component;
import com.testlum.testing.framework.mcp.exception.ToolException;
import com.testlum.testing.framework.mcp.constant.ToolMessage;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lists the scenarios in a tests folder and deletes one. */
@Component
final class ScenarioCatalog {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z\\d_-]+(/[a-zA-Z\\d_-]+)*");
    private static final List<String> VERBS = List.of("get", "post", "put", "patch", "delete", "head", "options");

    /** What one scenario.xml holds that matters for upkeep. */
    private record Info(Path dir, String name, String description, List<String> tags, boolean active, String csv,
                        List<String> calls, List<String> includes, int runs, boolean readable) { }

    /** Runs the engine executes only because they are include-tagged, for the runner's scenario gate. */
    record Gate(int includeExecutions, List<String> unreadable) { }

    String list(final String dirArg) {
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        List<Info> all = read(root);
        StringBuilder sb = new StringBuilder(all.size() + " scenarios\n");
        all.forEach(i -> describe(sb, i));
        int runs = all.stream().mapToInt(Info::runs).sum();
        int includeRuns = all.stream().filter(i -> i.tags().contains("include")).mapToInt(Info::runs).sum();
        int envs = environments(root.resolve("global-config.xml"));
        sb.append("total runs ").append(runs).append(", include-tagged ").append(includeRuns)
                .append(", enabled environments ").append(envs)
                .append(". For testlum_run expectedScenarios use ").append((runs - includeRuns) * envs);
        return sb.toString();
    }

    private static void describe(final StringBuilder sb, final Info i) {
        sb.append(i.name()).append(" | tags: ").append(String.join(",", i.tags()))
                .append(i.active() ? "" : " | INACTIVE")
                .append(i.csv().isEmpty() ? "" : " | variations: " + i.csv())
                .append(" | runs: ").append(i.runs()).append('\n');
        if (!i.description().isEmpty()) {
            sb.append("  ").append(i.description()).append('\n');
        }
        i.calls().forEach(c -> sb.append("  ").append(c).append('\n'));
        i.includes().forEach(c -> sb.append("  include ").append(c).append('\n'));
    }

    String delete(final String dirArg, final String name) {
        if (!NAME.matcher(name).matches()) {
            throw new ToolException(ToolMessage.BAD_SCENARIO_NAME);
        }
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        List<Info> all = read(root);
        Info target = findDeletable(all, name);
        try {
            remove(target.dir());
            boolean dropCsv = dropUnusedCsv(root, all, target);
            return "Deleted scenarios/" + name + (dropCsv ? " and data/" + target.csv() : "");
        } catch (IOException e) {
            throw new ToolException(ToolMessage.CANNOT_DELETE, e.getMessage());
        }
    }

    private static Info findDeletable(final List<Info> all, final String name) {
        Info target = all.stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new ToolException(ToolMessage.NO_SUCH_SCENARIO, name));
        List<String> unreadable = all.stream().filter(i -> !i.readable()).map(Info::name).toList();
        if (!unreadable.isEmpty()) {
            throw new ToolException(ToolMessage.CANNOT_VERIFY_INCLUDES, name, String.join(", ", unreadable));
        }
        all.stream().filter(i -> i.includes().stream().anyMatch(inc -> includes(inc, name))).findFirst()
                .ifPresent(i -> {
                    throw new ToolException(ToolMessage.STILL_INCLUDED, i.name(), name);
                });
        return target;
    }

    /** An include names a scenario by its path under scenarios/; one with {{variables}} may name any. */
    private static boolean includes(final String include, final String name) {
        if (!include.contains("{{")) {
            return include.equals(name);
        }
        return name.matches(Arrays.stream(include.split("\\{\\{[^}]*}}", -1)).map(Pattern::quote)
                .collect(Collectors.joining(".*")));
    }

    /** The scenarios whose variations file is the given csv. */
    List<String> variationUsers(final Path root, final String csv) {
        return read(root).stream().filter(i -> i.csv().equals(csv)).map(Info::name).toList();
    }

    /** The executions that come from include-tagged scenarios: their runs, over the tags selected, per environment. */
    Gate gate(final Path root, final String config, final String selectedTags) {
        List<Info> all = read(root);
        List<String> selected = tags(selectedTags);
        int runs = all.stream().filter(i -> i.readable() && i.tags().contains("include"))
                .filter(i -> selected.isEmpty() || i.tags().stream().anyMatch(selected::contains))
                .mapToInt(Info::runs).sum();
        return new Gate(runs * environments(root.resolve(config)),
                all.stream().filter(i -> !i.readable()).map(Info::name).toList());
    }

    private static int environments(final Path config) {
        try {
            long enabled = XmlSupport.descendants(XmlSupport.parse(config).getDocumentElement(), "env").stream()
                    .filter(e -> "true".equals(e.getAttribute("enabled"))).count();
            return (int) Math.max(1, enabled);
        } catch (Exception e) {
            return 1;
        }
    }

    private static boolean dropUnusedCsv(final Path root, final List<Info> all, final Info target)
            throws IOException {
        String csv = target.csv();
        boolean shared = all.stream().anyMatch(i -> !i.name().equals(target.name()) && i.csv().equals(csv));
        return !csv.isEmpty() && !shared && NAME.matcher(csv.replaceAll("\\.csv$", "")).matches()
                && Files.deleteIfExists(root.resolve("data").resolve(csv));
    }

    private static void remove(final Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static List<Info> read(final Path root) {
        if (!Files.isDirectory(root.resolve("scenarios"))) {
            throw new ToolException(ToolMessage.NO_SCENARIOS_FOLDER, root, WorkDirectoryResolver.RULE);
        }
        List<Info> out = new ArrayList<>();
        TestsFolderChecker.scenarioFiles(root).forEach((name, file) -> out.add(info(root, name, file)));
        return out;
    }

    private static Info info(final Path root, final String name, final Path file) {
        try {
            Element top = XmlSupport.parse(file).getDocumentElement();
            Element settings = XmlSupport.child(top, "settings");
            boolean active = settings == null || !"false".equals(settings.getAttribute("active"));
            String csv = text(settings, "variations");
            return new Info(file.getParent(), name, text(XmlSupport.child(top, "overview"), "description"),
                    tags(text(settings, "tags")), active, csv, calls(top), includeTargets(top),
                    runs(root, active, csv), true);
        } catch (Exception e) {
            return new Info(file.getParent(), name, "unreadable: " + e.getMessage(), List.of(), true, "",
                    List.of(), List.of(), 1, false);
        }
    }

    private static List<String> tags(final String text) {
        return Arrays.stream(text.split(",")).filter(t -> !t.isBlank()).map(String::trim).toList();
    }

    /** One run, or one per data row of the variations csv; an inactive scenario does not run. */
    private static int runs(final Path root, final boolean active, final String csv) {
        if (!active) {
            return 0;
        }
        if (csv.isEmpty()) {
            return 1;
        }
        try (Stream<String> lines = Files.lines(root.resolve("data").resolve(csv))) {
            return (int) lines.filter(l -> !l.isBlank()).count() - 1;
        } catch (IOException e) {
            return 1;
        }
    }

    private static List<String> calls(final Element top) {
        List<String> out = new ArrayList<>();
        for (Element http : XmlSupport.descendants(top, "http")) {
            for (Node n = http.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && VERBS.contains(e.getLocalName())) {
                    out.add(e.getLocalName().toUpperCase() + " " + e.getAttribute("endpoint"));
                }
            }
        }
        return out;
    }

    private static List<String> includeTargets(final Element top) {
        return XmlSupport.descendants(top, "include").stream().map(e -> e.getAttribute("scenario")).toList();
    }

    private static String text(final Element parent, final String child) {
        Element e = parent == null ? null : XmlSupport.child(parent, child);
        return e == null ? "" : e.getTextContent().trim();
    }
}
