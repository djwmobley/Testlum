package com.testlum.testing.framework.mcp;

import tools.jackson.databind.JsonNode;

import java.util.function.Function;

/** One MCP tool: a name, a description, a JSON schema for its input and a handler that returns text. */
public record Tool(String name, String description, JsonNode inputSchema, Function<JsonNode, String> handler) {

    /** Thrown by handlers for problems the caller can fix; reported to the client as a tool error. */
    public static final class ToolError extends RuntimeException {
        public ToolError(final String message, final Object... args) {
            super(args.length == 0 ? message : String.format(message, args));
        }
    }
}
