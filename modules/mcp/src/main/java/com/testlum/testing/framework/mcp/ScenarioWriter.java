package com.testlum.testing.framework.mcp;

import org.springframework.stereotype.Component;
import com.testlum.testing.framework.mcp.exception.ToolException;
import com.testlum.testing.framework.mcp.constant.ToolMessage;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/** Writes a scenario with its data files, and turns a table of values into engine variations. */
@RequiredArgsConstructor
@Component
final class ScenarioWriter {

    private static final String SCENARIO_XML = "scenario.xml";
    private static final String GLOBAL_CONFIG = "global-config.xml";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CSV_NAME = Pattern.compile("[a-zA-Z\\d_-]+\\.csv");
    private static final Pattern VARIATIONS =
            Pattern.compile("(<(?:\\w+:)?variations>)[^<]*(</(?:\\w+:)?variations>)");
    private static final Pattern SETTINGS_OPEN = Pattern.compile("<((?:\\w+:)?)settings(\\s[^>]*)?>");
    private static final Pattern SETTINGS_EMPTY = Pattern.compile("<((?:\\w+:)?)settings(\\s[^>]*)?/>");

    private final EngineSchemas schemas;
    private final TestsFolderChecker checker;
    private final ScenarioCatalog catalog;

    String writeTest(final String dir, final String name, final String xml, final JsonNode files,
                     final boolean overwrite) {
        Path folder = scenarioFolder(Path.of(dir).toAbsolutePath().normalize(), name);
        if (Files.exists(folder.resolve(SCENARIO_XML)) && !overwrite) {
            throw new ToolException(ToolMessage.ALREADY_EXISTS, name);
        }
        Map<String, String> content = contentOf(xml, files);
        requireValid(xml);
        Map<String, String> before = write(folder, content);
        String report = checker.check(dir, name, GLOBAL_CONFIG, false, "");
        if (report.startsWith("FAIL")) {
            restore(folder, before);
            throw new ToolException(ToolMessage.ROLLED_BACK, report);
        }
        return "Wrote scenarios/" + name + ": " + String.join(", ", content.keySet()) + "\n" + report;
    }

    private static Map<String, String> contentOf(final String xml, final JsonNode files) {
        Map<String, String> content = new LinkedHashMap<>();
        content.put(SCENARIO_XML, xml);
        if (files != null && files.isObject()) {
            files.properties().forEach(e -> content.put(plainName(e.getKey()), e.getValue().asText()));
        }
        return content;
    }

    private void requireValid(final String xml) {
        Path scratch = null;
        try {
            scratch = Files.createTempFile("testlum-mcp-", ".xml");
            Files.writeString(scratch, xml, StandardCharsets.UTF_8);
            List<EngineSchemas.Issue> bad = schemas.validate("scenario.xsd", scratch, SCENARIO_XML);
            if (!bad.isEmpty()) {
                throw new ToolException(ToolMessage.NOT_VALID, schemas.namespaceOf("scenario"), join(bad));
            }
        } catch (IOException e) {
            throw new ToolException(ToolMessage.CANNOT_STAGE, e.getMessage());
        } finally {
            deleteQuietly(scratch);
        }
    }

    private static void deleteQuietly(final Path file) {
        if (file != null) {
            file.toFile().delete();
        }
    }

    String generateVariations(final String dir, final String name, final String csv, final JsonNode rows) {
        Path root = Path.of(dir).toAbsolutePath().normalize();
        Path scenario = scenarioFolder(root, name).resolve(SCENARIO_XML);
        requireInputs(scenario, name, csv, rows);
        requireUnshared(root, name, csv);
        List<String> columns = new ArrayList<>(rows.get(0).propertyNames());
        Path data = root.resolve("data").resolve(csv);
        String report = saveChecked(dir, name, scenario, data, table(columns, rows));
        return "data/" + csv + ": " + rows.size() + " rows, columns " + columns + "\nscenarios/" + name
                + " now runs once per row. Use {{" + columns.get(0) + "}} etc. in the scenario and in file names.\n"
                + report;
    }

    private String saveChecked(final String dir, final String name, final Path scenario, final Path data,
                               final String table) {
        Map<Path, String> before = snapshot(scenario, data);
        saveVariations(data, scenario, data.getFileName().toString(), table);
        String report = checker.check(dir, name, GLOBAL_CONFIG, false, "");
        if (report.startsWith("FAIL")) {
            before.forEach(ScenarioWriter::restoreFile);
            throw new ToolException(ToolMessage.ROLLED_BACK, report);
        }
        return report;
    }

    /** A csv another scenario reads would change that scenario's runs, so it is never overwritten from here. */
    private void requireUnshared(final Path root, final String name, final String csv) {
        List<String> others = catalog.variationUsers(root, csv).stream().filter(n -> !n.equals(name)).toList();
        if (!others.isEmpty()) {
            throw new ToolException(ToolMessage.CSV_IN_USE, csv, String.join(", ", others));
        }
    }

    private static Map<Path, String> snapshot(final Path... files) {
        Map<Path, String> before = new LinkedHashMap<>();
        try {
            for (Path f : files) {
                before.put(f, Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null);
            }
        } catch (IOException e) {
            throw new ToolException(e, ToolMessage.CANNOT_ADD_VARIATIONS, e.getMessage());
        }
        return before;
    }

    private static void requireInputs(final Path scenario, final String name, final String csv, final JsonNode rows) {
        if (!Files.isRegularFile(scenario)) {
            throw new ToolException(ToolMessage.NO_SCENARIO_FOR_VARIATIONS, name);
        }
        if (!CSV_NAME.matcher(csv).matches()) {
            throw new ToolException(ToolMessage.BAD_CSV_NAME);
        }
        if (rows == null || !rows.isArray() || rows.isEmpty() || !rows.get(0).isObject()) {
            throw new ToolException(ToolMessage.BAD_ROWS);
        }
    }

    private static String table(final List<String> columns, final JsonNode rows) {
        StringBuilder table = new StringBuilder(String.join(",", columns.stream().map(ScenarioWriter::quote).toList()));
        for (JsonNode row : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : columns) {
                if (!row.has(c)) {
                    throw new ToolException(ToolMessage.UNEVEN_ROWS, c, row);
                }
                cells.add(quote(row.get(c).asText()));
            }
            table.append('\n').append(String.join(",", cells));
        }
        return table.append('\n').toString();
    }

    private static void saveVariations(final Path data, final Path scenario, final String csv,
                                       final String table) {
        try {
            Files.createDirectories(data.getParent());
            Files.writeString(data, table, StandardCharsets.UTF_8);
            setVariations(scenario, csv);
        } catch (IOException e) {
            throw new ToolException(e, ToolMessage.CANNOT_ADD_VARIATIONS, e.getMessage());
        }
    }

    /** Edits the text in place so the author's layout survives. */
    private static void setVariations(final Path scenario, final String csv) throws IOException {
        String xml = Files.readString(scenario, StandardCharsets.UTF_8);
        Matcher old = VARIATIONS.matcher(xml);
        String out;
        if (old.find()) {
            out = old.replaceFirst(Matcher.quoteReplacement(old.group(1) + csv + old.group(2)));
        } else {
            out = addVariations(xml, csv);
        }
        Files.writeString(scenario, out, StandardCharsets.UTF_8);
    }

    private static String addVariations(final String xml, final String csv) {
        Matcher empty = SETTINGS_EMPTY.matcher(xml);
        if (empty.find()) {
            String attrs = empty.group(2) == null ? "" : empty.group(2);
            return empty.replaceFirst(Matcher.quoteReplacement(
                    "<" + empty.group(1) + "settings" + attrs + ">" + variations(empty.group(1), csv, "\n    </"
                    + empty.group(1) + "settings>")));
        }
        Matcher open = SETTINGS_OPEN.matcher(xml);
        if (!open.find()) {
            throw new ToolException(ToolMessage.NO_SETTINGS);
        }
        return xml.substring(0, open.end()) + variations(open.group(1), csv, "") + xml.substring(open.end());
    }

    private static String variations(final String prefix, final String csv, final String close) {
        return "\n        <" + prefix + "variations>" + csv + "</" + prefix + "variations>" + close;
    }

    private static Path scenarioFolder(final Path root, final String name) {
        if (!NAME.matcher(name).matches()) {
            throw new ToolException(ToolMessage.BAD_NAME);
        }
        return root.resolve("scenarios").resolve(name);
    }

    private static String plainName(final String file) {
        if (file.contains("/") || file.contains("\\") || file.startsWith(".")) {
            throw new ToolException(ToolMessage.PATH_IN_FILE_NAME, file);
        }
        return file;
    }

    private static Map<String, String> write(final Path folder, final Map<String, String> content) {
        Map<String, String> before = new LinkedHashMap<>();
        try {
            Files.createDirectories(folder);
            for (var e : content.entrySet()) {
                Path f = folder.resolve(e.getKey());
                before.put(e.getKey(), Files.exists(f) ? Files.readString(f) : null);
                Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new ToolException(ToolMessage.CANNOT_WRITE, folder, ex.getMessage());
        }
        return before;
    }

    private static void restore(final Path folder, final Map<String, String> before) {
        before.forEach((name, content) -> restoreFile(folder.resolve(name), content));
        try {
            try (var left = Files.list(folder)) {
                if (left.findAny().isEmpty()) {
                    Files.delete(folder);
                }
            }
        } catch (IOException ignored) {
            // best effort, the tool error tells the caller what failed
        }
    }

    private static void restoreFile(final Path file, final String content) {
        try {
            if (content == null) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // the tool error tells the caller what failed
        }
    }

    private static String quote(final String cell) {
        return cell.matches("[^,\"\\r\\n]*") ? cell : "\"" + cell.replace("\"", "\"\"") + "\"";
    }

    private static String join(final List<EngineSchemas.Issue> issues) {
        return String.join("\n", issues.stream().map(EngineSchemas.Issue::toString).toList());
    }
}
