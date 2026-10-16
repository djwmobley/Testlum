package com.testlum.mcp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Validates a tests folder: engine XSDs first, then the rules the XSDs cannot express. */
final class Checker {

    private final Schemas schemas;

    Checker(final Schemas schemas) {
        this.schemas = schemas;
    }

    /** What the tests folder holds, for the runner's scenario gate. */
    record Scan(int scenarios, int includeTagged) { }

    String check(final String dirArg, final String only, final String config, final boolean requireTags,
                 final String tagPattern) {
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new Tool.ToolError("Not a folder: " + root);
        }
        Pattern tagRx = tagPattern.isEmpty() ? null : compile(tagPattern);
        List<Schemas.Issue> issues = new ArrayList<>();
        int scenarioCount = checkAll(root, only, config, requireTags, tagRx, issues);
        long errors = issues.stream().filter(i -> i.level().equals("error")).count();
        long warnings = issues.size() - errors;
        StringBuilder sb = new StringBuilder(errors == 0 ? "PASS" : "FAIL").append(": ").append(errors)
                .append(" errors, ").append(warnings).append(" warnings, ").append(scenarioCount)
                .append(" scenarios checked");
        issues.forEach(i -> sb.append('\n').append(i));
        return sb.toString();
    }

    private static Pattern compile(final String rx) {
        try {
            return Pattern.compile(rx);
        } catch (RuntimeException e) {
            throw new Tool.ToolError("tagPattern is not a valid regex: " + e.getMessage());
        }
    }

    private int checkAll(final Path root, final String only, final String config, final boolean requireTags,
                         final Pattern tagRx, final List<Schemas.Issue> issues) {
        Path cfg = root.resolve(config);
        Set<String> aliases = new TreeSet<>();
        if (!Files.isRegularFile(cfg)) {
            issues.add(err(config, "Global config not found (engine exits 3)"));
        } else {
            issues.addAll(schemas.validate("global-config.xsd", cfg, config));
            checkEnvironments(root, cfg, config, aliases, issues);
        }
        for (String d : List.of("data", "scenarios", "locators/pages", "locators/component")) {
            if (!Files.isDirectory(root.resolve(d))) {
                issues.add(err(d, "Required folder is missing (engine exits 3)"));
            }
        }
        for (String d : List.of("locators/pages", "locators/component")) {
            checkLocatorFiles(root, d, issues);
        }
        Map<String, Path> scenarios = scenarioFiles(root);
        Set<String> included = new TreeSet<>();
        Map<String, Boolean> includeTag = new LinkedHashMap<>();
        for (var e : scenarios.entrySet()) {
            includeTag.put(e.getKey(), false);
        }
        for (var e : scenarios.entrySet()) {
            if (only.isEmpty() || only.equals(e.getKey())) {
                checkScenario(root, e.getKey(), e.getValue(), aliases, scenarios, requireTags, tagRx, issues,
                        included, includeTag);
            }
        }
        if (!only.isEmpty() && !scenarios.containsKey(only)) {
            issues.add(err("scenarios/" + only, "No such scenario folder"));
        }
        for (String name : included) {
            if (scenarios.containsKey(name) && !includeTag.getOrDefault(name, false)
                    && (only.isEmpty() || scenarios.containsKey(only))) {
                issues.add(warn(rel(root, scenarios.get(name)),
                        "Scenario is used by an include but is not tagged 'include', so it also runs on its own"));
            }
        }
        return only.isEmpty() ? scenarios.size() : (scenarios.containsKey(only) ? 1 : 0);
    }

    /** Counts scenarios and include-tagged ones, used by the runner's gate. */
    Scan scan(final Path root) {
        int inc = 0;
        Map<String, Path> files = scenarioFiles(root);
        for (Path f : files.values()) {
            try {
                if (tags(Xml.parse(f)).contains("include")) {
                    inc++;
                }
            } catch (Exception ignored) {
                // invalid XML is reported by check, not here
            }
        }
        return new Scan(files.size(), inc);
    }

    private void checkEnvironments(final Path root, final Path cfg, final String shown, final Set<String> aliases,
                                   final List<Schemas.Issue> issues) {
        try {
            Document d = Xml.parse(cfg);
            for (Element env : Xml.descendants(d.getDocumentElement(), "env")) {
                if (!"true".equals(env.getAttribute("enabled"))) {
                    continue;
                }
                String folder = env.getAttribute("folder");
                Path integ = root.resolve("config").resolve(folder).resolve("integration.xml");
                String showInteg = "config/" + folder + "/integration.xml";
                if (!Files.isRegularFile(integ)) {
                    issues.add(err(showInteg, "Environment '" + folder + "' is enabled but has no integration.xml"));
                    continue;
                }
                issues.addAll(schemas.validate("integration-config.xsd", integ, showInteg));
                for (Element api : Xml.descendants(Xml.parse(integ).getDocumentElement(), "api")) {
                    aliases.add(api.getAttribute("alias"));
                }
            }
        } catch (Exception e) {
            // schema validation above already reported malformed XML
        }
    }

    private static void checkLocatorFiles(final Path root, final String dir, final List<Schemas.Issue> issues) {
        Path p = root.resolve(dir);
        if (!Files.isDirectory(p)) {
            return;
        }
        try (Stream<Path> s = Files.list(p)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                try {
                    Xml.parse(f);
                } catch (Exception e) {
                    issues.add(err(dir + "/" + f.getFileName(),
                            "Every file in locators is parsed as XML, this one is not: " + firstLine(e)));
                }
            }
        } catch (IOException e) {
            issues.add(err(dir, "Cannot list folder: " + e.getMessage()));
        }
    }

    private static Map<String, Path> scenarioFiles(final Path root) {
        Map<String, Path> out = new LinkedHashMap<>();
        Path dir = root.resolve("scenarios");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(f -> f.getFileName().toString().equals("scenario.xml")).sorted()
                    .forEach(f -> out.put(f.getParent().getFileName().toString(), f));
        } catch (IOException e) {
            throw new Tool.ToolError("Cannot list scenarios: " + e.getMessage());
        }
        return out;
    }

    private void checkScenario(final Path root, final String name, final Path file, final Set<String> aliases,
                               final Map<String, Path> scenarios, final boolean requireTags, final Pattern tagRx,
                               final List<Schemas.Issue> issues, final Set<String> included,
                               final Map<String, Boolean> includeTag) {
        String shown = rel(root, file);
        List<Schemas.Issue> xsd = schemas.validate("scenario.xsd", file, shown);
        issues.addAll(xsd);
        Document doc;
        try {
            doc = Xml.parse(file);
        } catch (Exception e) {
            if (xsd.isEmpty()) {
                issues.add(err(shown, firstLine(e)));
            }
            return;
        }
        Element top = doc.getDocumentElement();
        Element settings = Xml.child(top, "settings");
        List<String> tags = tags(doc);
        boolean isInclude = tags.contains("include");
        includeTag.put(name, isInclude);
        if (settings != null && "false".equals(settings.getAttribute("active"))) {
            issues.add(warn(shown, "settings active=\"false\": the scenario is skipped, and the pipeline gate fails it"));
        }
        if (settings != null && "true".equals(settings.getAttribute("onlyThis"))) {
            issues.add(warn(shown, "settings onlyThis=\"true\": only this scenario runs, and the pipeline gate fails it"));
        }
        if (!isInclude) {
            String tagText = tagText(doc);
            if (requireTags && tagText.isEmpty()) {
                issues.add(err(shown, "No tags on a scenario that is not an include"));
            }
            if (tagRx != null && !tagRx.matcher(tagText).matches()) {
                issues.add(err(shown, "Tags '" + tagText + "' do not match the required pattern " + tagRx.pattern()));
            }
        }
        for (Element inc : Xml.descendants(top, "include")) {
            String target = inc.getAttribute("scenario");
            if (target.isEmpty()) {
                continue;
            }
            included.add(target);
            if (!scenarios.containsKey(target)) {
                issues.add(err(shown, "include scenario=\"" + target + "\" has no folder with a scenario.xml"));
            }
        }
        for (Element http : Xml.descendants(top, "http")) {
            String alias = http.hasAttribute("alias") ? http.getAttribute("alias") : "DEFAULT";
            if (!aliases.isEmpty() && !aliases.contains(alias) && !alias.contains("{{")) {
                issues.add(err(shown, "http alias '" + alias + "' is not an api alias in the enabled environments ("
                        + String.join(", ", aliases) + ")"));
            }
        }
        for (Element el : Xml.descendants(top, "*")) {
            String f = el.getAttribute("file");
            if (!f.isEmpty() && !f.contains("{{") && !fileExists(root, file.getParent(), f)) {
                issues.add(err(shown, "File '" + f + "' referenced by <" + el.getLocalName()
                        + "> not found in the scenario folder, its parents or data/"));
            }
        }
    }

    private static boolean fileExists(final Path root, final Path scenarioDir, final String name) {
        String n = name.startsWith("/") ? name.substring(1) : name;
        for (Path d = scenarioDir; d != null && d.startsWith(root) && !d.equals(root); d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(n))) {
                return true;
            }
        }
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(data)) {
            return s.anyMatch(f -> Files.isRegularFile(f) && f.getFileName().toString().equals(n));
        } catch (IOException e) {
            return false;
        }
    }

    private static String tagText(final Document doc) {
        Element settings = Xml.child(doc.getDocumentElement(), "settings");
        Element tags = settings == null ? null : Xml.child(settings, "tags");
        return tags == null ? "" : tags.getTextContent().trim();
    }

    private static List<String> tags(final Document doc) {
        return Arrays.stream(tagText(doc).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String rel(final Path root, final Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }

    private static String firstLine(final Exception e) {
        String m = String.valueOf(e.getMessage());
        int i = m.indexOf('\n');
        return i < 0 ? m : m.substring(0, i);
    }

    private static Schemas.Issue err(final String file, final String msg) {
        return new Schemas.Issue(file, 0, 0, "error", msg);
    }

    private static Schemas.Issue warn(final String file, final String msg) {
        return new Schemas.Issue(file, 0, 0, "warning", msg);
    }
}
