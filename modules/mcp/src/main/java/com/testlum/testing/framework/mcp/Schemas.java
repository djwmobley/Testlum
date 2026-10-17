package com.testlum.testing.framework.mcp;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;

/**
 * The engine's XSDs are the syntax truth. They come from a directory (TESTLUM_XSD_DIR) or are
 * extracted from the engine jar (TESTLUM_ENGINE_JAR) into a temp folder removed on exit.
 */
public final class Schemas {

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String SCHEMA_DIR = "schema/";
    private static final String SCENARIO_XSD = "scenario.xsd";
    private static final Pattern NS_BRACES = Pattern.compile("\\{http://www\\.testlum\\.com/[^}]*}");
    private static final int MAX_ISSUES = 20;

    private final String xsdDirEnv;
    private final String engineJarEnv;
    private final Map<String, Schema> compiled = new HashMap<>();
    private Path dir;
    private SchemaDescriber describer;

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
        if (dir == null) {
            dir = locate();
        }
        return dir;
    }

    private Path locate() {
        if (xsdDirEnv != null && !xsdDirEnv.isBlank()) {
            Path p = Path.of(xsdDirEnv);
            if (!Files.isRegularFile(p.resolve(SCENARIO_XSD))) {
                throw new Tool.ToolError(ToolMessage.XSD_DIR_WITHOUT_SCENARIO, p);
            }
            return p;
        }
        if (engineJarEnv != null && !engineJarEnv.isBlank()) {
            return extract(Path.of(engineJarEnv));
        }
        throw new Tool.ToolError(ToolMessage.NO_SCHEMAS);
    }

    private static Path extract(final Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new Tool.ToolError(ToolMessage.JAR_NOT_FOUND, jar);
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Path tmp = Files.createTempDirectory("testlum-mcp-xsd");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(tmp)));
            return unpack(zip, tmp, jar);
        } catch (IOException e) {
            throw new Tool.ToolError(ToolMessage.CANNOT_READ_JAR, e.getMessage());
        }
    }

    private static Path unpack(final ZipFile zip, final Path tmp, final Path jar) throws IOException {
        for (ZipEntry e : Collections.list(zip.entries())) {
            String n = e.getName();
            if (n.startsWith(SCHEMA_DIR) && n.endsWith(".xsd") && n.indexOf('/', SCHEMA_DIR.length()) < 0) {
                try (InputStream is = zip.getInputStream(e)) {
                    Files.copy(is, tmp.resolve(n.substring(SCHEMA_DIR.length())), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!Files.isRegularFile(tmp.resolve(SCENARIO_XSD))) {
            throw new Tool.ToolError(ToolMessage.NO_SCENARIO_XSD_IN_JAR, jar);
        }
        return tmp;
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
                throw new Tool.ToolError(ToolMessage.CANNOT_COMPILE_SCHEMA, k, e.getMessage());
            }
        });
    }

    /** Validates a file against one of the engine schemas; returns findings, empty when valid. */
    public List<Issue> validate(final String xsd, final Path xml, final String shownName) {
        List<Issue> issues = new ArrayList<>();
        try {
            var v = schema(xsd).newValidator();
            v.setErrorHandler(new Collector(issues, shownName));
            v.validate(new StreamSource(xml.toFile()));
        } catch (SAXException | IOException e) {
            if (issues.isEmpty()) {
                issues.add(new Issue(shownName, 0, 0, "error", String.valueOf(e.getMessage())));
            }
        }
        return issues;
    }

    /** The xmlns the root element of the given kind must carry. */
    public String namespaceOf(final String kind) {
        return describer().namespaceOf(kind);
    }

    /** Describes the element at a slash-separated path under the root of the given kind. */
    public String describe(final String kind, final String path) {
        return describer().describe(kind, path);
    }

    private synchronized SchemaDescriber describer() {
        if (describer == null) {
            describer = new SchemaDescriber(dir());
        }
        return describer;
    }

    /** Gathers parser findings, up to a cap, with the schema namespaces stripped from the text. */
    @RequiredArgsConstructor
    private static final class Collector implements ErrorHandler {
        private final List<Issue> issues;
        private final String shownName;

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
    }
}
