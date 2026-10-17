package com.testlum.testing.framework.mcp.constant;

/** Texts of the errors the tools report back to the client. */
public final class ToolMessage {

    public static final String CANNOT_LIST_SCENARIOS = "Cannot list scenarios: %s";
    public static final String INVALID_TAG_PATTERN = "tagPattern is not a valid regex: %s";
    public static final String NOT_A_FOLDER = "Not a folder: %s. %s";
    public static final String HOST_PATH = "'%s' is a path on the host, outside the mounted folder. %s";
    public static final String NO_RUN_BY_TAG = "global config has no runScenariosByTag";
    public static final String NO_CONFIG = "No %s in %s. Run testlum_scaffold first.";
    public static final String ENGINE_JAR_NOT_SET =
            "Set TESTLUM_ENGINE_JAR to the engine jar (testlum-final.jar from the "
                    + "ghcr.io/testlumframework/testlum:2.0.0 image, or a build with the JUnit report).";
    public static final String RUN_FAILED = "Run failed: %s";
    public static final String CANNOT_WRITE = "Cannot write %s: %s";
    public static final String BAD_API_URL = "apiUrl must start with http:// or https://";
    public static final String BAD_ENV_OR_ALIAS = "env and apiAlias may hold letters, digits, _ and - only";
    public static final String NO_SUCH_ELEMENT = "No element '%s' under %s. Children: %s";
    public static final String NO_CHILD_ELEMENTS = "%s has no child elements";
    public static final String NO_ROOT_ELEMENT = "Root element not found in schemas: %s";
    public static final String BAD_KIND = "kind must be scenario, global-config or integration";
    public static final String CANNOT_INDEX_SCHEMAS = "Cannot index schemas: %s";
    public static final String CANNOT_COMPILE_SCHEMA = "Cannot compile %s: %s";
    public static final String NO_SCENARIO_XSD_IN_JAR = "No schema/scenario.xsd inside %s";
    public static final String CANNOT_READ_JAR = "Cannot read engine jar: %s";
    public static final String JAR_NOT_FOUND = "Engine jar not found: %s";
    public static final String NO_SCHEMAS =
            "No schemas available. Set TESTLUM_ENGINE_JAR to the engine jar or TESTLUM_XSD_DIR to "
                    + "a folder holding the engine's schema/*.xsd files.";
    public static final String XSD_DIR_WITHOUT_SCENARIO = "TESTLUM_XSD_DIR has no scenario.xsd: %s";
    public static final String NO_SCENARIOS_FOLDER = "No scenarios folder under %s. %s";
    public static final String STILL_INCLUDED = "Not deleted: scenario %s includes %s";
    public static final String CANNOT_VERIFY_INCLUDES = "Not deleted: %s may be included by a scenario that cannot "
            + "be read (%s), fix or remove those first";
    public static final String CSV_IN_USE = "data/%s is also the variations file of %s, pick another csv name";
    public static final String NO_SUCH_SCENARIO = "No such scenario: %s";
    public static final String CANNOT_DELETE = "Could not delete: %s";
    public static final String BAD_SCENARIO_NAME =
            "Scenario name is its path under scenarios/: letters, digits, - and _, with / between folders";
    public static final String MISSING_ARGUMENT = "Missing required argument: %s";
    public static final String PATH_IN_FILE_NAME = "File names go in the scenario folder, no paths: %s";
    public static final String BAD_NAME = "name may hold letters, digits, _ and - only";
    public static final String NO_SETTINGS = "The scenario has no settings element";
    public static final String CANNOT_ADD_VARIATIONS = "Cannot add variations: %s";
    public static final String UNEVEN_ROWS = "Every row needs the same columns. Missing '%s' in %s";
    public static final String BAD_ROWS = "rows must be a non-empty array of objects, one per variation";
    public static final String BAD_CSV_NAME = "csv must look like name.csv (letters, digits, _ and - only)";
    public static final String NO_SCENARIO_FOR_VARIATIONS = "No scenario '%s' to add variations to. Write it first.";
    public static final String ROLLED_BACK = "Rolled back, the check failed:\n%s";
    public static final String NOT_VALID = "Not written, the scenario does not pass the schema (root must be "
            + "<scenario xmlns=\"%s\">):\n%s";
    public static final String CANNOT_STAGE = "Cannot stage the scenario: %s";
    public static final String ALREADY_EXISTS =
            "Scenario '%s' already exists. Pass overwrite=true to replace it.";

    private ToolMessage() {
    }
}
