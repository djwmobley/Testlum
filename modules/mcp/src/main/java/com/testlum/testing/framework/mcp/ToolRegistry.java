package com.testlum.testing.framework.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.testlum.testing.framework.mcp.exception.ToolException;
import com.testlum.testing.framework.mcp.constant.ToolMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** The tools: look up commands, scaffold, check, run, write a test, generate variations, list and delete tests. */
@Component
@RequiredArgsConstructor
public final class ToolRegistry {

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final String DIR_HELP = "Tests folder. Leave out for /work. " + WorkDirectoryResolver.RULE;
    private static final String DEFAULT_CONFIG = "global-config.xml";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String LOOKUP_DOC =
            "Describe what the Testlum engine accepts, straight from its XSD schemas. With no path it lists "
                    + "the commands allowed in a scenario. With a path such as http or http/get it lists that "
                    + "element's attributes (required or optional, allowed values, patterns) and child elements. "
                    + "Use it instead of the wiki, which is partly stale.";

    private static final String SCAFFOLD_DOC =
            "Create the folder layout the engine demands (global-config.xml, config/<env>/integration.xml, "
                    + "scenarios/, data/, locators/pages, locators/component) with valid placeholders. Never "
                    + "overwrites an existing file. Validates what it wrote.";

    private static final String CHECK_DOC =
            "Validate a tests folder without running it: XSD check of global-config.xml, each integration.xml "
                    + "and each scenario.xml, the folder layout, and rules the XSDs cannot express (expected "
                    + "files exist, include targets exist and carry the include tag, http aliases exist, "
                    + "active=false and onlyThis=true flagged, optional tag rules). Returns PASS or FAIL then "
                    + "one line per finding as file:line:column [level] message.";

    private static final String GENERATE_VARIATIONS_DOC =
            "Turn a table of values into engine variations: writes data/<csv>, one row per variation, and sets "
                    + "settings/variations in the scenario, so it runs once per row. Reference columns as "
                    + "{{column}} in the scenario and in file names such as expected_{{id}}.json.";

    private static final String LIST_TESTS_DOC =
            "List the scenarios in a tests folder: name, tags (ticket ids go here), active or not, the "
                    + "variations file, the description, every http call as VERB endpoint and every include. "
                    + "Use it to match tests to tickets and to spot calls that no longer exist.";

    private static final String DELETE_TEST_DOC =
            "Delete scenarios/<name>. Refuses while another scenario includes it. Also removes its variations "
                    + "csv from data/ when no other scenario uses it.";

    private static final String RUN_DOC =
            "Run the Testlum engine headless on a tests folder (java -jar, --config and --path) and report the "
                    + "exit code with its meaning, per-scenario pass or fail with the full failure text, and "
                    + "the report path. Needs TESTLUM_ENGINE_JAR. Per-scenario results need "
                    + "<junit enabled=\"true\"/> in the report of the global config (scaffold adds it); without it "
                    + "you get the exit code and console tail only.";

    private static final String WRITE_TEST_DOC =
            "Write scenarios/<name>/scenario.xml plus its data files (request_1.json, expected_1.json and so on) "
                    + "in one step. The scenario must pass the engine schema or nothing is written; the folder "
                    + "check runs afterwards and a failing check rolls the write back.";

    private final EngineSchemas schemas;
    private final FolderScaffolder scaffolder;
    private final TestsFolderChecker checker;
    private final EngineRunner runner;
    private final ScenarioWriter writer;
    private final WorkDirectoryResolver dirs;
    private final ScenarioCatalog tests;

    public List<McpTool> all() {
        return List.of(lookup(), scaffold(), check(), run(), writeTest(), variations(), listTests(), deleteTest());
    }

    private McpTool lookup() {
        return new McpTool("testlum_lookup", LOOKUP_DOC,
                schema(props()
                        .add("kind", prop("string", "scenario (default), global-config or integration"))
                        .add("path", prop("string", "Slash path under the root, e.g. http/get or assert/equal"))),
                a -> schemas.describe(text(a, "kind", "scenario"), text(a, "path", "")));
    }

    private McpTool scaffold() {
        return new McpTool("testlum_scaffold", SCAFFOLD_DOC,
                schema(props()
                        .add("dir", prop("string", "Tests folder to create or complete. Leave out for /work. "
                                + WorkDirectoryResolver.RULE))
                        .add("env", prop("string", "Environment folder name, default sandbox"))
                        .add("project", prop("string", "Report project name, default tests"))
                        .add("apiAlias", prop("string", "API alias for integration.xml, default DEFAULT"))
                        .add("apiUrl", prop("string", "API base url, default http://localhost:8080"))),
                a -> scaffolder.scaffold(dirs.resolve(a), text(a, "env", "sandbox"), text(a, "project", "tests"),
                        text(a, "apiAlias", "DEFAULT"), text(a, "apiUrl", "http://localhost:8080")));
    }

    private McpTool check() {
        return new McpTool("testlum_check", CHECK_DOC,
                schema(props()
                        .add("dir", prop("string", DIR_HELP))
                        .add("scenario", prop("string", "Only check this scenario folder name (default all)"))
                        .add("config", prop("string", "Global config file name, default global-config.xml"))
                        .add("requireTags", prop("boolean", "Error when a non-include scenario has no tags"))
                        .add("tagPattern", prop("string", "Regex every non-include scenario's tags text must match "
                                + "in full, e.g. S-\\d+,.+"))),
                a -> checker.check(dirs.resolve(a), text(a, "scenario", ""), text(a, "config", DEFAULT_CONFIG),
                        a.path("requireTags").asBoolean(false), text(a, "tagPattern", "")));
    }

    private McpTool run() {
        return new McpTool("testlum_run", RUN_DOC,
                schema(props()
                        .add("dir", prop("string", DIR_HELP))
                        .add("tags", prop("string", "Comma separated tags: run only scenarios with these tags"))
                        .add("config", prop("string", "Global config file name, default global-config.xml"))
                        .add("expectedScenarios", prop("integer", "Gate: fail if executed scenarios, minus "
                                + "include-tagged ones, differ from this number"))
                        .add("timeoutSeconds", prop("integer", "Kill the engine after this long, default "
                                + DEFAULT_TIMEOUT_SECONDS))),
                a -> runner.run(new EngineRunner.Job(dirs.resolve(a), text(a, "config", DEFAULT_CONFIG),
                        text(a, "tags", ""), a.path("expectedScenarios").asInt(-1),
                        a.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS))));
    }

    private McpTool writeTest() {
        return new McpTool("testlum_write_test", WRITE_TEST_DOC,
                schema(props()
                        .add("dir", prop("string", DIR_HELP))
                        .add("name", prop("string", "Scenario folder name"))
                        .add("scenario", prop("string", "The full scenario.xml text"))
                        .add("files", prop("object", "File name to content, written next to scenario.xml"))
                        .add("overwrite", prop("boolean", "Replace an existing scenario of this name")),
                        "name", "scenario"),
                a -> writer.writeTest(dirs.resolve(a), required(a, "name"), required(a, "scenario"),
                        a.get("files"), a.path("overwrite").asBoolean(false)));
    }

    private McpTool variations() {
        return new McpTool("testlum_generate_variations", GENERATE_VARIATIONS_DOC,
                schema(props()
                        .add("dir", prop("string", DIR_HELP))
                        .add("name", prop("string", "Existing scenario folder name"))
                        .add("csv", prop("string", "Data file name, for example users.csv"))
                        .add("rows", prop("array", "Array of objects, one per variation, same keys in each")),
                        "name", "csv", "rows"),
                a -> writer.generateVariations(dirs.resolve(a), required(a, "name"), required(a, "csv"),
                        a.get("rows")));
    }

    private McpTool listTests() {
        return new McpTool("testlum_list_tests", LIST_TESTS_DOC,
                schema(props().add("dir", prop("string", DIR_HELP))),
                a -> tests.list(dirs.resolve(a)));
    }

    private McpTool deleteTest() {
        return new McpTool("testlum_delete_test", DELETE_TEST_DOC,
                schema(props()
                        .add("dir", prop("string", DIR_HELP))
                        .add("name", prop("string", "Scenario folder name")), "name"),
                a -> tests.delete(dirs.resolve(a), required(a, "name")));
    }

    // ---- small schema helpers ----

    /** Fluent holder for the properties of an input schema. */
    private static final class Props {
        private final ObjectNode node = JSON.createObjectNode();

        Props add(final String name, final ObjectNode def) {
            node.set(name, def);
            return this;
        }
    }

    private static Props props() {
        return new Props();
    }

    private static ObjectNode prop(final String type, final String description) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", type);
        n.put("description", description);
        return n;
    }

    private static ObjectNode schema(final Props properties, final String... required) {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "object");
        s.set("properties", properties.node);
        var req = s.putArray("required");
        for (String r : required) {
            req.add(r);
        }
        s.put("additionalProperties", false);
        return s;
    }

    static String text(final JsonNode a, final String key, final String fallback) {
        String v = a.path(key).asText("");
        return v.isBlank() ? fallback : v.trim();
    }

    static String required(final JsonNode a, final String key) {
        String v = text(a, key, "");
        if (v.isEmpty()) {
            throw new ToolException(ToolMessage.MISSING_ARGUMENT, key);
        }
        return v;
    }
}
