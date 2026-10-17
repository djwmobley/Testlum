package com.testlum.testing.framework.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** Writes the minimal folder layout the engine requires, without overwriting anything. */
@RequiredArgsConstructor
final class Scaffolder {

    private static final String GLOBAL_CONFIG = "global-config.xml";
    private static final String SAFE_NAME = "[A-Za-z0-9_-]+";

    private static final String GLOBAL_CONFIG_XML = """
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
                    <junit enabled="true"/>
                </report>
                <environments>
                    <env folder="%s" enabled="true"/>
                </environments>
            </globalTestConfiguration>
            """;

    private static final String INTEGRATION_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <integrations xmlns="http://www.testlum.com/testing/model/global-config">
                <apis>
                    <api alias="%s" enabled="true" url="%s"/>
                </apis>
            </integrations>
            """;

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

    private final Schemas schemas;

    String scaffold(final String dirArg, final String env, final String project, final String alias,
                    final String url) {
        requireSafe(env, alias, url);
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        List<String> created = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        write(root, env, globalConfig(env, project), integration(alias, url), created, kept);
        StringBuilder sb = new StringBuilder("Scaffold at ").append(root).append('\n');
        created.forEach(c -> sb.append("  created ").append(c).append('\n'));
        kept.forEach(c -> sb.append("  kept    ").append(c).append(" (already existed)\n"));
        return sb.append(validation(validateCreated(root, env, created))).toString().stripTrailing();
    }

    private static void requireSafe(final String env, final String alias, final String url) {
        if (!env.matches(SAFE_NAME) || !alias.matches(SAFE_NAME)) {
            throw new Tool.ToolError(ToolMessage.BAD_ENV_OR_ALIAS);
        }
        if (!url.matches("https?://.+")) {
            throw new Tool.ToolError(ToolMessage.BAD_API_URL);
        }
    }

    private static String validation(final List<Schemas.Issue> issues) {
        if (issues.isEmpty()) {
            return "Validated: written config files pass the engine schemas.";
        }
        return "Schema problems in the files written:\n"
                + String.join("\n", issues.stream().map(i -> "  " + i).toList()) + "\n";
    }

    private static void write(final Path root, final String env, final String globalConfig,
                              final String integration, final List<String> created, final List<String> kept) {
        try {
            Files.createDirectories(root);
            for (String d : List.of("scenarios", "data", "config/" + env, "locators/pages", "locators/component")) {
                Files.createDirectories(root.resolve(d));
            }
            put(root, GLOBAL_CONFIG, globalConfig, created, kept);
            put(root, "config/" + env + "/integration.xml", integration, created, kept);
            put(root, "locators/pages/placeholder.xml", PAGE, created, kept);
            put(root, "locators/component/placeholder.xml", COMPONENT, created, kept);
        } catch (IOException e) {
            throw new Tool.ToolError(ToolMessage.CANNOT_WRITE, root, e.getMessage());
        }
    }

    private List<Schemas.Issue> validateCreated(final Path root, final String env, final List<String> created) {
        List<Schemas.Issue> issues = new ArrayList<>();
        if (created.contains(GLOBAL_CONFIG)) {
            issues.addAll(schemas.validate("global-config.xsd", root.resolve(GLOBAL_CONFIG), GLOBAL_CONFIG));
        }
        String integ = "config/" + env + "/integration.xml";
        if (created.contains(integ)) {
            issues.addAll(schemas.validate("integration-config.xsd", root.resolve(integ), integ));
        }
        return issues;
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
        return GLOBAL_CONFIG_XML.formatted(Xml.esc(project), Xml.esc(env));
    }

    private static String integration(final String alias, final String url) {
        return INTEGRATION_XML.formatted(Xml.esc(alias), Xml.esc(url));
    }
}
