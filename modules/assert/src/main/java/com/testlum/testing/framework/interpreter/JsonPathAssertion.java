package com.testlum.testing.framework.interpreter;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider;
import com.testlum.testing.model.scenario.AssertJsonPath;

import net.minidev.json.parser.JSONParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Checks one field of a JSON body: exists, equals a value, matches a regex. */
final class JsonPathAssertion {

    private static final int SNIPPET_LENGTH = 40;
    private static final Pattern SCALAR = Pattern.compile("-?\\d.*|true|false|null|\".*\"", Pattern.DOTALL);
    private static final String FAILURE = "%s: %s";
    private static final String NO_BODY = "%s: there is no response body to read";
    private static final String NOT_JSON_AT_START = "%s: the body is not valid JSON (it starts with [%s])";
    private static final String NOT_JSON = "%s: the body is not valid JSON: %s";
    private static final String CANNOT_READ_PATH = "%s: cannot read path: %s";
    private static final String NOT_FOUND = "not found";
    private static final String EXISTS_BUT_SHOULD_NOT = "exists with value [%s] but should not";
    private static final String WRONG_VALUE = "expected [%s] but was [%s]";
    private static final String NO_MATCH = "[%s] does not match [%s]";
    private static final String CONTRADICTION = "%s: exists=false cannot be combined with equals or matches";
    private static final String BAD_REGEX = "matches is not a valid regex: %s";
    // json-smart's default mode accepts truncated and unquoted JSON, which would let a cut-off body pass
    private static final Configuration STRICT = Configuration.builder()
            .jsonProvider(new JsonSmartJsonProvider(JSONParser.MODE_RFC4627)).build();

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
        if (Boolean.FALSE.equals(assertion.isExists()) && (assertion.getEquals() != null
                || assertion.getMatches() != null)) {
            return String.format(CONTRADICTION, assertion.getPath());
        }
        String unreadable = read(body);
        if (unreadable != null) {
            return unreadable;
        }
        List<String> failures = new ArrayList<>(existence());
        if (found) {
            failures.addAll(valueChecks());
        }
        return failures.isEmpty() ? null : String.format(FAILURE, assertion.getPath(), String.join("; ", failures));
    }

    private String read(final String body) {
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.isEmpty()) {
            return String.format(NO_BODY, assertion.getPath());
        }
        if (SCALAR.matcher(trimmed).matches()) {
            return parseScalar(trimmed);
        }
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            String start = trimmed.length() > SNIPPET_LENGTH ? trimmed.substring(0, SNIPPET_LENGTH) : trimmed;
            return String.format(NOT_JSON_AT_START, assertion.getPath(), start);
        }
        return parse(body);
    }

    /** A body that is only a number, string, boolean or null has nothing but the root to read. */
    private String parseScalar(final String text) {
        try {
            value = JsonPath.using(STRICT).parse("[" + text + "]").read("$[0]");
            found = "$".equals(assertion.getPath().strip());
        } catch (RuntimeException e) {
            return String.format(NOT_JSON, assertion.getPath(), firstLine(e));
        }
        return null;
    }

    private String parse(final String body) {
        String path = assertion.getPath();
        try {
            JsonPath compiled = JsonPath.compile(path);
            value = JsonPath.using(STRICT).parse(body).read(compiled);
            found = compiled.isDefinite() || !(value instanceof List<?> list && list.isEmpty());
        } catch (PathNotFoundException e) {
            found = false;
        } catch (InvalidJsonException | IllegalArgumentException e) {
            return String.format(NOT_JSON, path, firstLine(e));
        } catch (RuntimeException e) {
            return String.format(CANNOT_READ_PATH, path, firstLine(e));
        }
        return null;
    }

    private List<String> existence() {
        boolean wantsAbsent = Boolean.FALSE.equals(assertion.isExists());
        if (found && wantsAbsent) {
            return List.of(String.format(EXISTS_BUT_SHOULD_NOT, text(value)));
        }
        return !found && !wantsAbsent ? List.of(NOT_FOUND) : List.of();
    }

    private List<String> valueChecks() {
        List<String> failures = new ArrayList<>();
        String actual = text(value);
        if (assertion.getEquals() != null && !assertion.getEquals().equals(actual)) {
            failures.add(String.format(WRONG_VALUE, assertion.getEquals(), actual));
        }
        if (assertion.getMatches() != null) {
            matchFailure(assertion.getMatches(), actual).ifPresent(failures::add);
        }
        return failures;
    }

    private static Optional<String> matchFailure(final String regex, final String actual) {
        try {
            boolean ok = Pattern.compile(regex, Pattern.DOTALL).matcher(actual).matches();
            return ok ? Optional.empty() : Optional.of(String.format(NO_MATCH, actual, regex));
        } catch (PatternSyntaxException e) {
            return Optional.of(String.format(BAD_REGEX, regex));
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
