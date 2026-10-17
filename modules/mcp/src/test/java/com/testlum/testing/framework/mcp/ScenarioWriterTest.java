package com.testlum.testing.framework.mcp;

import com.testlum.testing.framework.mcp.exception.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCENARIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<scenario xmlns=\"http://www.testlum.com/testing/model/scenario\">\n"
            + "<overview><description>Get one user back</description><name>t</name></overview>\n"
            + "<settings><tags>smoke</tags></settings>\n"
            + "<http comment=\"Get the user by id\"><get endpoint=\"/users/{{id}}\">"
            + "<response code=\"200\" file=\"expected_{{id}}.json\"/></get></http>\n</scenario>\n";

    @TempDir
    Path tmp;

    private ScenarioWriter writer;

    @BeforeEach
    void setUp() {
        TestSupport.scaffolder().scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "http://localhost:1");
        writer = TestSupport.writer();
    }

    private static JsonNode json(final String text) {
        return JSON.readTree(text);
    }

    private String read(final String relative) throws IOException {
        return Files.readString(tmp.resolve(relative));
    }

    private void writeUser(final String name, final String id) {
        writer.writeTest(tmp.toString(), name, SCENARIO.replace("{{id}}", id),
                json("{\"expected_" + id + ".json\":\"{}\"}"), false);
    }

    @Test
    void writesFilesAndRefusesBadOrDuplicate() {
        String ok = writer.writeTest(tmp.toString(), "user", SCENARIO, json("{\"expected_1.json\":\"{}\"}"), false);
        assertTrue(ok.contains("scenario.xml, expected_1.json") && ok.contains("PASS"), ok);
        assertTrue(Files.exists(tmp.resolve("scenarios/user/expected_1.json")));
        assertTrue(assertThrows(ToolException.class,
                () -> writer.writeTest(tmp.toString(), "user", SCENARIO, null, false))
                .getMessage().contains("already exists"));
        String bad = SCENARIO.replace("endpoint=\"/users/{{id}}\"", "endpoint=\"users\"");
        assertTrue(assertThrows(ToolException.class, () -> writer.writeTest(tmp.toString(), "bad", bad, null, false))
                .getMessage().contains("schema"));
        assertTrue(Files.notExists(tmp.resolve("scenarios/bad")));
    }

    @Test
    void rollsBackANewScenarioWhenTheCheckFails() {
        String xml = SCENARIO.replace("{{id}}", "9");
        var e = assertThrows(ToolException.class, () -> writer.writeTest(tmp.toString(), "nofile", xml, null, false));
        assertTrue(e.getMessage().contains("Rolled back") && e.getMessage().contains("expected_9.json"),
                e.getMessage());
        assertTrue(Files.notExists(tmp.resolve("scenarios/nofile")));
    }

    @Test
    void rollsBackAnOverwriteToTheOldFiles() throws IOException {
        writeUser("user", "1");
        String before = read("scenarios/user/scenario.xml");
        String broken = SCENARIO.replace("{{id}}", "9");
        JsonNode changed = json("{\"expected_1.json\":\"changed\"}");
        assertThrows(ToolException.class, () -> writer.writeTest(tmp.toString(), "user", broken, changed, true));
        assertEquals(before, read("scenarios/user/scenario.xml"));
        assertEquals("{}", read("scenarios/user/expected_1.json"));
    }

    @Test
    void leavesTheOldScenarioAloneWhenTheNewXmlIsNotValid() throws IOException {
        writeUser("user", "1");
        String before = read("scenarios/user/scenario.xml");
        String invalid = SCENARIO.replace("endpoint=\"/users/{{id}}\"", "endpoint=\"users\"");
        assertThrows(ToolException.class, () -> writer.writeTest(tmp.toString(), "user", invalid, null, true));
        assertEquals(before, read("scenarios/user/scenario.xml"));
    }

    @Test
    void variationsWriteTheCsvAndSetTheSetting() throws Exception {
        writeUser("user", "1");
        String out = writer.generateVariations(tmp.toString(), "user", "users.csv",
                json("[{\"id\":\"1\",\"name\":\"Ana, B\"},{\"id\":\"2\",\"name\":\"Bob\"}]"));
        assertTrue(out.contains("2 rows"), out);
        assertEquals("id,name\n1,\"Ana, B\"\n2,Bob\n", read("data/users.csv"));
        String after = read("scenarios/user/scenario.xml");
        assertTrue(after.contains("<variations>users.csv</variations>"));
        assertEquals(SCENARIO.replace("{{id}}", "1"),
                after.replaceFirst("\\s*<variations>users.csv</variations>", ""), "layout kept");
    }

    @Test
    void variationsReplaceInsteadOfAddingTwice() throws Exception {
        writeUser("user", "1");
        writer.generateVariations(tmp.toString(), "user", "users.csv", json("[{\"id\":\"1\"}]"));
        writer.generateVariations(tmp.toString(), "user", "users.csv", json("[{\"id\":\"1\"}]"));
        String again = read("scenarios/user/scenario.xml");
        assertEquals(1, again.split("<variations>", -1).length - 1, "replaced, not added twice");
        JsonNode uneven = json("[{\"id\":\"1\"},{\"x\":\"2\"}]");
        assertTrue(assertThrows(ToolException.class,
                () -> writer.generateVariations(tmp.toString(), "user", "u.csv", uneven))
                .getMessage().contains("same columns"));
    }

    /** A scenario the check rejects, put on disk by hand since the writer would not let it through. */
    private void writeBroken(final String name) throws IOException {
        Path folder = tmp.resolve("scenarios").resolve(name);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("scenario.xml"), SCENARIO.replace("{{id}}", "1")
                .replace("</scenario>", "<include comment=\"Include a missing one\" scenario=\"ghost\"/></scenario>"));
        Files.writeString(folder.resolve("expected_1.json"), "{}");
    }

    @Test
    void failedVariationsPutBackTheOldCsvAndScenario() throws Exception {
        writeBroken("user");
        Files.createDirectories(tmp.resolve("data"));
        Files.writeString(tmp.resolve("data/users.csv"), "id\n1\n");
        String scenario = read("scenarios/user/scenario.xml");
        JsonNode rows = json("[{\"id\":\"1\"},{\"id\":\"2\"}]");
        assertThrows(ToolException.class, () -> writer.generateVariations(tmp.toString(), "user", "users.csv", rows));
        assertEquals("id\n1\n", read("data/users.csv"));
        assertEquals(scenario, read("scenarios/user/scenario.xml"));
    }

    @Test
    void failedVariationsRemoveACsvThatDidNotExist() throws Exception {
        writeBroken("user");
        JsonNode rows = json("[{\"id\":\"1\"}]");
        assertThrows(ToolException.class, () -> writer.generateVariations(tmp.toString(), "user", "fresh.csv", rows));
        assertTrue(Files.notExists(tmp.resolve("data/fresh.csv")));
    }

    @Test
    void refusesACsvAnotherScenarioAlreadyUses() throws Exception {
        writeUser("one", "1");
        writeUser("two", "1");
        writer.generateVariations(tmp.toString(), "one", "shared.csv", json("[{\"id\":\"1\"}]"));
        String csv = read("data/shared.csv");
        JsonNode rows = json("[{\"id\":\"1\"},{\"id\":\"2\"}]");
        String msg = assertThrows(ToolException.class,
                () -> writer.generateVariations(tmp.toString(), "two", "shared.csv", rows)).getMessage();
        assertTrue(msg.contains("one"), msg);
        assertEquals(csv, read("data/shared.csv"));
    }
}
