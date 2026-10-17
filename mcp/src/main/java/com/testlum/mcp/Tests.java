package com.testlum.mcp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Lists the scenarios in a tests folder and deletes one. */
final class Tests {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z\\d_-]+");
    private static final List<String> VERBS = List.of("get", "post", "put", "patch", "delete", "head", "options");

    /** What one scenario.xml holds that matters for upkeep. */
    private record Info(String name, String description, List<String> tags, boolean active, String csv,
                        List<String> calls, List<String> includes) { }

    String list(final String dirArg) {
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        List<Info> all = read(root);
        StringBuilder sb = new StringBuilder(all.size() + " scenarios\n");
        for (Info i : all) {
            sb.append(i.name()).append(" | tags: ").append(String.join(",", i.tags()))
                    .append(i.active() ? "" : " | INACTIVE")
                    .append(i.csv().isEmpty() ? "" : " | variations: " + i.csv()).append('\n');
            if (!i.description().isEmpty()) {
                sb.append("  ").append(i.description()).append('\n');
            }
            i.calls().forEach(c -> sb.append("  ").append(c).append('\n'));
            i.includes().forEach(c -> sb.append("  include ").append(c).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    String delete(final String dirArg, final String name) {
        if (!NAME.matcher(name).matches()) {
            throw new Tool.ToolError("Scenario name may only hold letters, digits, - and _");
        }
        Path root = Path.of(dirArg).toAbsolutePath().normalize();
        List<Info> all = read(root);
        Info target = all.stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new Tool.ToolError("No such scenario: " + name));
        for (Info i : all) {
            if (i.includes().contains(name)) {
                throw new Tool.ToolError("Not deleted: scenario " + i.name() + " includes " + name);
            }
        }
        try {
            remove(root.resolve("scenarios").resolve(name));
            String csv = target.csv();
            boolean shared = all.stream().anyMatch(i -> !i.name().equals(name) && i.csv().equals(csv));
            boolean dropCsv = !csv.isEmpty() && !shared && NAME.matcher(csv.replaceAll("\\.csv$", "")).matches()
                    && Files.deleteIfExists(root.resolve("data").resolve(csv));
            return "Deleted scenarios/" + name + (dropCsv ? " and data/" + csv : "");
        } catch (IOException e) {
            throw new Tool.ToolError("Could not delete: " + e.getMessage());
        }
    }

    private static void remove(final Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static List<Info> read(final Path root) {
        Path dir = root.resolve("scenarios");
        if (!Files.isDirectory(dir)) {
            throw new Tool.ToolError("No scenarios folder under " + root);
        }
        List<Info> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                Path f = d.resolve("scenario.xml");
                if (Files.isRegularFile(f)) {
                    out.add(info(d.getFileName().toString(), f));
                }
            }
        } catch (IOException e) {
            throw new Tool.ToolError("Cannot read scenarios: " + e.getMessage());
        }
        return out;
    }

    private static Info info(final String name, final Path file) {
        try {
            Element top = Xml.parse(file).getDocumentElement();
            Element settings = Xml.child(top, "settings");
            String tags = text(settings, "tags");
            List<String> tagList = new ArrayList<>();
            for (String t : tags.split(",")) {
                if (!t.isBlank()) {
                    tagList.add(t.trim());
                }
            }
            boolean active = settings == null || !"false".equals(settings.getAttribute("active"));
            return new Info(name, text(Xml.child(top, "overview"), "description"), tagList, active,
                    text(settings, "variations"), calls(top), includes(top));
        } catch (Exception e) {
            return new Info(name, "unreadable: " + e.getMessage(), List.of(), true, "", List.of(), List.of());
        }
    }

    private static List<String> calls(final Element top) {
        List<String> out = new ArrayList<>();
        for (Element http : Xml.descendants(top, "http")) {
            for (Node n = http.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && VERBS.contains(e.getLocalName())) {
                    out.add(e.getLocalName().toUpperCase() + " " + e.getAttribute("endpoint"));
                }
            }
        }
        return out;
    }

    private static List<String> includes(final Element top) {
        return Xml.descendants(top, "include").stream().map(e -> e.getAttribute("scenario")).toList();
    }

    private static String text(final Element parent, final String child) {
        Element e = parent == null ? null : Xml.child(parent, child);
        return e == null ? "" : e.getTextContent().trim();
    }
}
