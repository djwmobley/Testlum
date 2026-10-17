package com.testlum.testing.framework.mcp;

import com.testlum.testing.framework.mcp.exception.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioCatalogTest {

    private static final String XML = "<scenario xmlns=\"http://www.testlum.com/testing/model/scenario\">"
            + "<overview><description>Reads one</description></overview>"
            + "<settings>%s<tags>%s</tags></settings>"
            + "<http alias=\"DEFAULT\"><get endpoint=\"/api/x/{{id}}\"><response code=\"200\"/></get></http>%s"
            + "</scenario>";
    private static final String ENV_CONFIG = "<globalTestConfiguration "
            + "xmlns=\"http://www.testlum.com/testing/model/global-config\"><environments>"
            + "<env folder=\"a\" enabled=\"true\"/><env folder=\"b\" enabled=\"true\"/>"
            + "<env folder=\"c\" enabled=\"false\"/></environments></globalTestConfiguration>";

    @TempDir
    Path tmp;

    private final ScenarioCatalog catalog = new ScenarioCatalog();

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(tmp.resolve("data"));
        Files.writeString(tmp.resolve("data/ids.csv"), "id\n1\n2\n");
        Files.writeString(tmp.resolve("global-config.xml"), ENV_CONFIG);
    }

    private void scenario(final String name, final String variations, final String tags, final String extra)
            throws IOException {
        Path d = tmp.resolve("scenarios").resolve(name);
        Files.createDirectories(d);
        String var = variations.isEmpty() ? "" : "<variations>" + variations + "</variations>";
        Files.writeString(d.resolve("scenario.xml"), XML.formatted(var, tags, extra));
    }

    private static String include(final String name) {
        return "<include scenario=\"" + name + "\"/>";
    }

    @Test
    void listsEveryScenarioAndTheRunCount() throws IOException {
        scenario("one", "ids.csv", "smoke,api", "");
        scenario("two", "", "api", include("one"));
        scenario("group/three", "", "api", "");
        String list = catalog.list(tmp.toString());
        assertTrue(list.contains("one | tags: smoke,api | variations: ids.csv | runs: 2"), list);
        assertTrue(list.contains("total runs 4, include-tagged 0, enabled environments 2. "
                + "For testlum_run expectedScenarios use 8"), list);
        assertTrue(list.contains("GET /api/x/{{id}}") && list.contains("group/three | tags"), list);
    }

    @Test
    void refusesToDeleteWhatIsIncluded() throws IOException {
        scenario("one", "", "api", "");
        scenario("two", "", "api", include("one"));
        assertTrue(assertThrows(ToolException.class, () -> catalog.delete(tmp.toString(), "one"))
                .getMessage().contains("two includes"));
    }

    @Test
    void matchesIncludesOnTheFullPathNotTheLastName() throws IOException {
        scenario("a/login", "", "include", "");
        scenario("b/login", "", "include", "");
        scenario("user", "", "api", include("a/login"));
        assertThrows(ToolException.class, () -> catalog.delete(tmp.toString(), "a/login"));
        assertTrue(catalog.delete(tmp.toString(), "b/login").startsWith("Deleted scenarios/b/login"));
        assertTrue(Files.isDirectory(tmp.resolve("scenarios/a/login")));
    }

    @Test
    void anIncludeWithAVariableCountsAsIncludingAnyMatchingScenario() throws IOException {
        scenario("step/one", "", "include", "");
        scenario("user", "", "api", include("step/{{n}}"));
        assertThrows(ToolException.class, () -> catalog.delete(tmp.toString(), "step/one"));
    }

    @Test
    void refusesToDeleteWhileAScenarioCannotBeRead() throws IOException {
        scenario("one", "", "api", "");
        Path broken = tmp.resolve("scenarios/broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("scenario.xml"), "<scenario>");
        String msg = assertThrows(ToolException.class, () -> catalog.delete(tmp.toString(), "one")).getMessage();
        assertTrue(msg.contains("broken"), msg);
        assertTrue(Files.isDirectory(tmp.resolve("scenarios/one")));
    }

    @Test
    void deletesNestedScenariosAndTheUnusedCsvOnly() throws IOException {
        scenario("one", "ids.csv", "api", "");
        scenario("group/three", "ids.csv", "api", "");
        assertEquals("Deleted scenarios/group/three", catalog.delete(tmp.toString(), "group/three"));
        assertTrue(Files.notExists(tmp.resolve("scenarios/group/three")));
        assertTrue(Files.exists(tmp.resolve("data/ids.csv")), "still used by one");
        assertTrue(catalog.delete(tmp.toString(), "one").contains("data/ids.csv"));
        assertTrue(Files.notExists(tmp.resolve("data/ids.csv")));
    }

    @Test
    void gateCountsIncludeRunsOverVariationsAndEnvironments() throws IOException {
        scenario("login", "ids.csv", "include", "");
        scenario("user", "", "api", include("login"));
        ScenarioCatalog.Gate gate = catalog.gate(tmp, "global-config.xml", "");
        assertEquals(4, gate.includeExecutions(), "2 rows x 2 environments");
        assertTrue(gate.unreadable().isEmpty());
    }

    @Test
    void gateOnlyCountsIncludesOfTheSelectedTags() throws IOException {
        scenario("login", "", "include,smoke", "");
        scenario("logout", "", "include,slow", "");
        assertEquals(2, catalog.gate(tmp, "global-config.xml", "smoke").includeExecutions());
    }

    @Test
    void gateNamesScenariosItCouldNotRead() throws IOException {
        Path broken = tmp.resolve("scenarios/broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("scenario.xml"), "<scenario>");
        assertEquals(java.util.List.of("broken"), catalog.gate(tmp, "global-config.xml", "").unreadable());
    }
}
