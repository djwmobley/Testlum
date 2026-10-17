package com.testlum.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** The tools: look up commands, scaffold, check, run, write a test, generate variations. */
public final class Tools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Schemas schemas;
    private final Scaffolder scaffolder;
    private final Checker checker;
    private final Runner runner;
    private final Writer writer;

    public Tools(final Schemas schemas, final String engineJar) {
        this.schemas = schemas;
        this.scaffolder = new Scaffolder(schemas);
        this.checker = new Checker(schemas);
        this.runner = new Runner(engineJar, checker);
        this.writer = new Writer(schemas, checker);
    }

    public List<Tool> all() {
        return List.of(lookup(), scaffold(), check(), run(), writeTest(), variations());
    }

    private Tool lookup() {
        return new Tool("testlum_lookup",
                "Describe what the Testlum engine accepts, straight from its XSD schemas. With no path it lists "
                        + "the commands allowed in a scenario. With a path such as http or http/get it lists that "
                        + "element's attributes (required or optional, allowed values, patterns) and child elements. "
                        + "Use it instead of the wiki, which is partly stale.",
                schema(props()
                        .add("kind", prop("string", "scenario (default), global-config or integration"))
                        .add("path", prop("string", "Slash path under the root, e.g. http/get or assert/equal"))),
                a -> schemas.describe(text(a, "kind", "scenario"), text(a, "path", "")));
    }

    private Tool scaffold() {
        return new Tool("testlum_scaffold",
                "Create the folder layout the engine demands (global-config.xml, config/<env>/integration.xml, "
                        + "scenarios/, data/, locators/pages, locators/component) with valid placeholders. Never "
                        + "overwrites an existing file. Validates what it wrote.",
                schema(props()
                        .add("dir", prop("string", "Absolute path of the tests folder to create or complete"))
                        .add("env", prop("string", "Environment folder name, default sandbox"))
                        .add("project", prop("string", "Report project name, default tests"))
                        .add("apiAlias", prop("string", "API alias for integration.xml, default DEFAULT"))
                        .add("apiUrl", prop("string", "API base url, default http://localhost:8080")), "dir"),
                a -> scaffolder.scaffold(required(a, "dir"), text(a, "env", "sandbox"), text(a, "project", "tests"),
                        text(a, "apiAlias", "DEFAULT"), text(a, "apiUrl", "http://localhost:8080")));
    }

    private Tool check() {
        return new Tool("testlum_check",
                "Validate a tests folder without running it: XSD check of global-config.xml, each integration.xml "
                        + "and each scenario.xml, the folder layout, and rules the XSDs cannot express (expected "
                        + "files exist, include targets exist and carry the include tag, http aliases exist, "
                        + "active=false and onlyThis=true flagged, optional tag rules). Returns PASS or FAIL then "
                        + "one line per finding as file:line:column [level] message.",
                schema(props()
                        .add("dir", prop("string", "Absolute path of the tests folder"))
                        .add("scenario", prop("string", "Only check this scenario folder name (default all)"))
                        .add("config", prop("string", "Global config file name, default global-config.xml"))
                        .add("requireTags", prop("boolean", "Error when a non-include scenario has no tags"))
                        .add("tagPattern", prop("string", "Regex every non-include scenario's tags text must match "
                                + "in full, e.g. S-\\d+,.+")), "dir"),
                a -> checker.check(required(a, "dir"), text(a, "scenario", ""), text(a, "config", "global-config.xml"),
                        a.path("requireTags").asBoolean(false), text(a, "tagPattern", "")));
    }

    private Tool run() {
        return new Tool("testlum_run",
                "Run the Testlum engine headless on a tests folder (java -jar, --config and --path) and report the "
                        + "exit code with its meaning, per-scenario pass or fail with the full failure text, and "
                        + "the report path. Needs TESTLUM_ENGINE_JAR. Per-scenario results need the JUnit output "
                        + "of the djwmobley fork; the stock engine gives exit code and console tail only.",
                schema(props()
                        .add("dir", prop("string", "Absolute path of the tests folder"))
                        .add("tags", prop("string", "Comma separated tags: run only scenarios with these tags"))
                        .add("config", prop("string", "Global config file name, default global-config.xml"))
                        .add("expectedScenarios", prop("integer", "Gate: fail if executed scenarios, minus "
                                + "include-tagged ones, differ from this number"))
                        .add("timeoutSeconds", prop("integer", "Kill the engine after this long, default 600")), "dir"),
                a -> runner.run(required(a, "dir"), text(a, "config", "global-config.xml"), text(a, "tags", ""),
                        a.path("expectedScenarios").asInt(-1), a.path("timeoutSeconds").asInt(600)));
    }

    private Tool writeTest() {
        return new Tool("testlum_write_test",
                "Write scenarios/<name>/scenario.xml plus its data files (request_1.json, expected_1.json and so on) "
                        + "in one step. The scenario must pass the engine schema or nothing is written; the folder "
                        + "check runs afterwards and a failing check rolls the write back.",
                schema(props()
                        .add("dir", prop("string", "Absolute path of the tests folder"))
                        .add("name", prop("string", "Scenario folder name"))
                        .add("scenario", prop("string", "The full scenario.xml text"))
                        .add("files", prop("object", "File name to content, written next to scenario.xml"))
                        .add("overwrite", prop("boolean", "Replace an existing scenario of this name")),
                        "dir", "name", "scenario"),
                a -> writer.writeTest(required(a, "dir"), required(a, "name"), required(a, "scenario"),
                        a.get("files"), a.path("overwrite").asBoolean(false)));
    }

    private Tool variations() {
        return new Tool("testlum_generate_variations",
                "Turn a table of values into engine variations: writes data/<csv>, one row per variation, and sets "
                        + "settings/variations in the scenario, so it runs once per row. Reference columns as "
                        + "{{column}} in the scenario and in file names such as expected_{{id}}.json.",
                schema(props()
                        .add("dir", prop("string", "Absolute path of the tests folder"))
                        .add("name", prop("string", "Existing scenario folder name"))
                        .add("csv", prop("string", "Data file name, for example users.csv"))
                        .add("rows", prop("array", "Array of objects, one per variation, same keys in each")),
                        "dir", "name", "csv", "rows"),
                a -> writer.generateVariations(required(a, "dir"), required(a, "name"), required(a, "csv"),
                        a.get("rows")));
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
            throw new Tool.ToolError("Missing required argument: " + key);
        }
        return v;
    }
}
