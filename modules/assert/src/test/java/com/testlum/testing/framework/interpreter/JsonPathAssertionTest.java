package com.testlum.testing.framework.interpreter;

import com.testlum.testing.model.scenario.AssertJsonPath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathAssertionTest {

    private static final String BODY =
            "{\"id\":7,\"title\":\"Login page\",\"points\":3.5,\"done\":false,\"owner\":null,"
                    + "\"tags\":[\"ui\",\"auth\"],\"team\":{\"lead\":\"Ana\"}}";

    private static AssertJsonPath assertion(final String path, final Boolean exists, final String equals,
                                            final String matches) {
        AssertJsonPath a = new AssertJsonPath();
        a.setPath(path);
        a.setExists(exists);
        a.setEquals(equals);
        a.setMatches(matches);
        return a;
    }

    @Test
    void fieldMustExistWhenNothingElseIsGiven() {
        assertNull(JsonPathAssertion.check(BODY, assertion("$.title", null, null, null)));
        assertEquals("$.missing: not found", JsonPathAssertion.check(BODY, assertion("$.missing", null, null, null)));
    }

    @Test
    void existsFalseNeedsTheFieldToBeAbsent() {
        assertNull(JsonPathAssertion.check(BODY, assertion("$.missing", false, null, null)));
        assertTrue(JsonPathAssertion.check(BODY, assertion("$.id", false, null, null))
                .contains("exists with value [7] but should not"));
    }

    @Test
    void equalsComparesTheValueAsText() {
        assertNull(JsonPathAssertion.check(BODY, assertion("$.id", null, "7", null)));
        assertNull(JsonPathAssertion.check(BODY, assertion("$.points", null, "3.5", null)));
        assertNull(JsonPathAssertion.check(BODY, assertion("$.done", null, "false", null)));
        assertNull(JsonPathAssertion.check(BODY, assertion("$.owner", null, "null", null)));
        assertNull(JsonPathAssertion.check(BODY, assertion("$.team.lead", null, "Ana", null)));
        assertNull(JsonPathAssertion.check(BODY, assertion("$.tags", null, "[\"ui\",\"auth\"]", null)));
        assertEquals("$.title: expected [Other] but was [Login page]",
                JsonPathAssertion.check(BODY, assertion("$.title", null, "Other", null)));
    }

    @Test
    void matchesNeedsTheWholeValueToMatch() {
        assertNull(JsonPathAssertion.check(BODY, assertion("$.title", null, null, "Login.*")));
        assertEquals("$.title: [Login page] does not match [Login]",
                JsonPathAssertion.check(BODY, assertion("$.title", null, null, "Login")));
    }

    @Test
    void everyGivenCheckMustHold() {
        assertNull(JsonPathAssertion.check(BODY, assertion("$.id", true, "7", "\\d+")));
        String failure = JsonPathAssertion.check(BODY, assertion("$.id", true, "8", "x"));
        assertTrue(failure.contains("expected [8] but was [7]") && failure.contains("does not match [x]"), failure);
    }

    @Test
    void missingFieldWithValueCheckReportsNotFound() {
        assertEquals("$.nope: not found", JsonPathAssertion.check(BODY, assertion("$.nope", null, "x", null)));
    }

    @Test
    void invalidJsonIsReported() {
        assertTrue(JsonPathAssertion.check("not json", assertion("$.a", null, null, null)).contains("valid JSON"));
    }
}
