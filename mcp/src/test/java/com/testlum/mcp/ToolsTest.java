package com.testlum.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsTest {

    @TempDir
    Path tmp;

    @Test
    void lookupDescribesAttributesAndChildren() {
        Schemas s = TestSupport.schemas();
        String get = s.describe("scenario", "http/get");
        assertTrue(get.contains("endpoint - required"), get);
        assertTrue(get.contains("response - required"), get);
        String eq = s.describe("scenario", "assert/equal");
        assertTrue(eq.contains("minLength 10"), eq);
        assertTrue(s.describe("integration", "apis/api").contains("https?://"));
        assertTrue(s.describe("global-config", "").contains("environments"));
        assertTrue(assertThrows(Tool.ToolError.class, () -> s.describe("scenario", "http/nope"))
                .getMessage().contains("Children:"));
        assertThrows(Tool.ToolError.class, () -> s.describe("bogus", ""));
    }

    @Test
    void scaffoldCreatesValidFilesAndKeepsExisting() throws Exception {
        Scaffolder sc = new Scaffolder(TestSupport.schemas());
        String first = sc.scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "https://example.com");
        assertTrue(first.contains("Validated"), first);
        Files.writeString(tmp.resolve("global-config.xml"), "mine");
        String second = sc.scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "https://example.com");
        assertTrue(second.contains("kept    global-config.xml"), second);
        assertEquals("mine", Files.readString(tmp.resolve("global-config.xml")));
        assertThrows(Tool.ToolError.class, () -> sc.scaffold(tmp.toString(), "a b", "p", "DEFAULT", "http://x"));
        assertThrows(Tool.ToolError.class, () -> sc.scaffold(tmp.toString(), "e", "p", "DEFAULT", "ftp://x"));
    }

    @Test
    void tagConfigEnablesExactlyTheGivenTags() throws Exception {
        Schemas s = TestSupport.schemas();
        new Scaffolder(s).scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "http://localhost:1");
        Path out = tmp.resolve(".mcp-run-global-config.xml");
        Runner.writeTagConfig(tmp.resolve("global-config.xml"), out, "smoke, S-01,RULE");
        String xml = Files.readString(out);
        assertTrue(xml.contains("runScenariosByTag enabled=\"true\""), xml);
        assertTrue(xml.contains("name=\"smoke\"") && xml.contains("name=\"S-01\"") && xml.contains("name=\"RULE\""), xml);
        assertTrue(s.validate("global-config.xsd", out, "x").isEmpty());
    }

    @Test
    void stripsAnsiColorCodes() {
        assertEquals("ERROR scenario failed", Runner.stripAnsi("\u001B[31mERROR\u001B[0m scenario \u001B[1;32mfailed\u001B[m"));
    }

    @Test
    void runWithoutEngineJarExplainsWhatToSet() {
        Tools t = new Tools(TestSupport.schemas(), null);
        var run = t.all().stream().filter(x -> x.name().equals("testlum_run")).findFirst().orElseThrow();
        var args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("dir", tmp.toString());
        assertTrue(assertThrows(Tool.ToolError.class, () -> run.handler().apply(args))
                .getMessage().contains("TESTLUM_ENGINE_JAR"));
    }

    @Test
    void schemasFromEngineJarExtractAndValidate() throws Exception {
        Path jar = tmp.resolve("fake-engine.jar");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            Path src = Path.of("..", "engine", "src", "main", "resources", "schema").toAbsolutePath().normalize();
            try (var files = Files.list(src)) {
                for (Path f : files.toList()) {
                    zos.putNextEntry(new java.util.zip.ZipEntry("schema/" + f.getFileName()));
                    zos.write(Files.readAllBytes(f));
                    zos.closeEntry();
                }
            }
        }
        Schemas s = new Schemas(null, jar.toString());
        assertTrue(s.describe("scenario", "http").contains("alias"));
    }
}
