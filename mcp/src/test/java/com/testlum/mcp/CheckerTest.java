package com.testlum.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckerTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<scenario xmlns=\"http://www.testlum.com/testing/model/scenario\">\n"
            + "<overview><description>A scenario for the checker test</description><name>t</name></overview>\n";

    @TempDir
    Path tests;

    private Schemas schemas;
    private Checker checker;

    @BeforeEach
    void setUp() {
        schemas = TestSupport.schemas();
        checker = new Checker(schemas);
        new Scaffolder(schemas).scaffold(tests.toString(), "sandbox", "tests", "DEFAULT", "http://localhost:1");
    }

    private void scenario(final String name, final String body) throws IOException {
        Path d = tests.resolve("scenarios").resolve(name);
        Files.createDirectories(d);
        Files.writeString(d.resolve("scenario.xml"), HEAD + body + "\n</scenario>\n");
    }

    private String check(final boolean requireTags, final String pattern) {
        return checker.check(tests.toString(), "", "global-config.xml", requireTags, pattern);
    }

    @Test
    void scaffoldedFolderPasses() {
        assertTrue(check(false, "").startsWith("PASS: 0 errors, 0 warnings, 0 scenarios"));
    }

    @Test
    void goodScenarioPasses() throws IOException {
        scenario("ok", "<settings><tags>smoke</tags></settings>"
                + "<http comment=\"Get user one from the stub\"><get endpoint=\"/users/1\">"
                + "<response code=\"200\" file=\"expected_1.json\"/></get></http>");
        Files.writeString(tests.resolve("scenarios/ok/expected_1.json"), "{}");
        String r = check(true, "smoke");
        assertTrue(r.startsWith("PASS"), r);
    }

    @Test
    void reportsSchemaAndRuleProblems() throws IOException {
        scenario("bad", "<settings active=\"false\"><tags>x</tags></settings>"
                + "<include comment=\"Include something missing\" scenario=\"ghost\"/>"
                + "<include comment=\"Include a plain scenario\" scenario=\"plain\"/>"
                + "<http comment=\"short\" alias=\"OTHER\"><get endpoint=\"users\">"
                + "<response code=\"200\" file=\"expected_9.json\"/></get></http>");
        scenario("plain", "<settings><tags>smoke</tags></settings>");
        String r = check(false, "S-\\d+,.+");
        assertTrue(r.startsWith("FAIL"), r);
        assertTrue(r.contains("endpoint"), "XSD: endpoint must start with a slash\n" + r);
        assertTrue(r.contains("ghost"), r);
        assertTrue(r.contains("not tagged 'include'"), r);
        assertTrue(r.contains("alias 'OTHER'"), r);
        assertTrue(r.contains("expected_9.json"), r);
        assertTrue(r.contains("active=\"false\""), r);
        assertTrue(r.contains("do not match the required pattern"), r);
        assertTrue(r.matches("(?s).*scenarios/bad/scenario.xml:\\d+:\\d+ \\[error].*"), r);
    }

    @Test
    void missingLayoutIsAnError() throws IOException {
        Files.delete(tests.resolve("locators/pages/placeholder.xml"));
        Files.delete(tests.resolve("locators/pages"));
        Files.writeString(tests.resolve("locators/component/note.txt"), "not xml");
        String r = check(false, "");
        assertTrue(r.contains("locators/pages"), r);
        assertTrue(r.contains("locators/component/note.txt"), r);
    }

    @Test
    void scanCountsIncludes() throws IOException {
        scenario("inc", "<settings><tags>include</tags></settings>");
        scenario("real", "<settings><tags>smoke</tags></settings>");
        Checker.Scan s = checker.scan(tests);
        assertTrue(s.scenarios() == 2 && s.includeTagged() == 1);
        assertFalse(check(false, "").contains("not tagged"));
    }
}
