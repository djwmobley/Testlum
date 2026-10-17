package com.testlum.testing.framework.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRunnerTest {

    private static final String RESULTS = "<testsuite><testcase name=\"a\" classname=\"scenarios/a/scenario.xml\"/>"
            + "<testcase name=\"b\" classname=\"scenarios/b/scenario.xml\">"
            + "<failure message=\"m\">full text</failure></testcase>"
            + "<testcase name=\"c\" classname=\"scenarios/c/scenario.xml\"><skipped message=\"s\"/></testcase>"
            + "<testcase name=\"d\" classname=\"scenarios/d/scenario.xml\">"
            + "<error message=\"boom\">stack trace</error></testcase></testsuite>";

    @TempDir
    Path tmp;

    private List<Element> cases() throws Exception {
        Path f = tmp.resolve("junit.xml");
        Files.writeString(f, RESULTS);
        return XmlSupport.descendants(XmlSupport.parse(f).getDocumentElement(), "testcase");
    }

    private Path junit(final String day) throws IOException {
        Path f = tmp.resolve("report").resolve(day).resolve("junit.xml");
        Files.createDirectories(f.getParent());
        Files.writeString(f, RESULTS);
        return f;
    }

    @Test
    void tagConfigEnablesExactlyTheGivenTags() throws Exception {
        TestSupport.scaffolder().scaffold(tmp.toString(), "sandbox", "demo", "DEFAULT", "http://localhost:1");
        Path out = tmp.resolve(".mcp-run-global-config.xml");
        EngineRunner.writeTagConfig(tmp.resolve("global-config.xml"), out, "smoke, S-01,RULE");
        String xml = Files.readString(out);
        assertTrue(xml.contains("runScenariosByTag enabled=\"true\""), xml);
        assertTrue(xml.contains("name=\"smoke\"") && xml.contains("name=\"S-01\"")
                && xml.contains("name=\"RULE\""), xml);
        assertTrue(xml.contains("<junit enabled=\"true\"/>"), xml);
        assertTrue(TestSupport.schemas().validate("global-config.xsd", out, "x").isEmpty());
    }

    @Test
    void listsEveryScenarioWithItsStatusAndTheErrorText() throws Exception {
        String out = EngineRunner.scenarioLines(cases());
        assertTrue(out.startsWith("PASS a\nFAIL b\nSKIP c\nFAIL d\n"), out);
        assertTrue(out.contains("--- b (scenarios/b/scenario.xml)\nfull text"), out);
        assertTrue(out.contains("--- d (scenarios/d/scenario.xml)\nstack trace"), out);
    }

    @Test
    void countsAnErrorAsAFailure() throws Exception {
        cases();
        StringBuilder sb = new StringBuilder();
        EngineRunner.junitSummary(sb, tmp.resolve("junit.xml"), new ScenarioCatalog.Gate(0, List.of()), -1);
        assertTrue(sb.toString().contains("4 total, 1 passed, 2 failed, 1 skipped"), sb.toString());
    }

    @Test
    void theGateCountsRunsWithoutTheIncludeExecutions() throws Exception {
        cases();
        StringBuilder sb = new StringBuilder();
        boolean failed = EngineRunner.junitSummary(sb, tmp.resolve("junit.xml"),
                new ScenarioCatalog.Gate(1, List.of()), 2);
        assertFalse(failed, sb.toString());
        assertTrue(sb.toString().contains("gate ok: 2 runs ran"), sb.toString());
        assertTrue(EngineRunner.junitSummary(new StringBuilder(), tmp.resolve("junit.xml"),
                new ScenarioCatalog.Gate(1, List.of()), 3));
    }

    @Test
    void theGateIsNotCheckedWhenAScenarioCouldNotBeRead() throws Exception {
        cases();
        StringBuilder sb = new StringBuilder();
        assertTrue(EngineRunner.junitSummary(sb, tmp.resolve("junit.xml"),
                new ScenarioCatalog.Gate(0, List.of("broken")), 3));
        assertTrue(sb.toString().contains("GATE NOT CHECKED") && sb.toString().contains("broken"), sb.toString());
    }

    @Test
    void ignoresAJunitFileTheRunDidNotWrite() throws Exception {
        Path old = junit("10-01-2026");
        Map<Path, String> before = EngineRunner.junitStamps(tmp);
        assertNull(EngineRunner.freshJunit(tmp, before));
        Path fresh = junit("10-02-2026");
        assertEquals(fresh, EngineRunner.freshJunit(tmp, before));
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertNotNull(EngineRunner.freshJunit(tmp, before));
    }

    @Test
    void seesARewrittenFileEvenWithTheSameName() throws Exception {
        Path f = junit("10-01-2026");
        Map<Path, String> before = EngineRunner.junitStamps(tmp);
        Files.writeString(f, RESULTS + "\n");
        assertEquals(f, EngineRunner.freshJunit(tmp, before));
    }

    @Test
    void stripsAnsiColorCodes() {
        assertEquals("ERROR scenario failed",
                EngineRunner.stripAnsi("\u001B[31mERROR\u001B[0m scenario \u001B[1;32mfailed\u001B[m"));
    }
}
