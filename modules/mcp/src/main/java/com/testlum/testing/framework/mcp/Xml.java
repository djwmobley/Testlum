package com.testlum.testing.framework.mcp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Small DOM helpers shared by the checker and the runner. */
final class Xml {

    private Xml() {
    }

    static String esc(final String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static Document parse(final Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = f.newDocumentBuilder();
        builder.setErrorHandler(null); // exceptions carry the message, so keep the parser quiet on stderr
        return builder.parse(file.toFile());
    }

    static List<Element> descendants(final Element root, final String local) {
        List<Element> out = new ArrayList<>();
        NodeList nl = root.getElementsByTagNameNS("*", local);
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    static Element child(final Element parent, final String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }
}
