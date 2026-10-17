package com.testlum.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP server over stdio (newline-delimited JSON-RPC). Ends when stdin closes or, with no
 * traffic and no call in flight, after the idle limit, so a forgotten session never lingers.
 */
public final class McpServer {

    private static final String PROTOCOL = "2025-03-26";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Tool> tools;
    private final InputStream in;
    private final PrintStream out;
    private final int idleSeconds;
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean idleExit = new AtomicBoolean();
    private volatile Thread reader;

    public McpServer(final List<Tool> tools, final InputStream in, final PrintStream out, final int idleSeconds) {
        this.tools = tools;
        this.in = in;
        this.out = out;
        this.idleSeconds = idleSeconds;
    }

    /** True when the last {@link #serve()} ended because of the idle timeout. */
    public boolean endedIdle() {
        return idleExit.get();
    }

    public void serve() throws IOException {
        reader = Thread.currentThread();
        Thread watchdog = new Thread(this::watch, "idle-watchdog");
        watchdog.setDaemon(true);
        if (idleSeconds > 0) {
            watchdog.start();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (!idleExit.get() && (line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                lastActivity.set(System.nanoTime());
                busy.set(true);
                try {
                    handle(line);
                } finally {
                    busy.set(false);
                    lastActivity.set(System.nanoTime());
                }
            }
        } catch (IOException e) {
            if (!idleExit.get()) {
                throw e;
            }
        } finally {
            watchdog.interrupt();
        }
    }

    private void watch() {
        long limit = idleSeconds * 1_000_000_000L;
        try {
            while (true) {
                Thread.sleep(Math.max(50, Math.min(1000, idleSeconds * 250L)));
                if (!busy.get() && System.nanoTime() - lastActivity.get() > limit) {
                    idleExit.set(true);
                    System.err.println("testlum-mcp: idle for " + idleSeconds + "s, exiting");
                    closeQuietly();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly() {
        try {
            if (in == System.in) {
                Runtime.getRuntime().halt(0);
            }
            in.close();
        } catch (IOException ignored) {
            // closing only unblocks the reader
        }
        Thread t = reader;
        if (t != null) {
            t.interrupt();
        }
    }

    private void handle(final String line) {
        JsonNode msg;
        try {
            msg = JSON.readTree(line);
        } catch (IOException e) {
            send(error(null, -32700, "Parse error"));
            return;
        }
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText("");
        if (id == null || id.isNull()) {
            return; // notifications need no reply
        }
        switch (method) {
            case "initialize" -> send(result(id, initializeResult(msg.path("params"))));
            case "ping" -> send(result(id, JSON.createObjectNode()));
            case "tools/list" -> send(result(id, listResult()));
            case "tools/call" -> send(result(id, call(msg.path("params"))));
            default -> send(error(id, -32601, "Method not found: " + method));
        }
    }

    private ObjectNode initializeResult(final JsonNode params) {
        ObjectNode r = JSON.createObjectNode();
        String asked = params.path("protocolVersion").asText(PROTOCOL);
        r.put("protocolVersion", asked.isBlank() ? PROTOCOL : asked);
        r.putObject("capabilities").putObject("tools");
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "testlum-mcp");
        info.put("version", "0.5.0");
        return r;
    }

    private ObjectNode listResult() {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (Tool t : tools) {
            ObjectNode n = arr.addObject();
            n.put("name", t.name());
            n.put("description", t.description());
            n.set("inputSchema", t.inputSchema());
        }
        return r;
    }

    private ObjectNode call(final JsonNode params) {
        String name = params.path("name").asText();
        JsonNode args = params.has("arguments") ? params.get("arguments") : JSON.createObjectNode();
        ObjectNode r = JSON.createObjectNode();
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        String text;
        boolean isError = false;
        if (tool == null) {
            text = "Unknown tool: " + name;
            isError = true;
        } else {
            try {
                text = tool.handler().apply(args);
            } catch (Tool.ToolError e) {
                text = e.getMessage();
                isError = true;
            } catch (RuntimeException e) {
                text = "Internal error: " + e;
                isError = true;
            }
        }
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", isError);
        return r;
    }

    private static ObjectNode result(final JsonNode id, final JsonNode result) {
        ObjectNode n = JSON.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        return n;
    }

    private static ObjectNode error(final JsonNode id, final int code, final String message) {
        ObjectNode n = JSON.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id == null ? JSON.nullNode() : id);
        ObjectNode e = n.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return n;
    }

    private synchronized void send(final JsonNode node) {
        out.print(node.toString());
        out.print('\n');
        out.flush();
    }
}
