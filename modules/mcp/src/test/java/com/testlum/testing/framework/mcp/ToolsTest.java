package com.testlum.testing.framework.mcp;

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

class ToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LOCAL_URL = "http://localhost:1";
    private static final String SCENARIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<scenario xmlns=\"http://www.testlum.com/testing/model/scenario\">\n"
            + "<overview><description>Get one user back</description><name>t</name></overview>\n"
            + "<settings><tags>smoke</tags></settings>\n"
            + "<http comment=\"Get the user by id\"><get endpoint=\"/users/{{id}}\">"
            + "<response code=\"200\" file=\"expected_{{id}}.json\"/></get></http>\n</scenario>\n";

    @TempDir
    Path tmp;

    private static JsonNode json(final String text) {
        return JSON.readTree(text);
    }

    private Writer writer() {
        Schemas s = TestSupport.schemas();
        new Scaffolder(s).scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", LOCAL_URL);
        return new Writer(s, new Checker(s));
    }

    @Nested
    class Lookup {

        @Test
        void describesAttributesAndChildren() {
            Schemas s = TestSupport.schemas();
            String get = s.describe("scenario", "http/get");
            assertTrue(get.contains("endpoint - required"), get);
            assertTrue(get.contains("response - required"), get);
            assertTrue(s.describe("scenario", "assert/equal").contains("minLength 10"));
            assertTrue(s.describe("integration", "apis/api").contains("https?://"));
            assertTrue(s.describe("global-config", "").contains("environments"));
        }

        @Test
        void keepsSameNamesInTwoNamespacesApart() {
            Schemas s = TestSupport.schemas();
            assertTrue(s.describe("scenario", "postgres").contains("file - required"));
            assertTrue(s.describe("scenario", "var").contains("generate - choice"), "children from a named group");
        }

        @Test
        void unknownPathsListTheChildren() {
            Schemas s = TestSupport.schemas();
            assertTrue(assertThrows(Tool.ToolError.class, () -> s.describe("scenario", "http/nope"))
                    .getMessage().contains("Children:"));
            assertThrows(Tool.ToolError.class, () -> s.describe("bogus", ""));
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
            assertTrue(new Schemas(null, jar.toString()).describe("scenario", "http").contains("alias"));
        }
    }

    @Nested
    class Scaffold {

        @Test
        void createsValidFilesAndKeepsExisting() throws Exception {
            Scaffolder sc = new Scaffolder(TestSupport.schemas());
            String first = sc.scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "https://example.com");
            assertTrue(first.contains("Validated"), first);
            Files.writeString(tmp.resolve("global-config.xml"), "mine");
            String second = sc.scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "https://example.com");
            assertTrue(second.contains("kept    global-config.xml"), second);
            assertEquals("mine", Files.readString(tmp.resolve("global-config.xml")));
        }

        @Test
        void switchesTheJunitReportOn() throws Exception {
            new Scaffolder(TestSupport.schemas()).scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", LOCAL_URL);
            assertTrue(Files.readString(tmp.resolve("global-config.xml")).contains("<junit enabled=\"true\"/>"));
        }

        @Test
        void refusesUnsafeNamesAndUrls() {
            Scaffolder sc = new Scaffolder(TestSupport.schemas());
            assertThrows(Tool.ToolError.class, () -> sc.scaffold(tmp.toString(), "a b", "p", "DEFAULT", "http://x"));
            assertThrows(Tool.ToolError.class, () -> sc.scaffold(tmp.toString(), "e", "p", "DEFAULT", "ftp://x"));
        }
    }

    @Nested
    class Run {

        @Test
        void tagConfigEnablesExactlyTheGivenTags() throws Exception {
            Schemas s = TestSupport.schemas();
            new Scaffolder(s).scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", LOCAL_URL);
            Path out = tmp.resolve(".mcp-run-global-config.xml");
            Runner.writeTagConfig(tmp.resolve("global-config.xml"), out, "smoke, S-01,RULE");
            String xml = Files.readString(out);
            assertTrue(xml.contains("runScenariosByTag enabled=\"true\""), xml);
            assertTrue(xml.contains("name=\"smoke\"") && xml.contains("name=\"S-01\"")
                    && xml.contains("name=\"RULE\""), xml);
            assertTrue(xml.contains("<junit enabled=\"true\"/>"), xml);
            assertTrue(s.validate("global-config.xsd", out, "x").isEmpty());
        }

        @Test
        void listsEveryScenarioWithItsStatus() throws Exception {
            Path f = tmp.resolve("junit.xml");
            Files.writeString(f, "<testsuite><testcase name=\"a\" classname=\"scenarios/a/scenario.xml\"/>"
                    + "<testcase name=\"b\" classname=\"scenarios/b/scenario.xml\">"
                    + "<failure message=\"m\">full text</failure></testcase>"
                    + "<testcase name=\"c\" classname=\"scenarios/c/scenario.xml\"><skipped message=\"s\"/>"
                    + "</testcase></testsuite>");
            String out = Runner.scenarioLines(Xml.descendants(Xml.parse(f).getDocumentElement(), "testcase"));
            assertTrue(out.startsWith("PASS a\nFAIL b\nSKIP c\n"), out);
            assertTrue(out.contains("--- b (scenarios/b/scenario.xml)\nfull text"), out);
        }

        @Test
        void stripsAnsiColorCodes() {
            assertEquals("ERROR scenario failed",
                    Runner.stripAnsi("\u001B[31mERROR\u001B[0m scenario \u001B[1;32mfailed\u001B[m"));
        }

        @Test
        void withoutEngineJarExplainsWhatToSet() {
            Tools t = new Tools(TestSupport.schemas(), null);
            Tool run = t.all().stream().filter(x -> x.name().equals("testlum_run")).findFirst().orElseThrow();
            JsonNode args = JSON.createObjectNode().put("dir", tmp.toString());
            assertTrue(assertThrows(Tool.ToolError.class, () -> run.handler().apply(args))
                    .getMessage().contains("TESTLUM_ENGINE_JAR"));
        }
    }

    @Nested
    class WriteTest {

        @Test
        void writesFilesAndRefusesBadOrDuplicate() {
            Writer w = writer();
            String ok = w.writeTest(tmp.toString(), "user", SCENARIO, json("{\"expected_1.json\":\"{}\"}"), false);
            assertTrue(ok.contains("scenario.xml, expected_1.json") && ok.contains("PASS"), ok);
            assertTrue(Files.exists(tmp.resolve("scenarios/user/expected_1.json")));
            assertTrue(assertThrows(Tool.ToolError.class,
                    () -> w.writeTest(tmp.toString(), "user", SCENARIO, null, false))
                    .getMessage().contains("already exists"));
            String bad = SCENARIO.replace("endpoint=\"/users/{{id}}\"", "endpoint=\"users\"");
            assertTrue(assertThrows(Tool.ToolError.class, () -> w.writeTest(tmp.toString(), "bad", bad, null, false))
                    .getMessage().contains("schema"));
            assertTrue(Files.notExists(tmp.resolve("scenarios/bad")));
        }

        @Test
        void rollsBackWhenTheCheckFails() {
            Writer w = writer();
            String xml = SCENARIO.replace("{{id}}", "9");
            var e = assertThrows(Tool.ToolError.class, () -> w.writeTest(tmp.toString(), "nofile", xml, null, false));
            assertTrue(e.getMessage().contains("Rolled back") && e.getMessage().contains("expected_9.json"),
                    e.getMessage());
            assertTrue(Files.notExists(tmp.resolve("scenarios/nofile")));
        }

        @Test
        void variationsWriteTheCsvAndSetTheSetting() throws Exception {
            Writer w = writer();
            String one = SCENARIO.replace("{{id}}", "1");
            w.writeTest(tmp.toString(), "user", one, json("{\"expected_1.json\":\"{}\"}"), false);
            String out = w.generateVariations(tmp.toString(), "user", "users.csv",
                    json("[{\"id\":\"1\",\"name\":\"Ana, B\"},{\"id\":\"2\",\"name\":\"Bob\"}]"));
            assertTrue(out.contains("2 rows") && out.contains("PASS"), out);
            assertEquals("id,name\n1,\"Ana, B\"\n2,Bob\n", Files.readString(tmp.resolve("data/users.csv")));
            String after = Files.readString(tmp.resolve("scenarios/user/scenario.xml"));
            assertTrue(after.contains("<variations>users.csv</variations>"));
            assertEquals(one, after.replaceFirst("\\s*<variations>users.csv</variations>", ""), "layout kept");
        }

        @Test
        void variationsReplaceInsteadOfAddingTwice() throws Exception {
            Writer w = writer();
            w.writeTest(tmp.toString(), "user", SCENARIO.replace("{{id}}", "1"),
                    json("{\"expected_1.json\":\"{}\"}"), false);
            w.generateVariations(tmp.toString(), "user", "users.csv", json("[{\"id\":\"1\"}]"));
            w.generateVariations(tmp.toString(), "user", "users.csv", json("[{\"id\":\"3\"}]"));
            String again = Files.readString(tmp.resolve("scenarios/user/scenario.xml"));
            assertEquals(1, again.split("<variations>", -1).length - 1, "replaced, not added twice");
            JsonNode uneven = json("[{\"id\":\"1\"},{\"x\":\"2\"}]");
            assertTrue(assertThrows(Tool.ToolError.class,
                    () -> w.generateVariations(tmp.toString(), "user", "u.csv", uneven))
                    .getMessage().contains("same columns"));
        }
    }

    @Nested
    class ListAndDelete {

        private static final String XML = "<scenario xmlns=\"http://www.testlum.com/testing/model/scenario\">"
                + "<overview><description>Reads one</description></overview>"
                + "<settings><variations>ids.csv</variations><tags>ADO-7,api</tags></settings>"
                + "<http alias=\"DEFAULT\"><get endpoint=\"/api/x/{{id}}\"><response code=\"200\"/></get></http>%s"
                + "</scenario>";

        private Tests prepare() throws Exception {
            Path sc = tmp.resolve("scenarios");
            Files.createDirectories(sc.resolve("one"));
            Files.createDirectories(sc.resolve("two"));
            Files.createDirectories(sc.resolve("group/three"));
            Files.createDirectories(tmp.resolve("data"));
            Files.writeString(tmp.resolve("data/ids.csv"), "id\n1\n");
            Files.writeString(sc.resolve("one/scenario.xml"), XML.formatted(""));
            Files.writeString(sc.resolve("two/scenario.xml"), XML.formatted("<include scenario=\"one\"/>"));
            Files.writeString(sc.resolve("group/three/scenario.xml"), XML.formatted(""));
            return new Tests();
        }

        @Test
        void listsEveryScenarioAndTheRunGate() throws Exception {
            String list = prepare().list(tmp.toString());
            assertTrue(list.contains("one | tags: ADO-7,api | variations: ids.csv | runs: 1"), list);
            assertTrue(list.contains("total runs 3, include-tagged 0. For testlum_run expectedScenarios use 3"),
                    list);
            assertTrue(list.contains("GET /api/x/{{id}}") && list.contains("three | tags"), list);
        }

        @Test
        void refusesToDeleteWhatIsIncluded() throws Exception {
            Tests t = prepare();
            assertTrue(assertThrows(Tool.ToolError.class, () -> t.delete(tmp.toString(), "one"))
                    .getMessage().contains("two includes"));
        }

        @Test
        void deletesNestedScenariosAndTheUnusedCsv() throws Exception {
            Path sc = tmp.resolve("scenarios");
            Tests t = prepare();
            assertTrue(t.delete(tmp.toString(), "two").startsWith("Deleted scenarios/two"));
            assertTrue(t.delete(tmp.toString(), "three").startsWith("Deleted"));
            assertTrue(Files.notExists(sc.resolve("group/three")));
            assertTrue(t.delete(tmp.toString(), "one").contains("data/ids.csv"));
            assertTrue(Files.notExists(sc.resolve("one")));
        }
    }
}
