package com.testlum.testing.framework.report;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JUnitXmlWriterTest {

    @Test
    void writesOneSuiteWithPassFailAndSkippedCases() throws Exception {
        ScenarioResult pass = result("/t/scenarios/pass/scenario.xml", true, null, 1500);
        ScenarioResult fail = result("/t/scenarios/fail/scenario.xml", false, "Equality <1,2> is not equal.\u0001", 20);
        Map<String, String> skipped = Map.of("/off/scenario.xml", "inactive");

        Element suite = parse(JUnitXmlWriter.toXml(List.of(pass, fail), skipped));

        assertEquals("testsuite", suite.getTagName());
        assertEquals("3", suite.getAttribute("tests"));
        assertEquals("1", suite.getAttribute("failures"));
        assertEquals("1", suite.getAttribute("skipped"));
        assertEquals("1.520", suite.getAttribute("time"));
        NodeList cases = suite.getElementsByTagName("testcase");
        assertEquals("pass", ((Element) cases.item(0)).getAttribute("name"));
        assertEquals("/t/scenarios/pass/scenario.xml", ((Element) cases.item(0)).getAttribute("classname"));
        assertEquals("1.500", ((Element) cases.item(0)).getAttribute("time"));
        Element failure = (Element) ((Element) cases.item(1)).getElementsByTagName("failure").item(0);
        assertNotNull(failure);
        assertEquals("Equality <1,2> is not equal.", failure.getAttribute("message").strip());
        Element skip = (Element) ((Element) cases.item(2)).getElementsByTagName("skipped").item(0);
        assertEquals("inactive", skip.getAttribute("message"));
        assertEquals("off", ((Element) cases.item(2)).getAttribute("name"));
    }

    private static ScenarioResult result(final String path, final boolean ok, final String cause, final long ms) {
        ScenarioResult r = new ScenarioResult();
        r.setPath(path);
        r.setSuccess(ok);
        r.setCause(cause);
        r.setExecutionTime(ms);
        return r;
    }

    private static Element parse(final String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }
}
