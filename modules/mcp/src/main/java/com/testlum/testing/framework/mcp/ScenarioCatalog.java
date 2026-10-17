package com.testlum.testing.framework.mcp;

import com.testlum.testing.framework.mcp.constant.ToolMessage;
import com.testlum.testing.framework.mcp.exception.ToolException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lists the scenarios in a tests folder and deletes one. */
@Component
final class ScenarioCatalog {

    static final Pattern NAME = Pattern.compile("[a-zA-Z\\d_-]+(/[a-zA-Z\\d_-]+)*");
    private static final List<String> VERBS = List.of("get", "post", "put", "patch", "delete", "head", "options");

    /** What one scenario.xml holds that matters for upkeep. */
    private record Info(String name, String description, List<String> tags, boolean active, List<String> csvs,
                        List<String> calls, List<String> includes, boolean readable) { }

    String list(final Path root) {
        List<Info> all = read(root);
        StringBuilder sb = new StringBuilder(all.size() + " scenarios\n");
        all.forEach(i -> describe(sb, i));
        return sb.toString().stripTrailing();
    }

    private static void describe(final StringBuilder sb, final Info i) {
        sb.append(i.name()).append(" | tags: ").append(String.join(",", i.tags()))
                .append(i.active() ? "" : " | INACTIVE")
                .append(i.csvs().isEmpty() ? "" : " | variations: " + String.join(",", i.csvs())).append('\n');
        if (!i.description().isEmpty()) {
            sb.append("  ").append(i.description()).append('\n');
        }
        i.calls().forEach(c -> sb.append("  ").append(c).append('\n'));
        i.includes().forEach(c -> sb.append("  include ").append(c).append('\n'));
    }

    /** Deletes the scenario and the scenarios under it, unless a scenario outside them includes one. */
    String delete(final Path root, final String name) {
        if (!NAME.matcher(name).matches()) {
            throw new ToolException(ToolMessage.BAD_SCENARIO_NAME);
        }
        List<Info> all = read(root);
        List<Info> doomed = all.stream().filter(i -> inside(i.name(), name)).toList();
        List<Info> staying = all.stream().filter(i -> !doomed.contains(i)).toList();
        requireNoReaders(name, staying, doomed);
        remove(root.resolve(Layout.SCENARIOS).resolve(name));
        return "Deleted scenarios/" + name + leftOver(root, doomed, staying);
    }

    /** Data files are never deleted; the ones nothing refers to any more are named so the user can decide. */
    private static String leftOver(final Path root, final List<Info> doomed, final List<Info> staying) {
        String unused = doomed.stream().flatMap(i -> i.csvs().stream()).distinct()
                .filter(csv -> csv.matches("[a-zA-Z\\d_-]+\\.csv")
                        && Files.isRegularFile(root.resolve(Layout.DATA).resolve(csv)))
                .filter(csv -> staying.stream().noneMatch(i -> i.csvs().contains(csv)))
                .map(csv -> "data/" + csv).collect(Collectors.joining(", "));
        return unused.isEmpty() ? "" : ". No scenario uses " + unused + " now; it is left in place.";
    }

    /** The scenarios other than the named one that refer to a data csv. */
    List<String> usersOf(final Path root, final String csv, final String except) {
        if (!Files.isDirectory(root.resolve(Layout.SCENARIOS))) {
            return List.of();
        }
        return read(root).stream().filter(i -> !i.name().equals(except) && i.csvs().contains(csv))
                .map(Info::name).toList();
    }

    private static boolean inside(final String candidate, final String name) {
        return candidate.equals(name) || candidate.startsWith(name + "/");
    }

    private static void requireNoReaders(final String name, final List<Info> staying, final List<Info> doomed) {
        if (doomed.stream().noneMatch(i -> i.name().equals(name))) {
            throw new ToolException(ToolMessage.NO_SUCH_SCENARIO, name);
        }
        List<String> unreadable = staying.stream().filter(i -> !i.readable()).map(Info::name).toList();
        if (!unreadable.isEmpty()) {
            throw new ToolException(ToolMessage.CANNOT_VERIFY_INCLUDES, name, String.join(", ", unreadable));
        }
        staying.forEach(reader -> doomed.forEach(gone -> requireNotIncluded(reader, gone)));
    }

    private static void requireNotIncluded(final Info reader, final Info gone) {
        if (reader.includes().stream().anyMatch(inc -> includes(inc, gone.name()))) {
            throw new ToolException(ToolMessage.STILL_INCLUDED, reader.name(), gone.name());
        }
    }

    /** The engine resolves an include as a path, so group/./child names group/child. */
    private static String normal(final String include) {
        return Path.of(include.replace('\\', '/')).normalize().toString().replace('\\', '/');
    }

    /** An include names a scenario by its path under scenarios/; one with {{variables}} may name any. */
    private static boolean includes(final String include, final String name) {
        if (!include.contains("{{")) {
            return normal(include).equals(name);
        }
        return name.matches(Arrays.stream(normal(include).split("\\{\\{[^}]*}}", -1)).map(Pattern::quote)
                .collect(Collectors.joining(".*")));
    }

    private static void remove(final Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new ToolException(e, ToolMessage.CANNOT_DELETE, e.getMessage());
        }
    }

    private static List<Info> read(final Path root) {
        List<Info> out = new ArrayList<>();
        Layout.scenarioFiles(root).forEach((name, file) -> out.add(info(name, file)));
        return out;
    }

    private static Info info(final String name, final Path file) {
        try {
            Element top = XmlSupport.parse(file).getDocumentElement();
            Element settings = XmlSupport.child(top, "settings");
            boolean active = settings == null || !"false".equals(settings.getAttribute("active"));
            return new Info(name, text(XmlSupport.child(top, "overview"), "description"),
                    tags(text(settings, "tags")), active, csvs(top, settings), calls(top),
                    includeTargets(top), true);
        } catch (Exception e) {
            return new Info(name, "unreadable: " + e.getMessage(), List.of(), true, List.of(),
                    List.of(), List.of(), false);
        }
    }

    /** The csv in settings plus any repeat that names its own, so no data file looks unused while one reads it. */
    private static List<String> csvs(final Element top, final Element settings) {
        Stream<String> repeats = XmlSupport.descendants(top, "repeat").stream().map(e -> e.getAttribute("variations"));
        return Stream.concat(Stream.of(text(settings, "variations")), repeats).filter(c -> !c.isBlank()).distinct()
                .toList();
    }

    private static List<String> tags(final String text) {
        return Arrays.stream(text.split(",")).filter(t -> !t.isBlank()).map(String::trim).toList();
    }

    private static List<String> calls(final Element top) {
        List<String> out = new ArrayList<>();
        for (Element http : XmlSupport.descendants(top, "http")) {
            for (Node n = http.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && VERBS.contains(e.getLocalName())) {
                    out.add(e.getLocalName().toUpperCase() + " " + e.getAttribute("endpoint"));
                }
            }
        }
        return out;
    }

    private static List<String> includeTargets(final Element top) {
        return XmlSupport.descendants(top, "include").stream().map(e -> e.getAttribute("scenario")).toList();
    }

    private static String text(final Element parent, final String child) {
        Element e = parent == null ? null : XmlSupport.child(parent, child);
        return e == null ? "" : e.getTextContent().trim();
    }
}
