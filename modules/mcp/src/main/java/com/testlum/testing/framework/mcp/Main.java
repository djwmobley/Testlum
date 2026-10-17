package com.testlum.testing.framework.mcp;

import java.io.IOException;

/** Entry point: serves the Testlum tools over stdio and exits when the client goes away or sits idle. */
public final class Main {

    private static final int DEFAULT_IDLE_SECONDS = 900;

    private Main() {
    }

    public static void main(final String[] args) throws IOException {
        int idle = envInt("TESTLUM_MCP_IDLE_SECONDS", DEFAULT_IDLE_SECONDS);
        Schemas schemas = new Schemas(System.getenv("TESTLUM_XSD_DIR"), System.getenv("TESTLUM_ENGINE_JAR"));
        Tools tools = new Tools(schemas, System.getenv("TESTLUM_ENGINE_JAR"));
        new McpServer(tools.all(), System.in, System.out, idle).serve();
    }

    private static int envInt(final String name, final int fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    }
}
