package com.testlum.testing.framework.mcp;

import org.springframework.stereotype.Component;
import com.testlum.testing.framework.mcp.exception.ToolException;
import com.testlum.testing.framework.mcp.constant.ToolMessage;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import com.testlum.testing.framework.FileSearcher;
import com.testlum.testing.framework.TestResourceSettings;
import com.testlum.testing.framework.exception.DefaultFrameworkException;
import lombok.RequiredArgsConstructor;
import java.util.stream.Stream;

/** Validates a tests folder: engine XSDs first, then the rules the XSDs cannot express. */
@RequiredArgsConstructor
@Component
final class TestsFolderChecker {

    private static final String INTEGRATION = "integration.xml";
    private static final String INTEGRATION_XSD = "integration-config.xsd";
    private static final String INCLUDE = "include";
    private static final String SETTINGS = "settings";
    private static final List<String> LOCATOR_DIRS = List.of("locators/pages", "locators/component");

    private final EngineSchemas schemas;

    /** One check over one folder, with everything the rules share. */
    @RequiredArgsConstructor
    private final class Run {
        private final Path root;
        private final String only;
        private final boolean requireTags;
        private final Pattern tagRx;
        private final List<EngineSchemas.Issue> issues = new ArrayList<>();
        private final Set<String> aliases = new TreeSet<>();
        private final Set<String> included = new TreeSet<>();
        private final Map<String, Boolean> includeTag = new LinkedHashMap<>();
        private Map<String, Path> scenarios;
        private FileSearcher searcher;

        /** The engine's own file lookup, so a file is found here exactly when the engine finds it. */
        private void openSearcher(final String config) {
            try {
                TestResourceSettings.init(config, root.toString(), Optional.empty());
                searcher = new FileSearcher(new TestResourceSettings());
            } catch (DefaultFrameworkException e) {
                issues.add(err("data", firstLine(e)));
            } catch (RuntimeException e) {
                searcher = null; // missing folders are reported above
            }
        }

        private boolean fileExists(final Path scenarioFile, final String name) {
            if (searcher == null) {
                return true;
            }
            try {
                searcher.searchFileFromDir(scenarioFile.toFile(), name);
                return true;
            } catch (RuntimeException e) {
                return inDataFolder(name);
            }
        }

        private boolean inDataFolder(final String name) {
            try {
                searcher.searchFileFromDataFolder(name);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        int execute(final String config) {
            checkConfig(config);
            checkFolders();
            if (Files.isRegularFile(root.resolve(config))) {
                openSearcher(config);
            }
            scenarios = scenarioFiles(root);
            checkScenarios();
            warnIncludesNotTagged();
            return only.isEmpty() ? scenarios.size() : (scenarios.containsKey(only) ? 1 : 0);
        }

        private void checkScenarios() {
            scenarios.forEach((name, file) -> {
                if (only.isEmpty() || only.equals(name)) {
                    checkScenario(this, name, file);
                }
            });
            if (!only.isEmpty() && !scenarios.containsKey(only)) {
                issues.add(err("scenarios/" + only, "No such scenario folder"));
            }
        }

        private void checkFolders() {
            for (String d : List.of("data", "scenarios", "locators/pages", "locators/component")) {
                if (!Files.isDirectory(root.resolve(d))) {
                    issues.add(err(d, "Required folder is missing (engine exits 3)"));
                }
            }
            LOCATOR_DIRS.forEach(d -> checkLocatorFiles(root, d, issues));
        }

        private void checkConfig(final String config) {
            Path cfg = root.resolve(config);
            if (!Files.isRegularFile(cfg)) {
                issues.add(err(config, "Global config not found (engine exits 3)"));
                return;
            }
            issues.addAll(schemas.validate("global-config.xsd", cfg, config));
            checkEnvironments(this, cfg);
        }

        private void warnIncludesNotTagged() {
            for (String name : included) {
                if (scenarios.containsKey(name) && !includeTag.getOrDefault(name, false)
                        && (only.isEmpty() || scenarios.containsKey(only))) {
                    issues.add(warn(rel(root, scenarios.get(name)), "Scenario is used by an include but is not "
                            + "tagged 'include', so it also runs on its own"));
                }
            }
        }
    }

    String check(final String dirArg, final String only, final String config, final boolean requireTags,
                 final String tagPattern) {
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ToolException(ToolMessage.NOT_A_FOLDER, root, WorkDirectoryResolver.RULE);
        }
        Run run = new Run(root, only, requireTags, tagPattern.isEmpty() ? null : compile(tagPattern));
        int scenarioCount = run.execute(config);
        long errors = run.issues.stream().filter(i -> i.level().equals("error")).count();
        StringBuilder sb = new StringBuilder(errors == 0 ? "PASS" : "FAIL").append(": ").append(errors)
                .append(" errors, ").append(run.issues.size() - errors).append(" warnings, ").append(scenarioCount)
                .append(" scenarios checked");
        run.issues.forEach(i -> sb.append('\n').append(i));
        return sb.toString();
    }

    private static Pattern compile(final String rx) {
        try {
            return Pattern.compile(rx);
        } catch (RuntimeException e) {
            throw new ToolException(ToolMessage.INVALID_TAG_PATTERN, e.getMessage());
        }
    }

    private void checkEnvironments(final Run run, final Path cfg) {
        try {
            Document d = XmlSupport.parse(cfg);
            for (Element env : XmlSupport.descendants(d.getDocumentElement(), "env")) {
                if ("true".equals(env.getAttribute("enabled"))) {
                    checkEnvironment(run, env.getAttribute("folder"));
                }
            }
        } catch (Exception e) {
            // schema validation above already reported malformed XML
        }
    }

    private void checkEnvironment(final Run run, final String folder) throws Exception {
        Path integ = run.root.resolve("config").resolve(folder).resolve(INTEGRATION);
        String shown = "config/" + folder + "/" + INTEGRATION;
        if (!Files.isRegularFile(integ)) {
            run.issues.add(err(shown, "Environment '" + folder + "' is enabled but has no " + INTEGRATION));
            return;
        }
        run.issues.addAll(schemas.validate(INTEGRATION_XSD, integ, shown));
        for (Element api : XmlSupport.descendants(XmlSupport.parse(integ).getDocumentElement(), "api")) {
            run.aliases.add(api.getAttribute("alias"));
        }
    }

    private static void checkLocatorFiles(final Path root, final String dir, final List<EngineSchemas.Issue> issues) {
        Path p = root.resolve(dir);
        if (!Files.isDirectory(p)) {
            return;
        }
        try (Stream<Path> s = Files.list(p)) {
            s.filter(Files::isRegularFile).forEach(f -> checkLocatorFile(dir, f, issues));
        } catch (IOException e) {
            issues.add(err(dir, "Cannot list folder: " + e.getMessage()));
        }
    }

    private static void checkLocatorFile(final String dir, final Path f, final List<EngineSchemas.Issue> issues) {
        String shown = dir + "/" + f.getFileName();
        try {
            if (Files.size(f) == 0) {
                issues.add(err(shown, "Empty file. The engine parses every file in locators as XML and exits 3 "
                        + "(Premature end of file); delete it or make it valid XML"));
                return;
            }
            XmlSupport.parse(f);
        } catch (Exception e) {
            issues.add(err(shown, "Every file in locators is parsed as XML, this one is not: " + firstLine(e)));
        }
    }

    static Map<String, Path> scenarioFiles(final Path root) {
        Map<String, Path> out = new LinkedHashMap<>();
        Path dir = root.resolve("scenarios");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(f -> f.getFileName().toString().equals("scenario.xml")).sorted()
                    .forEach(f -> out.put(relativeName(dir, f), f));
        } catch (IOException e) {
            throw new ToolException(ToolMessage.CANNOT_LIST_SCENARIOS, e.getMessage());
        }
        return out;
    }

    /** The scenario's path under scenarios/, which is how an include names it. */
    static String relativeName(final Path scenariosDir, final Path scenarioFile) {
        return scenariosDir.relativize(scenarioFile.getParent()).toString().replace('\\', '/');
    }

    private void checkScenario(final Run run, final String name, final Path file) {
        String shown = rel(run.root, file);
        Document doc = parseOrReport(run, shown, file, schemas.validate("scenario.xsd", file, shown));
        if (doc == null) {
            return;
        }
        Element top = doc.getDocumentElement();
        run.includeTag.put(name, tags(doc).contains(INCLUDE));
        checkSettings(run.issues, shown, XmlSupport.child(top, SETTINGS));
        if (!run.includeTag.get(name)) {
            checkTags(run, shown, tagText(doc));
        }
        checkReferences(run, shown, file, top);
    }

    private static void checkReferences(final Run run, final String shown, final Path file, final Element top) {
        checkIncludes(run, shown, top);
        checkAliases(run, shown, top);
        checkFiles(run, shown, file, top);
    }

    private static Document parseOrReport(final Run run, final String shown, final Path file,
                                          final List<EngineSchemas.Issue> xsd) {
        run.issues.addAll(xsd);
        try {
            return XmlSupport.parse(file);
        } catch (Exception e) {
            if (xsd.isEmpty()) {
                run.issues.add(err(shown, firstLine(e)));
            }
            return null;
        }
    }

    private static void checkSettings(final List<EngineSchemas.Issue> issues, final String shown,
                                      final Element settings) {
        if (settings != null && "false".equals(settings.getAttribute("active"))) {
            issues.add(warn(shown, "settings active=\"false\": the scenario is skipped, and the pipeline gate "
                    + "fails it"));
        }
        if (settings != null && "true".equals(settings.getAttribute("onlyThis"))) {
            issues.add(warn(shown, "settings onlyThis=\"true\": only this scenario runs, and the pipeline gate "
                    + "fails it"));
        }
    }

    private static void checkTags(final Run run, final String shown, final String tagText) {
        if (run.requireTags && tagText.isEmpty()) {
            run.issues.add(err(shown, "No tags on a scenario that is not an include"));
        }
        if (run.tagRx != null && !run.tagRx.matcher(tagText).matches()) {
            run.issues.add(err(shown, "Tags '" + tagText + "' do not match the required pattern "
                    + run.tagRx.pattern()));
        }
    }

    private static void checkIncludes(final Run run, final String shown, final Element top) {
        for (Element inc : XmlSupport.descendants(top, INCLUDE)) {
            String target = inc.getAttribute("scenario");
            if (target.isEmpty() || target.contains("{{")) {
                continue;
            }
            run.included.add(target);
            if (!run.scenarios.containsKey(target)) {
                run.issues.add(err(shown, "include scenario=\"" + target + "\" has no folder with a scenario.xml"));
            }
        }
    }

    private static void checkAliases(final Run run, final String shown, final Element top) {
        for (Element http : XmlSupport.descendants(top, "http")) {
            String alias = http.hasAttribute("alias") ? http.getAttribute("alias") : "DEFAULT";
            if (!run.aliases.isEmpty() && !run.aliases.contains(alias) && !alias.contains("{{")) {
                run.issues.add(err(shown, "http alias '" + alias + "' is not an api alias in the enabled "
                        + "environments (" + String.join(", ", run.aliases) + ")"));
            }
        }
    }

    private static void checkFiles(final Run run, final String shown, final Path file, final Element top) {
        for (Element el : XmlSupport.descendants(top, "*")) {
            String f = el.getAttribute("file");
            if (!f.isEmpty() && !f.contains("{{") && !run.fileExists(file, f)) {
                run.issues.add(err(shown, "File '" + f + "' referenced by <" + el.getLocalName()
                        + "> not found in the scenario folder, its parents or data/"));
            }
        }
    }

    private static String tagText(final Document doc) {
        Element settings = XmlSupport.child(doc.getDocumentElement(), SETTINGS);
        Element tags = settings == null ? null : XmlSupport.child(settings, "tags");
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

    private static EngineSchemas.Issue err(final String file, final String msg) {
        return new EngineSchemas.Issue(file, 0, 0, "error", msg);
    }

    private static EngineSchemas.Issue warn(final String file, final String msg) {
        return new EngineSchemas.Issue(file, 0, 0, "warning", msg);
    }
}
