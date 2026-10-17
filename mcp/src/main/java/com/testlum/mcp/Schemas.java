package com.testlum.mcp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The engine's XSDs are the syntax truth. They come from a directory (TESTLUM_XSD_DIR) or are
 * extracted from the engine jar (TESTLUM_ENGINE_JAR) into a temp folder removed on exit.
 */
public final class Schemas {

    public static final String SCENARIO_NS = "http://www.testlum.com/testing/model/scenario";
    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Pattern NS_BRACES = Pattern.compile("\\{http://www\\.testlum\\.com/[^}]*}");
    private static final int MAX_ISSUES = 20;

    private final String xsdDirEnv;
    private final String engineJarEnv;
    private Path dir;
    private final Map<String, Schema> compiled = new HashMap<>();
    private final Map<String, Element> complexTypes = new HashMap<>();
    private final Map<String, Element> simpleTypes = new HashMap<>();
    private final Map<String, Element> topElements = new HashMap<>();
    private final Map<String, Element> groups = new HashMap<>();
    private boolean indexed;

    public Schemas(final String xsdDir, final String engineJar) {
        this.xsdDirEnv = xsdDir;
        this.engineJarEnv = engineJar;
    }

    /** One validation finding. */
    public record Issue(String file, int line, int column, String level, String message) {
        @Override
        public String toString() {
            return file + (line > 0 ? ":" + line + ":" + column : "") + " [" + level + "] " + message;
        }
    }

    public synchronized Path dir() {
        if (dir != null) {
            return dir;
        }
        if (xsdDirEnv != null && !xsdDirEnv.isBlank()) {
            Path p = Path.of(xsdDirEnv);
            if (!Files.isRegularFile(p.resolve("scenario.xsd"))) {
                throw new Tool.ToolError("TESTLUM_XSD_DIR has no scenario.xsd: " + p);
            }
            dir = p;
        } else if (engineJarEnv != null && !engineJarEnv.isBlank()) {
            dir = extract(Path.of(engineJarEnv));
        } else {
            throw new Tool.ToolError("No schemas available. Set TESTLUM_ENGINE_JAR to the engine jar "
                    + "or TESTLUM_XSD_DIR to a folder holding the engine's schema/*.xsd files.");
        }
        return dir;
    }

    private static Path extract(final Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new Tool.ToolError("Engine jar not found: " + jar);
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Path tmp = Files.createTempDirectory("testlum-mcp-xsd");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(tmp)));
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                String n = e.getName();
                if (n.startsWith("schema/") && n.endsWith(".xsd") && n.indexOf('/', 7) < 0) {
                    try (InputStream is = zip.getInputStream(e)) {
                        Files.copy(is, tmp.resolve(n.substring(7)), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (!Files.isRegularFile(tmp.resolve("scenario.xsd"))) {
                throw new Tool.ToolError("No schema/scenario.xsd inside " + jar);
            }
            return tmp;
        } catch (IOException e) {
            throw new Tool.ToolError("Cannot read engine jar: " + e.getMessage());
        }
    }

    private static void delete(final Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // best effort on exit
        }
    }

    private synchronized Schema schema(final String xsd) {
        return compiled.computeIfAbsent(xsd, k -> {
            try {
                SchemaFactory f = SchemaFactory.newInstance(XS);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
                return f.newSchema(new StreamSource(dir().resolve(k).toFile()));
            } catch (SAXException e) {
                throw new Tool.ToolError("Cannot compile " + k + ": " + e.getMessage());
            }
        });
    }

    /** Validates a file against one of the engine schemas; returns findings, empty when valid. */
    public List<Issue> validate(final String xsd, final Path xml, final String shownName) {
        List<Issue> issues = new ArrayList<>();
        ErrorHandler h = new ErrorHandler() {
            @Override
            public void warning(final SAXParseException e) {
                add("warning", e);
            }

            @Override
            public void error(final SAXParseException e) {
                add("error", e);
            }

            @Override
            public void fatalError(final SAXParseException e) {
                add("error", e);
            }

            private void add(final String level, final SAXParseException e) {
                if (issues.size() < MAX_ISSUES) {
                    issues.add(new Issue(shownName, e.getLineNumber(), e.getColumnNumber(), level,
                            NS_BRACES.matcher(String.valueOf(e.getMessage())).replaceAll("")));
                }
            }
        };
        try {
            var v = schema(xsd).newValidator();
            v.setErrorHandler(h);
            v.validate(new StreamSource(xml.toFile()));
        } catch (SAXException | IOException e) {
            if (issues.isEmpty()) {
                issues.add(new Issue(shownName, 0, 0, "error", String.valueOf(e.getMessage())));
            }
        }
        return issues;
    }

    // ---- structure lookup ----

    private synchronized void index() {
        if (indexed) {
            return;
        }
        try (Stream<Path> s = Files.list(dir())) {
            for (Path p : s.filter(f -> f.toString().endsWith(".xsd")).toList()) {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                Document d = f.newDocumentBuilder().parse(p.toFile());
                for (Element e : children(d.getDocumentElement(), null)) {
                    String n = e.getAttribute("name");
                    String ns = d.getDocumentElement().getAttribute("targetNamespace");
                    switch (e.getLocalName()) {
                        case "complexType" -> complexTypes.put(ns + "|" + n, e);
                        case "simpleType" -> simpleTypes.put(ns + "|" + n, e);
                        case "group" -> groups.put(ns + "|" + n, e);
                        case "element" -> topElements.put(ns + "|" + n, e);
                        default -> { }
                    }
                }
            }
        } catch (Exception e) {
            throw new Tool.ToolError("Cannot index schemas: " + e.getMessage());
        }
        indexed = true;
    }

    private static List<Element> children(final Element parent, final String local) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && XS.equals(e.getNamespaceURI())
                    && (local == null || local.equals(e.getLocalName()))) {
                out.add(e);
            }
        }
        return out;
    }

    /** Finds a named definition, resolving the prefix of qname in the scope of the element that uses it. */
    private static Element named(final Map<String, Element> defs, final Element user, final String qname) {
        int i = qname.indexOf(':');
        String ns = user.lookupNamespaceURI(i < 0 ? null : qname.substring(0, i));
        if (ns == null) {
            ns = user.getOwnerDocument().getDocumentElement().getAttribute("targetNamespace");
        }
        Element found = defs.get(ns + "|" + strip(qname));
        // a schema with no targetNamespace is included into the namespace of whoever includes it
        return found != null ? found : defs.get("|" + strip(qname));
    }

    private static String strip(final String qname) {
        int i = qname.indexOf(':');
        return i < 0 ? qname : qname.substring(i + 1);
    }

    /** A child element or attribute found while walking a type. */
    private record Member(String name, String type, String detail) { }

    private static final class Walk {
        final List<String> extendsChain = new ArrayList<>();
        final List<Member> attributes = new ArrayList<>();
        final List<Member> elements = new ArrayList<>();
    }

    private Element typeOf(final Element decl) {
        String t = decl.getAttribute("type");
        if (!t.isEmpty()) {
            return named(complexTypes, decl, t);
        }
        List<Element> inline = children(decl, "complexType");
        return inline.isEmpty() ? null : inline.get(0);
    }

    private void walkType(final Element ct, final Walk w) {
        for (Element c : children(ct, null)) {
            switch (c.getLocalName()) {
                case "complexContent", "simpleContent" -> {
                    for (Element ext : children(c, null)) {
                        String base = strip(ext.getAttribute("base"));
                        Element baseType = named(complexTypes, ext, ext.getAttribute("base"));
                        if (baseType != null) {
                            w.extendsChain.add(base);
                            walkType(baseType, w);
                        }
                        walkType(ext, w);
                    }
                }
                case "sequence", "choice", "all" -> walkGroup(c, w, c.getLocalName().equals("choice"), "");
                case "group" -> walkNamedGroup(c, w);
                case "attribute" -> w.attributes.add(attribute(c));
                default -> { }
            }
        }
    }

    private void walkGroup(final Element g, final Walk w, final boolean choice, final String outer) {
        String groupOccurs = occurs(g);
        for (Element c : children(g, null)) {
            switch (c.getLocalName()) {
                case "element" -> {
                    String n = c.hasAttribute("ref") ? strip(c.getAttribute("ref")) : c.getAttribute("name");
                    String occ = occurs(c);
                    String kind = choice ? "choice" + (groupOccurs.isEmpty() ? "" : ", repeatable " + groupOccurs) : occ;
                    w.elements.add(new Member(n, typeName(c), kind.isEmpty() ? "required" : kind));
                }
                case "sequence", "choice", "all" -> walkGroup(c, w, c.getLocalName().equals("choice"), outer);
                case "group" -> walkNamedGroup(c, w);
                default -> { }
            }
        }
    }

    private void walkNamedGroup(final Element ref, final Walk w) {
        Element def = named(groups, ref, ref.getAttribute("ref"));
        if (def != null) {
            for (Element c : children(def, null)) {
                if (List.of("sequence", "choice", "all").contains(c.getLocalName())) {
                    walkGroup(c, w, c.getLocalName().equals("choice"), "");
                }
            }
        }
    }

    private static String occurs(final Element e) {
        String min = e.hasAttribute("minOccurs") ? e.getAttribute("minOccurs") : "1";
        String max = e.hasAttribute("maxOccurs") ? e.getAttribute("maxOccurs") : "1";
        if (min.equals("1") && max.equals("1")) {
            return "";
        }
        return min.equals("0") && max.equals("1") ? "optional" : min + ".." + max;
    }

    private static String typeName(final Element decl) {
        String t = decl.getAttribute("type");
        return t.isEmpty() ? "(inline)" : strip(t);
    }

    private Member attribute(final Element a) {
        String use = a.getAttribute("use");
        String def = a.getAttribute("default");
        String detail = ("required".equals(use) ? "required" : "optional")
                + (def.isEmpty() ? "" : ", default " + def);
        String t = a.getAttribute("type");
        String type = t.isEmpty() ? describeSimple(children(a, "simpleType").stream().findFirst().orElse(null))
                : describeSimpleName(a, t);
        return new Member(a.getAttribute("name"), type, detail);
    }

    private String describeSimpleName(final Element user, final String qname) {
        Element st = named(simpleTypes, user, qname);
        return st == null ? strip(qname) : describeSimple(st);
    }

    private String describeSimple(final Element st) {
        if (st == null) {
            return "string";
        }
        List<String> enums = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        List<String> other = new ArrayList<>();
        String base = "string";
        for (Element r : children(st, "restriction")) {
            base = describeSimpleName(r, r.getAttribute("base"));
            for (Element f : children(r, null)) {
                switch (f.getLocalName()) {
                    case "enumeration" -> enums.add(f.getAttribute("value"));
                    case "pattern" -> patterns.add(f.getAttribute("value"));
                    default -> other.add(f.getLocalName() + " " + f.getAttribute("value"));
                }
            }
        }
        StringBuilder sb = new StringBuilder(enums.isEmpty() ? base : "one of " + String.join("|", enums));
        if (!patterns.isEmpty()) {
            sb.append(" matching ").append(String.join(" or ", patterns));
        }
        if (!other.isEmpty()) {
            sb.append(" (").append(String.join(", ", other)).append(')');
        }
        return sb.toString();
    }

    /** Root element name for each kind of file the engine reads. */
    public static String rootFor(final String kind) {
        return switch (kind) {
            case "scenario" -> "scenario";
            case "global-config" -> "globalTestConfiguration";
            case "integration" -> "integrations";
            default -> throw new Tool.ToolError("kind must be scenario, global-config or integration");
        };
    }

    /** Describes the element at a slash-separated path under the root of the given kind. */
    public String describe(final String kind, final String path) {
        index();
        String rootName = rootFor(kind);
        Element decl = topElements.entrySet().stream().filter(e -> e.getKey().endsWith("|" + rootName))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (decl == null) {
            throw new Tool.ToolError("Root element not found in schemas: " + rootName);
        }
        String shown = rootName;
        List<String> steps = new ArrayList<>();
        if (path != null && !path.isBlank()) {
            for (String s : path.trim().split("/")) {
                if (!s.isBlank()) {
                    steps.add(s.trim());
                }
            }
            if (!steps.isEmpty() && steps.get(0).equals(rootName)) {
                steps.remove(0);
            }
        }
        for (String step : steps) {
            Element ct = typeOf(decl);
            if (ct == null) {
                throw new Tool.ToolError(shown + " has no child elements");
            }
            Walk w = new Walk();
            walkType(ct, w);
            Member next = w.elements.stream().filter(m -> m.name().equals(step)).findFirst().orElse(null);
            if (next == null) {
                throw new Tool.ToolError("No element '" + step + "' under " + shown + ". Children: "
                        + String.join(", ", w.elements.stream().map(Member::name).toList()));
            }
            decl = findDecl(ct, step);
            shown = shown + "/" + step;
        }
        return render(shown, decl);
    }

    private Element findDecl(final Element ct, final String name) {
        Element inType = findElementDecl(ct, name);
        if (inType == null) {
            throw new Tool.ToolError("Element declaration not found: " + name);
        }
        return inType;
    }

    private Element findElementDecl(final Element node, final String name) {
        for (Element c : children(node, null)) {
            switch (c.getLocalName()) {
                case "element" -> {
                    if (name.equals(c.getAttribute("name"))) {
                        return c;
                    }
                }
                case "group" -> {
                    Element def = named(groups, c, c.getAttribute("ref"));
                    Element r = def == null ? null : findElementDecl(def, name);
                    if (r != null) {
                        return r;
                    }
                }
                case "complexContent", "simpleContent", "extension", "sequence", "choice", "all" -> {
                    if (c.getLocalName().equals("extension")) {
                        Element base = named(complexTypes, c, c.getAttribute("base"));
                        Element fromBase = base == null ? null : findElementDecl(base, name);
                        if (fromBase != null) {
                            return fromBase;
                        }
                    }
                    Element r = findElementDecl(c, name);
                    if (r != null) {
                        return r;
                    }
                }
                default -> { }
            }
        }
        return null;
    }

    private String render(final String shown, final Element decl) {
        StringBuilder sb = new StringBuilder(shown);
        Element ct = typeOf(decl);
        if (ct == null) {
            return sb.append("\ntype: ").append(describeSimpleName(decl, decl.getAttribute("type"))).toString();
        }
        Walk w = new Walk();
        walkType(ct, w);
        String t = typeName(decl);
        sb.append("\ntype: ").append(t);
        if (!w.extendsChain.isEmpty()) {
            sb.append(" (extends ").append(String.join(", ", w.extendsChain.stream().distinct().toList())).append(')');
        }
        sb.append('\n');
        if (!w.attributes.isEmpty()) {
            sb.append("attributes:\n");
            for (Member a : w.attributes) {
                sb.append("  ").append(a.name()).append(" - ").append(a.detail()).append("; ")
                        .append(a.type()).append('\n');
            }
        }
        if (!w.elements.isEmpty()) {
            sb.append("child elements:\n");
            for (Member e : w.elements) {
                sb.append("  ").append(e.name()).append(" - ").append(e.detail()).append("; type ")
                        .append(e.type()).append('\n');
            }
        }
        if (w.attributes.isEmpty() && w.elements.isEmpty()) {
            sb.append("(no attributes or child elements)\n");
        }
        return sb.toString().stripTrailing();
    }

    /** The commands a scenario may contain, for the overview. */
    public File dirFile() {
        return dir().toFile();
    }
}
