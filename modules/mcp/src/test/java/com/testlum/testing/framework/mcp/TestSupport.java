package com.testlum.testing.framework.mcp;

import java.nio.file.Path;

final class TestSupport {

    private TestSupport() {
    }

    /** The engine's own XSDs in this repository, so the tests never need a built engine. */
    static EngineSchemas schemas() {
        Path dir = Path.of("..", "..", "engine", "src", "main", "resources", "schema").toAbsolutePath().normalize();
        return new EngineSchemas(dir.toString(), null);
    }

    static FolderScaffolder scaffolder() {
        return new FolderScaffolder(schemas());
    }

    static TestsFolderChecker checker() {
        return new TestsFolderChecker(schemas());
    }

    static ScenarioWriter writer() {
        return new ScenarioWriter(schemas(), checker(), new ScenarioCatalog());
    }

    static EngineRunner runner(final String engineJar) {
        return new EngineRunner(engineJar, new ScenarioCatalog());
    }

    static ToolRegistry registry() {
        return new ToolRegistry(schemas(), scaffolder(), checker(), runner(""), writer(),
                new WorkDirectoryResolver("", ""), new ScenarioCatalog());
    }
}
