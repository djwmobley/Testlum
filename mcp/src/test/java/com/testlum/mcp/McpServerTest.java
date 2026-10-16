package com.testlum.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void answersInitializeListAndCallThenEndsOnEof() throws Exception {
        Tools tools = new Tools(TestSupport.schemas(), null);
        String in = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"testlum_lookup","arguments":{"path":"http/get"}}}
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}
                {"jsonrpc":"2.0","id":5,"method":"bogus"}
                """;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        new McpServer(tools.all(), new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(buf, true, StandardCharsets.UTF_8), 0).serve();
        List<String> lines = buf.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(5, lines.size(), "the notification gets no reply");
        assertEquals("testlum-mcp", JSON.readTree(lines.get(0)).at("/result/serverInfo/name").asText());
        assertEquals(4, JSON.readTree(lines.get(1)).at("/result/tools").size());
        JsonNode call = JSON.readTree(lines.get(2)).path("result");
        assertTrue(call.at("/content/0/text").asText().contains("endpoint"));
        assertTrue(JSON.readTree(lines.get(3)).at("/result/isError").asBoolean());
        assertEquals(-32601, JSON.readTree(lines.get(4)).at("/error/code").asInt());
    }

    @Test
    void exitsWhenIdle() throws Exception {
        PipedOutputStream feed = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(feed);
        McpServer server = new McpServer(List.of(), in, new PrintStream(new ByteArrayOutputStream()), 1);
        long start = System.nanoTime();
        server.serve();
        assertTrue(server.endedIdle());
        assertTrue((System.nanoTime() - start) / 1_000_000_000L < 10);
    }
}
