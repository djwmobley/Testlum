package com.testlum.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Writes a scenario with its data files, and turns a table of values into engine variations. */
final class Writer {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CSV_NAME = Pattern.compile("[a-zA-Z\\d_-]+\\.csv");

    private final Schemas schemas;
    private final Checker checker;

    Writer(final Schemas schemas, final Checker checker) {
        this.schemas = schemas;
        this.checker = checker;
    }

    String writeTest(final String dir, final String name, final String xml, final JsonNode files,
                     final boolean overwrite) {
        Path root = Path.of(dir).toAbsolutePath().normalize();
        Path folder = scenarioFolder(root, name);
        if (Files.exists(folder.resolve("scenario.xml")) && !overwrite) {
            throw new Tool.ToolError("Scenario '" + name + "' already exists. Pass overwrite=true to replace it.");
        }
        Map<String, String> content = new LinkedHashMap<>();
        content.put("scenario.xml", xml);
        if (files != null && files.isObject()) {
            files.fields().forEachRemaining(e -> content.put(plainName(e.getKey()), e.getValue().asText()));
        }
        Path scratch = null;
        try {
            scratch = Files.createTempFile("testlum-mcp-", ".xml");
            Files.writeString(scratch, xml, StandardCharsets.UTF_8);
            List<Schemas.Issue> bad = schemas.validate("scenario.xsd", scratch, "scenario.xml");
            if (!bad.isEmpty()) {
                throw new Tool.ToolError("Not written, the scenario does not pass the schema:\n" + join(bad));
            }
        } catch (IOException e) {
            throw new Tool.ToolError("Cannot stage the scenario: " + e.getMessage());
        } finally {
            if (scratch != null) {
                scratch.toFile().delete();
            }
        }
        Map<String, String> before = write(folder, content);
        String report = checker.check(dir, name, "global-config.xml", false, "");
        if (report.startsWith("FAIL")) {
            restore(folder, before);
            throw new Tool.ToolError("Rolled back, the check failed:\n" + report);
        }
        return "Wrote scenarios/" + name + ": " + String.join(", ", content.keySet()) + "\n" + report;
    }

    String generateVariations(final String dir, final String name, final String csv, final JsonNode rows) {
        Path root = Path.of(dir).toAbsolutePath().normalize();
        Path scenario = scenarioFolder(root, name).resolve("scenario.xml");
        if (!Files.isRegularFile(scenario)) {
            throw new Tool.ToolError("No scenario '" + name + "' to add variations to. Write it first.");
        }
        if (!CSV_NAME.matcher(csv).matches()) {
            throw new Tool.ToolError("csv must look like name.csv (letters, digits, _ and - only)");
        }
        List<String> columns = new ArrayList<>();
        if (rows == null || !rows.isArray() || rows.isEmpty() || !rows.get(0).isObject()) {
            throw new Tool.ToolError("rows must be a non-empty array of objects, one per variation");
        }
        rows.get(0).fieldNames().forEachRemaining(columns::add);
        StringBuilder table = new StringBuilder(String.join(",", columns.stream().map(Writer::quote).toList()));
        for (JsonNode row : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : columns) {
                if (!row.has(c)) {
                    throw new Tool.ToolError("Every row needs the same columns. Missing '" + c + "' in " + row);
                }
                cells.add(quote(row.get(c).asText()));
            }
            table.append('\n').append(String.join(",", cells));
        }
        Path data = root.resolve("data").resolve(csv);
        String oldScenario;
        try {
            oldScenario = Files.readString(scenario);
            Files.createDirectories(data.getParent());
            Files.writeString(data, table.append('\n').toString(), StandardCharsets.UTF_8);
            setVariations(scenario, csv);
        } catch (Exception e) {
            throw new Tool.ToolError("Cannot add variations: " + e.getMessage());
        }
        String report = checker.check(dir, name, "global-config.xml", false, "");
        if (report.startsWith("FAIL")) {
            try {
                Files.writeString(scenario, oldScenario);
            } catch (IOException ignored) {
                // the report below says what is wrong either way
            }
            throw new Tool.ToolError("Rolled back, the check failed:\n" + report);
        }
        return "data/" + csv + ": " + rows.size() + " rows, columns " + columns + "\nscenarios/" + name
                + " now runs once per row. Use {{" + columns.get(0) + "}} etc. in the scenario and in file names.\n"
                + report;
    }

    private static void setVariations(final Path scenario, final String csv) throws Exception {
        Document d = Xml.parse(scenario);
        Element settings = Xml.child(d.getDocumentElement(), "settings");
        if (settings == null) {
            throw new Tool.ToolError("The scenario has no settings element");
        }
        Element old = Xml.child(settings, "variations");
        if (old != null) {
            settings.removeChild(old);
        }
        Element v = d.createElementNS(settings.getNamespaceURI(), "variations");
        v.setTextContent(csv);
        settings.insertBefore(v, settings.getFirstChild());
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.transform(new DOMSource(d), new StreamResult(scenario.toFile()));
    }

    private static Path scenarioFolder(final Path root, final String name) {
        if (!NAME.matcher(name).matches()) {
            throw new Tool.ToolError("name may hold letters, digits, _ and - only");
        }
        return root.resolve("scenarios").resolve(name);
    }

    private static String plainName(final String file) {
        if (file.contains("/") || file.contains("\\") || file.startsWith(".")) {
            throw new Tool.ToolError("File names go in the scenario folder, no paths: " + file);
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
            throw new Tool.ToolError("Cannot write " + folder + ": " + ex.getMessage());
        }
        return before;
    }

    private static void restore(final Path folder, final Map<String, String> before) {
        try {
            for (var e : before.entrySet()) {
                Path f = folder.resolve(e.getKey());
                if (e.getValue() == null) {
                    Files.deleteIfExists(f);
                } else {
                    Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
                }
            }
            try (var left = Files.list(folder)) {
                if (left.findAny().isEmpty()) {
                    Files.delete(folder);
                }
            }
        } catch (IOException ignored) {
            // best effort, the tool error tells the caller what failed
        }
    }

    private static String quote(final String cell) {
        return cell.matches("[^,\"\\r\\n]*") ? cell : "\"" + cell.replace("\"", "\"\"") + "\"";
    }

    private static String join(final List<Schemas.Issue> issues) {
        return String.join("\n", issues.stream().map(Schemas.Issue::toString).toList());
    }
}
