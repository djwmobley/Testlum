package com.testlum.testing.framework.mcp;

/** Texts of the errors the tools report back to the client. */
final class ToolMessage {

    static final String CANNOT_LIST_SCENARIOS = "Cannot list scenarios: %s";
    static final String INVALID_TAG_PATTERN = "tagPattern is not a valid regex: %s";
    static final String NOT_A_FOLDER = "Not a folder: %s. %s";
    static final String HOST_PATH = "'%s' is a path on the host, outside the mounted folder. %s";
    static final String NO_RUN_BY_TAG = "global config has no runScenariosByTag";
    static final String NO_CONFIG = "No %s in %s. Run testlum_scaffold first.";
    static final String ENGINE_JAR_NOT_SET =
            "Set TESTLUM_ENGINE_JAR to the engine jar (testlum-final.jar from the "
                    + "ghcr.io/testlumframework/testlum:2.0.0 image, or a build with the JUnit report).";
    static final String RUN_FAILED = "Run failed: %s";
    static final String CANNOT_WRITE = "Cannot write %s: %s";
    static final String BAD_API_URL = "apiUrl must start with http:// or https://";
    static final String BAD_ENV_OR_ALIAS = "env and apiAlias may hold letters, digits, _ and - only";
    static final String NO_SUCH_ELEMENT = "No element '%s' under %s. Children: %s";
    static final String NO_CHILD_ELEMENTS = "%s has no child elements";
    static final String NO_ROOT_ELEMENT = "Root element not found in schemas: %s";
    static final String BAD_KIND = "kind must be scenario, global-config or integration";
    static final String CANNOT_INDEX_SCHEMAS = "Cannot index schemas: %s";
    static final String CANNOT_COMPILE_SCHEMA = "Cannot compile %s: %s";
    static final String NO_SCENARIO_XSD_IN_JAR = "No schema/scenario.xsd inside %s";
    static final String CANNOT_READ_JAR = "Cannot read engine jar: %s";
    static final String JAR_NOT_FOUND = "Engine jar not found: %s";
    static final String NO_SCHEMAS =
            "No schemas available. Set TESTLUM_ENGINE_JAR to the engine jar or TESTLUM_XSD_DIR to "
                    + "a folder holding the engine's schema/*.xsd files.";
    static final String XSD_DIR_WITHOUT_SCENARIO = "TESTLUM_XSD_DIR has no scenario.xsd: %s";
    static final String NO_SCENARIOS_FOLDER = "No scenarios folder under %s. %s";
    static final String STILL_INCLUDED = "Not deleted: scenario %s includes %s";
    static final String NO_SUCH_SCENARIO = "No such scenario: %s";
    static final String CANNOT_DELETE = "Could not delete: %s";
    static final String BAD_SCENARIO_NAME = "Scenario name may only hold letters, digits, - and _";
    static final String MISSING_ARGUMENT = "Missing required argument: %s";
    static final String PATH_IN_FILE_NAME = "File names go in the scenario folder, no paths: %s";
    static final String BAD_NAME = "name may hold letters, digits, _ and - only";
    static final String NO_SETTINGS = "The scenario has no settings element";
    static final String CANNOT_ADD_VARIATIONS = "Cannot add variations: %s";
    static final String UNEVEN_ROWS = "Every row needs the same columns. Missing '%s' in %s";
    static final String BAD_ROWS = "rows must be a non-empty array of objects, one per variation";
    static final String BAD_CSV_NAME = "csv must look like name.csv (letters, digits, _ and - only)";
    static final String NO_SCENARIO_FOR_VARIATIONS = "No scenario '%s' to add variations to. Write it first.";
    static final String ROLLED_BACK = "Rolled back, the check failed:\n%s";
    static final String NOT_VALID = "Not written, the scenario does not pass the schema (root must be "
            + "<scenario xmlns=\"%s\">):\n%s";
    static final String CANNOT_STAGE = "Cannot stage the scenario: %s";
    static final String ALREADY_EXISTS =
            "Scenario '%s' already exists. Pass overwrite=true to replace it.";

    private ToolMessage() {
    }
}
