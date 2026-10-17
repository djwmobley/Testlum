package com.testlum.testing.framework.mcp;

import com.testlum.testing.framework.mcp.exception.ToolException;
import com.testlum.testing.framework.mcp.constant.ToolMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Reads the engine's XSDs as plain DOM and describes an element's attributes and children for lookup. */
final class SchemaDescriber {

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String CHOICE = "choice";
    private static final List<String> GROUPS = List.of("sequence", CHOICE, "all");

    /** Usage notes the XSD annotations are too short to carry, keyed by the path lookup prints. */
    private static final Map<String, String> NOTES = Map.of("scenario/assert/jsonPath",
            "usage: With none of exists, equals or matches the field must exist; all given checks must hold. "
                    + "A filter or wildcard path that matches nothing counts as not found, so exists=\"false\" on "
                    + "$[?(@.status!='done')] says no item has another status. Pair it with a count such as "
                    + "path=\"$.length()\" equals=\"3\", or an empty list passes without proving anything.");

    private final Map<String, Element> complexTypes = new HashMap<>();
    private final Map<String, Element> simpleTypes = new HashMap<>();
    private final Map<String, Element> topElements = new HashMap<>();
    private final Map<String, Element> groups = new HashMap<>();

    /** A child element or attribute found while walking a type. */
    private record Member(String name, String type, String detail) { }

    private static final class Walk {
        private final List<String> extendsChain = new ArrayList<>();
        private final List<Member> attributes = new ArrayList<>();
        private final List<Member> elements = new ArrayList<>();
    }

    SchemaDescriber(final Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".xsd")).toList()) {
                indexFile(p);
            }
        } catch (Exception e) {
            throw new ToolException(ToolMessage.CANNOT_INDEX_SCHEMAS, e.getMessage());
        }
    }

    private void indexFile(final Path p) throws Exception {
        Document d = parse(p);
        String ns = d.getDocumentElement().getAttribute("targetNamespace");
        for (Element e : children(d.getDocumentElement(), null)) {
            String key = ns + "|" + e.getAttribute("name");
            switch (e.getLocalName()) {
                case "complexType" -> complexTypes.put(key, e);
                case "simpleType" -> simpleTypes.put(key, e);
                case "group" -> groups.put(key, e);
                case "element" -> topElements.put(key, e);
                default -> { }
            }
        }
    }

    private static Document parse(final Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(p.toFile());
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
                case "complexContent", "simpleContent" -> children(c, null).forEach(ext -> walkExtension(ext, w));
                case "sequence", "choice", "all" -> walkGroup(c, w, CHOICE.equals(c.getLocalName()));
                case "group" -> walkNamedGroup(c, w);
                case "attribute" -> w.attributes.add(attribute(c));
                default -> { }
            }
        }
    }

    private void walkExtension(final Element ext, final Walk w) {
        Element baseType = named(complexTypes, ext, ext.getAttribute("base"));
        if (baseType != null) {
            w.extendsChain.add(strip(ext.getAttribute("base")));
            walkType(baseType, w);
        }
        walkType(ext, w);
    }

    private void walkGroup(final Element g, final Walk w, final boolean choice) {
        String groupOccurs = occurs(g);
        for (Element c : children(g, null)) {
            switch (c.getLocalName()) {
                case "element" -> w.elements.add(member(c, choice, groupOccurs));
                case "sequence", "choice", "all" -> walkGroup(c, w, CHOICE.equals(c.getLocalName()));
                case "group" -> walkNamedGroup(c, w);
                default -> { }
            }
        }
    }

    private Member member(final Element c, final boolean choice, final String groupOccurs) {
        String n = c.hasAttribute("ref") ? strip(c.getAttribute("ref")) : c.getAttribute("name");
        String kind = choice ? CHOICE + (groupOccurs.isEmpty() ? "" : ", repeatable " + groupOccurs) : occurs(c);
        return new Member(n, typeName(c), kind.isEmpty() ? "required" : kind);
    }

    private void walkNamedGroup(final Element ref, final Walk w) {
        Element def = named(groups, ref, ref.getAttribute("ref"));
        if (def != null) {
            children(def, null).stream().filter(c -> GROUPS.contains(c.getLocalName()))
                    .forEach(c -> walkGroup(c, w, CHOICE.equals(c.getLocalName())));
        }
    }

    private static String occurs(final Element e) {
        String min = e.hasAttribute("minOccurs") ? e.getAttribute("minOccurs") : "1";
        String max = e.hasAttribute("maxOccurs") ? e.getAttribute("maxOccurs") : "1";
        if ("1".equals(min) && "1".equals(max)) {
            return "";
        }
        return "0".equals(min) && "1".equals(max) ? "optional" : min + ".." + max;
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
        String base = children(st, "restriction").stream().map(r -> restriction(r, enums, patterns, other))
                .reduce("string", (first, last) -> last);
        String text = enums.isEmpty() ? base : "one of " + String.join("|", enums);
        text += patterns.isEmpty() ? "" : " matching " + String.join(" or ", patterns);
        return text + (other.isEmpty() ? "" : " (" + String.join(", ", other) + ")");
    }

    private String restriction(final Element r, final List<String> enums, final List<String> patterns,
                               final List<String> other) {
        children(r, null).forEach(f -> facet(f, enums, patterns, other));
        return describeSimpleName(r, r.getAttribute("base"));
    }

    private static void facet(final Element f, final List<String> enums, final List<String> patterns,
                              final List<String> other) {
        switch (f.getLocalName()) {
            case "enumeration" -> enums.add(f.getAttribute("value"));
            case "pattern" -> patterns.add(f.getAttribute("value"));
            default -> other.add(f.getLocalName() + " " + f.getAttribute("value"));
        }
    }

    /** Root element name for each kind of file the engine reads. */
    private static String rootFor(final String kind) {
        return switch (kind) {
            case "scenario" -> "scenario";
            case "global-config" -> "globalTestConfiguration";
            case "integration" -> "integrations";
            default -> throw new ToolException(ToolMessage.BAD_KIND);
        };
    }

    /** The xmlns the root element of the given kind must carry. */
    String namespaceOf(final String kind) {
        String suffix = "|" + rootFor(kind);
        return topElements.keySet().stream().filter(k -> k.endsWith(suffix)).findFirst()
                .map(k -> k.substring(0, k.length() - suffix.length())).orElse("");
    }

    /** Describes the element at a slash-separated path under the root of the given kind. */
    String describe(final String kind, final String path) {
        String rootName = rootFor(kind);
        Element decl = topElements.entrySet().stream().filter(e -> e.getKey().endsWith("|" + rootName))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new ToolException(ToolMessage.NO_ROOT_ELEMENT, rootName));
        List<String> steps = steps(path, rootName);
        String shown = rootName;
        for (String step : steps) {
            decl = child(decl, step, shown);
            shown = shown + "/" + step;
        }
        String out = render(shown, decl);
        out = NOTES.containsKey(shown) ? out + "\n" + NOTES.get(shown) : out;
        return steps.isEmpty() ? out + "\nxmlns: " + namespaceOf(kind) : out;
    }

    private static List<String> steps(final String path, final String rootName) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<String> steps = new ArrayList<>(Arrays.stream(path.trim().split("/")).filter(s -> !s.isBlank())
                .map(String::trim).toList());
        if (!steps.isEmpty() && steps.get(0).equals(rootName)) {
            steps.remove(0);
        }
        return steps;
    }

    private Element child(final Element decl, final String step, final String shown) {
        Element ct = typeOf(decl);
        if (ct == null) {
            throw new ToolException(ToolMessage.NO_CHILD_ELEMENTS, shown);
        }
        Walk w = new Walk();
        walkType(ct, w);
        Element found = w.elements.stream().anyMatch(m -> m.name().equals(step)) ? findElementDecl(ct, step) : null;
        if (found == null) {
            throw new ToolException(ToolMessage.NO_SUCH_ELEMENT, step, shown,
                    String.join(", ", w.elements.stream().map(Member::name).toList()));
        }
        return found;
    }

    private Element findElementDecl(final Element node, final String name) {
        for (Element c : children(node, null)) {
            Element r = declIn(c, name);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private Element declIn(final Element c, final String name) {
        return switch (c.getLocalName()) {
            case "element" -> name.equals(c.getAttribute("name")) ? c : null;
            case "group" -> inNamed(groups, c, "ref", name);
            case "extension" -> {
                Element fromBase = inNamed(complexTypes, c, "base", name);
                yield fromBase != null ? fromBase : findElementDecl(c, name);
            }
            case "complexContent", "simpleContent", "sequence", "choice", "all" -> findElementDecl(c, name);
            default -> null;
        };
    }

    private Element inNamed(final Map<String, Element> defs, final Element user, final String attr,
                            final String name) {
        Element def = named(defs, user, user.getAttribute(attr));
        return def == null ? null : findElementDecl(def, name);
    }

    private static String documentation(final Element type) {
        Element ann = XmlSupport.child(type, "annotation");
        Element doc = ann == null ? null : XmlSupport.child(ann, "documentation");
        return doc == null ? "" : doc.getTextContent().trim().replaceAll("\\s+", " ");
    }

    private String render(final String shown, final Element decl) {
        Element ct = typeOf(decl);
        if (ct == null) {
            return shown + "\ntype: " + describeSimpleName(decl, decl.getAttribute("type"));
        }
        Walk w = new Walk();
        walkType(ct, w);
        StringBuilder sb = new StringBuilder(header(shown, decl, ct, w)).append('\n');
        members(sb, "attributes:\n", "", w.attributes);
        members(sb, "child elements:\n", "type ", w.elements);
        return (w.attributes.isEmpty() && w.elements.isEmpty() ? sb.append("(no attributes or child elements)")
                : sb).toString().stripTrailing();
    }

    private static String header(final String shown, final Element decl, final Element ct, final Walk w) {
        String notes = documentation(ct);
        String extended = w.extendsChain.isEmpty() ? ""
                : " (extends " + String.join(", ", w.extendsChain.stream().distinct().toList()) + ")";
        return shown + "\ntype: " + typeName(decl) + (notes.isEmpty() ? "" : "\nnotes: " + notes) + extended;
    }

    private static void members(final StringBuilder sb, final String title, final String typeLabel,
                                final List<Member> list) {
        if (list.isEmpty()) {
            return;
        }
        sb.append(title);
        for (Member m : list) {
            sb.append("  ").append(m.name()).append(" - ").append(m.detail()).append("; ").append(typeLabel)
                    .append(m.type()).append('\n');
        }
    }
}
