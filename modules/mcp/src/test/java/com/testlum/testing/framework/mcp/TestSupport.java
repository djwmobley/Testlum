package com.testlum.testing.framework.mcp;

import java.nio.file.Path;

final class TestSupport {

    private TestSupport() {
    }

    /** The engine's own XSDs in this repository, so the tests never need a built engine. */
    static Schemas schemas() {
        Path dir = Path.of("..", "..", "engine", "src", "main", "resources", "schema").toAbsolutePath().normalize();
        return new Schemas(dir.toString(), null);
    }
}
