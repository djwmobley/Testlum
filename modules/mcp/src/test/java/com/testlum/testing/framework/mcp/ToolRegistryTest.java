package com.testlum.testing.framework.mcp;

import com.testlum.testing.framework.mcp.exception.ToolException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LOCAL_URL = "http://localhost:1";

    @TempDir
    Path tmp;

    @Nested
    class Lookup {

        @Test
        void describesAttributesAndChildren() {
            EngineSchemas s = TestSupport.schemas();
            String get = s.describe("scenario", "http/get");
            assertTrue(get.contains("endpoint - required"), get);
            assertTrue(get.contains("response - required"), get);
            assertTrue(s.describe("scenario", "assert/equal").contains("minLength 10"));
            assertTrue(s.describe("integration", "apis/api").contains("https?://"));
            assertTrue(s.describe("global-config", "").contains("environments"));
        }

        @Test
        void keepsSameNamesInTwoNamespacesApart() {
            EngineSchemas s = TestSupport.schemas();
            assertTrue(s.describe("scenario", "postgres").contains("file - required"));
            assertTrue(s.describe("scenario", "var").contains("generate - choice"), "children from a named group");
        }

        @Test
        void unknownPathsListTheChildren() {
            EngineSchemas s = TestSupport.schemas();
            assertTrue(assertThrows(ToolException.class, () -> s.describe("scenario", "http/nope"))
                    .getMessage().contains("Children:"));
            assertThrows(ToolException.class, () -> s.describe("bogus", ""));
        }

        @Test
        void addsTheUsageNoteForJsonPath() {
            assertTrue(TestSupport.schemas().describe("scenario", "assert/jsonPath").contains("usage:"));
        }

        @Test
        void readsSchemasFromTheEngineJar() throws Exception {
            Path jar = tmp.resolve("fake-engine.jar");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
                Path src = Path.of("..", "..", "engine", "src", "main", "resources", "schema").toAbsolutePath()
                        .normalize();
                try (var files = Files.list(src)) {
                    for (Path f : files.toList()) {
                        zos.putNextEntry(new ZipEntry("schema/" + f.getFileName()));
                        zos.write(Files.readAllBytes(f));
                        zos.closeEntry();
                    }
                }
            }
            assertTrue(new EngineSchemas(null, jar.toString()).describe("scenario", "http").contains("alias"));
        }
    }

    @Nested
    class Scaffold {

        @Test
        void createsValidFilesAndKeepsExisting() throws Exception {
            FolderScaffolder sc = TestSupport.scaffolder();
            String first = sc.scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "https://example.com");
            assertTrue(first.contains("Validated"), first);
            Files.writeString(tmp.resolve("global-config.xml"), "mine");
            String second = sc.scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "https://example.com");
            assertTrue(second.contains("kept    global-config.xml"), second);
            assertEquals("mine", Files.readString(tmp.resolve("global-config.xml")));
        }

        @Test
        void switchesTheJunitReportOn() throws Exception {
            TestSupport.scaffolder().scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", LOCAL_URL);
            assertTrue(Files.readString(tmp.resolve("global-config.xml")).contains("<junit enabled=\"true\"/>"));
        }

        @Test
        void refusesUnsafeNamesAndUrls() {
            FolderScaffolder sc = TestSupport.scaffolder();
            assertThrows(ToolException.class, () -> sc.scaffold(tmp.toString(), "a b", "p", "DEFAULT", "http://x"));
            assertThrows(ToolException.class, () -> sc.scaffold(tmp.toString(), "e", "p", "DEFAULT", "ftp://x"));
        }
    }

    @Nested
    class Registry {

        @Test
        void offersEightToolsAndNeedsTheEngineJarToRun() {
            ToolRegistry t = TestSupport.registry();
            assertEquals(8, t.all().size());
            McpTool run = t.all().stream().filter(x -> x.name().equals("testlum_run")).findFirst().orElseThrow();
            JsonNode args = JSON.createObjectNode().put("dir", tmp.toString());
            assertTrue(assertThrows(ToolException.class, () -> run.handler().apply(args))
                    .getMessage().contains("TESTLUM_ENGINE_JAR"));
        }
    }
}
