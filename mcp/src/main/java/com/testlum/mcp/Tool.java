package com.testlum.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Function;

/** One MCP tool: a name, a description, a JSON schema for its input and a handler that returns text. */
public record Tool(String name, String description, JsonNode inputSchema, Function<JsonNode, String> handler) {

    /** Thrown by handlers for problems the caller can fix; reported to the client as a tool error. */
    public static final class ToolError extends RuntimeException {
        public ToolError(final String message) {
            super(message);
        }
    }
}
