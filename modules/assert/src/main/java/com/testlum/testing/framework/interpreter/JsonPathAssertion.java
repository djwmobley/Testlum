package com.testlum.testing.framework.interpreter;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.testlum.testing.model.scenario.AssertJsonPath;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Checks one field of a JSON body: exists, equals a value, matches a regex. */
final class JsonPathAssertion {

    private static final int SNIPPET_LENGTH = 40;

    private final AssertJsonPath assertion;
    private Object value;
    private boolean found = true;

    private JsonPathAssertion(final AssertJsonPath assertion) {
        this.assertion = assertion;
    }

    /** Returns the failure message, or null when every given check holds. */
    static String check(final String body, final AssertJsonPath assertion) {
        return new JsonPathAssertion(assertion).run(body);
    }

    private String run(final String body) {
        String unreadable = read(body);
        if (unreadable != null) {
            return unreadable;
        }
        List<String> failures = new ArrayList<>(existence());
        if (found) {
            failures.addAll(valueChecks());
        }
        return failures.isEmpty() ? null : assertion.getPath() + ": " + String.join("; ", failures);
    }

    private String read(final String body) {
        String trimmed = body == null ? "" : body.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            String start = trimmed.length() > SNIPPET_LENGTH ? trimmed.substring(0, SNIPPET_LENGTH) : trimmed;
            return String.format("%s: the body is not valid JSON (it starts with [%s])", assertion.getPath(), start);
        }
        return parse(body);
    }

    private String parse(final String body) {
        String path = assertion.getPath();
        try {
            value = JsonPath.parse(body).read(path);
        } catch (PathNotFoundException e) {
            found = false;
        } catch (InvalidJsonException | IllegalArgumentException e) {
            return String.format("%s: the body is not valid JSON: %s", path, firstLine(e));
        } catch (RuntimeException e) {
            return String.format("%s: cannot read path: %s", path, firstLine(e));
        }
        return null;
    }

    private List<String> existence() {
        Boolean exists = assertion.isExists();
        boolean valueChecks = assertion.getEquals() != null || assertion.getMatches() != null;
        List<String> failures = new ArrayList<>();
        if (exists != null && !exists && found) {
            failures.add("exists with value [" + text(value) + "] but should not");
        } else if (!found && (exists == null || exists || valueChecks) && !Boolean.FALSE.equals(exists)) {
            failures.add("not found");
        }
        return failures;
    }

    private List<String> valueChecks() {
        List<String> failures = new ArrayList<>();
        String actual = text(value);
        if (assertion.getEquals() != null && !assertion.getEquals().equals(actual)) {
            failures.add(String.format("expected [%s] but was [%s]", assertion.getEquals(), actual));
        }
        if (assertion.getMatches() != null && !matches(assertion.getMatches(), actual)) {
            failures.add(String.format("[%s] does not match [%s]", actual, assertion.getMatches()));
        }
        return failures;
    }

    private static boolean matches(final String regex, final String actual) {
        try {
            return Pattern.compile(regex, Pattern.DOTALL).matcher(actual).matches();
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("matches is not a valid regex: " + regex, e);
        }
    }

    private static String text(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return Configuration.defaultConfiguration().jsonProvider().toJson(value);
    }

    private static String firstLine(final Exception e) {
        String m = String.valueOf(e.getMessage());
        int i = m.indexOf('\n');
        return i < 0 ? m : m.substring(0, i);
    }
}
