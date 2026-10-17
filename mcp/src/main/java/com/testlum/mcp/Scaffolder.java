package com.testlum.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes the minimal folder layout the engine requires, without overwriting anything. */
final class Scaffolder {

    private final Schemas schemas;

    Scaffolder(final Schemas schemas) {
        this.schemas = schemas;
    }

    String scaffold(final String dirArg, final String env, final String project, final String alias,
                    final String url) {
        if (!env.matches("[A-Za-z0-9_-]+") || !alias.matches("[A-Za-z0-9_-]+")) {
            throw new Tool.ToolError("env and apiAlias may hold letters, digits, _ and - only");
        }
        if (!url.matches("https?://.+")) {
            throw new Tool.ToolError("apiUrl must start with http:// or https://");
        }
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        List<String> created = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        try {
            Files.createDirectories(root);
            for (String d : List.of("scenarios", "data", "config/" + env, "locators/pages", "locators/component")) {
                Files.createDirectories(root.resolve(d));
            }
            put(root, "global-config.xml", globalConfig(env, project), created, kept);
            put(root, "config/" + env + "/integration.xml", integration(alias, url), created, kept);
            put(root, "locators/pages/placeholder.xml", PAGE, created, kept);
            put(root, "locators/component/placeholder.xml", COMPONENT, created, kept);
        } catch (IOException e) {
            throw new Tool.ToolError("Cannot write " + root + ": " + e.getMessage());
        }
        StringBuilder sb = new StringBuilder("Scaffold at ").append(root).append('\n');
        created.forEach(c -> sb.append("  created ").append(c).append('\n'));
        kept.forEach(c -> sb.append("  kept    ").append(c).append(" (already existed)\n"));
        List<Schemas.Issue> issues = new ArrayList<>();
        if (created.contains("global-config.xml")) {
            issues.addAll(schemas.validate("global-config.xsd", root.resolve("global-config.xml"), "global-config.xml"));
        }
        String integ = "config/" + env + "/integration.xml";
        if (created.contains(integ)) {
            issues.addAll(schemas.validate("integration-config.xsd", root.resolve(integ), integ));
        }
        if (issues.isEmpty()) {
            sb.append("Validated: written config files pass the engine schemas.");
        } else {
            sb.append("Schema problems in the files written:\n");
            issues.forEach(i -> sb.append("  ").append(i).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    private static void put(final Path root, final String rel, final String body, final List<String> created,
                            final List<String> kept) throws IOException {
        Path f = root.resolve(rel);
        if (Files.exists(f)) {
            kept.add(rel);
            return;
        }
        Files.writeString(f, body, StandardCharsets.UTF_8);
        created.add(rel);
    }

    private static String globalConfig(final String env, final String project) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <globalTestConfiguration xmlns="http://www.testlum.com/testing/model/global-config">
                    <stopScenarioOnFailure>false</stopScenarioOnFailure>
                    <stopIfInvalidScenario>true</stopIfInvalidScenario>
                    <delayBetweenScenarioRuns seconds="0" enabled="false"/>
                    <runScenariosByTag enabled="false">
                        <tag name="none" enabled="false"/>
                    </runScenariosByTag>
                    <report>
                        <extentReports projectName="%s">
                            <htmlReportGenerator enabled="true"/>
                        </extentReports>
                    </report>
                    <environments>
                        <env folder="%s" enabled="true"/>
                    </environments>
                </globalTestConfiguration>
                """.formatted(Xml.esc(project), Xml.esc(env));
    }

    private static String integration(final String alias, final String url) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <integrations xmlns="http://www.testlum.com/testing/model/global-config">
                    <apis>
                        <api alias="%s" enabled="true" url="%s"/>
                    </apis>
                </integrations>
                """.formatted(Xml.esc(alias), Xml.esc(url));
    }

    private static final String PAGE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <page xmlns="http://www.testlum.com/testing/model/pages">
                <details>
                    <name>placeholder</name>
                    <url>/</url>
                    <description>Placeholder page so the engine finds valid XML</description>
                </details>
                <locators>
                    <locator locatorId="placeholder">
                        <id>placeholder</id>
                    </locator>
                </locators>
            </page>
            """;

    private static final String COMPONENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <component xmlns="http://www.testlum.com/testing/model/pages">
                <locators>
                    <locator locatorId="placeholder">
                        <id>placeholder</id>
                    </locator>
                </locators>
            </component>
            """;
}
