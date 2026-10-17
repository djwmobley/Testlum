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
        assertTrue(s.describe("scenario", "postgres").contains("file - required"), "same name in two namespaces");
        assertTrue(s.describe("scenario", "var").contains("generate - choice"), "children from a named group");
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
    void listsEveryScenarioWithItsStatus() throws Exception {
        Path f = tmp.resolve("junit.xml");
        Files.writeString(f, "<testsuite><testcase name=\"a\" classname=\"scenarios/a/scenario.xml\"/>"
                + "<testcase name=\"b\" classname=\"scenarios/b/scenario.xml\"><failure message=\"m\">full text</failure></testcase>"
                + "<testcase name=\"c\" classname=\"scenarios/c/scenario.xml\"><skipped message=\"s\"/></testcase></testsuite>");
        String out = Runner.scenarioLines(Xml.descendants(Xml.parse(f).getDocumentElement(), "testcase"));
        assertTrue(out.startsWith("PASS a\nFAIL b\nSKIP c\n"), out);
        assertTrue(out.contains("--- b (scenarios/b/scenario.xml)\nfull text"), out);
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

    private static final String SCENARIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<scenario xmlns=\"http://www.testlum.com/testing/model/scenario\">\n"
            + "<overview><description>Get one user back</description><name>t</name></overview>\n"
            + "<settings><tags>smoke</tags></settings>\n"
            + "<http comment=\"Get the user by id\"><get endpoint=\"/users/{{id}}\">"
            + "<response code=\"200\" file=\"expected_{{id}}.json\"/></get></http>\n</scenario>\n";

    private Writer writer() {
        Schemas s = TestSupport.schemas();
        new Scaffolder(s).scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "http://localhost:1");
        return new Writer(s, new Checker(s));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(final String text) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
    }

    @Test
    void writeTestWritesFilesAndRefusesBadOrDuplicate() throws Exception {
        Writer w = writer();
        String ok = w.writeTest(tmp.toString(), "user", SCENARIO, json("{\"expected_1.json\":\"{}\"}"), false);
        assertTrue(ok.contains("scenario.xml, expected_1.json") && ok.contains("PASS"), ok);
        assertTrue(Files.exists(tmp.resolve("scenarios/user/expected_1.json")));
        assertTrue(assertThrows(Tool.ToolError.class, () -> w.writeTest(tmp.toString(), "user", SCENARIO, null, false))
                .getMessage().contains("already exists"));
        String bad = SCENARIO.replace("endpoint=\"/users/{{id}}\"", "endpoint=\"users\"");
        assertTrue(assertThrows(Tool.ToolError.class, () -> w.writeTest(tmp.toString(), "bad", bad, null, false))
                .getMessage().contains("schema"));
        assertTrue(Files.notExists(tmp.resolve("scenarios/bad")));
    }

    @Test
    void writeTestRollsBackWhenTheCheckFails() {
        Writer w = writer();
        var e = assertThrows(Tool.ToolError.class,
                () -> w.writeTest(tmp.toString(), "nofile", SCENARIO.replace("{{id}}", "9"), null, false));
        assertTrue(e.getMessage().contains("Rolled back") && e.getMessage().contains("expected_9.json"), e.getMessage());
        assertTrue(Files.notExists(tmp.resolve("scenarios/nofile")));
    }

    @Test
    void variationsWriteTheCsvAndSetTheSetting() throws Exception {
        Writer w = writer();
        w.writeTest(tmp.toString(), "user", SCENARIO.replace("{{id}}", "1"), json("{\"expected_1.json\":\"{}\"}"), false);
        String out = w.generateVariations(tmp.toString(), "user", "users.csv",
                json("[{\"id\":\"1\",\"name\":\"Ana, B\"},{\"id\":\"2\",\"name\":\"Bob\"}]"));
        assertTrue(out.contains("2 rows") && out.contains("PASS"), out);
        assertEquals("id,name\n1,\"Ana, B\"\n2,Bob\n", Files.readString(tmp.resolve("data/users.csv")));
        assertTrue(Files.readString(tmp.resolve("scenarios/user/scenario.xml")).contains("<variations>users.csv</variations>"));
        assertTrue(assertThrows(Tool.ToolError.class, () -> w.generateVariations(tmp.toString(), "user", "u.csv",
                json("[{\"id\":\"1\"},{\"x\":\"2\"}]"))).getMessage().contains("same columns"));
    }

    @Test
    void listAndDeleteTests() throws Exception {
        Path sc = tmp.resolve("scenarios");
        String xml = "<scenario xmlns=\"http://www.testlum.com/testing/model/scenario\"><overview><description>Reads one</description></overview>"
                + "<settings><variations>ids.csv</variations><tags>ADO-7,api</tags></settings>"
                + "<http alias=\"DEFAULT\"><get endpoint=\"/api/x/{{id}}\"><response code=\"200\"/></get></http>%s</scenario>";
        Files.createDirectories(sc.resolve("one"));
        Files.createDirectories(sc.resolve("two"));
        Files.createDirectories(tmp.resolve("data"));
        Files.writeString(tmp.resolve("data/ids.csv"), "id\n1\n");
        Files.writeString(sc.resolve("one/scenario.xml"), xml.formatted(""));
        Files.writeString(sc.resolve("two/scenario.xml"), xml.formatted("<include scenario=\"one\"/>"));
        Tests t = new Tests();
        String list = t.list(tmp.toString());
        assertTrue(list.contains("one | tags: ADO-7,api | variations: ids.csv | runs: 1"), list);
        assertTrue(list.contains("total runs 2, include-tagged 0. For testlum_run expectedScenarios use 2"), list);
        assertTrue(list.contains("GET /api/x/{{id}}"), list);
        assertTrue(assertThrows(Tool.ToolError.class, () -> t.delete(tmp.toString(), "one")).getMessage().contains("two includes"));
        assertTrue(t.delete(tmp.toString(), "two").startsWith("Deleted scenarios/two"));
        assertTrue(t.delete(tmp.toString(), "one").contains("data/ids.csv"));
        assertTrue(Files.notExists(sc.resolve("one")));
    }
}
