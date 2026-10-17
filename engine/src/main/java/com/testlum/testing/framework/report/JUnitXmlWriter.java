package com.testlum.testing.framework.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes scenario results as JUnit XML (one testsuite, one testcase per scenario)
 * so CI tools such as Azure DevOps PublishTestResults can read them.
 */
public final class JUnitXmlWriter {

    private static final double MS_PER_SECOND = 1000.0;

    private JUnitXmlWriter() {
    }

    public static void write(final Path file, final List<ScenarioResult> results,
                             final Map<String, String> skipped) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toXml(results, skipped), StandardCharsets.UTF_8);
    }

    public static String toXml(final List<ScenarioResult> results, final Map<String, String> skipped) {
        long failures = results.stream().filter(r -> !r.isSuccess()).count();
        long millis = results.stream().mapToLong(ScenarioResult::getExecutionTime).sum();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append(String.format("<testsuite name=\"testlum\" tests=\"%d\" failures=\"%d\" errors=\"0\" "
                        + "skipped=\"%d\" time=\"%s\">%n",
                results.size() + skipped.size(), failures, skipped.size(), seconds(millis)));
        results.forEach(r -> appendResult(xml, r));
        skipped.forEach((path, reason) -> xml.append(String.format(
                "  <testcase name=\"%s\" classname=\"%s\" time=\"0.000\"><skipped message=\"%s\"/></testcase>%n",
                esc(folderOf(path)), esc(path), esc(reason))));
        return xml.append("</testsuite>\n").toString();
    }

    private static void appendResult(final StringBuilder xml, final ScenarioResult r) {
        xml.append(String.format("  <testcase name=\"%s\" classname=\"%s\" time=\"%s\"",
                esc(folderOf(r.getPath())), esc(r.getPath()), seconds(r.getExecutionTime())));
        if (r.isSuccess()) {
            xml.append("/>\n");
            return;
        }
        String cause = Objects.toString(r.getCause(), "");
        xml.append(String.format("><failure message=\"%s\">%s</failure></testcase>%n",
                esc(cause.lines().findFirst().orElse("")), esc(cause)));
    }

    // The scenario folder is the stable identity; the file name is always scenario.xml.
    private static String folderOf(final String path) {
        Path parent = Paths.get(path).getParent();
        return parent == null ? path : parent.getFileName().toString();
    }

    private static String seconds(final long millis) {
        return String.format(java.util.Locale.ROOT, "%.3f", millis / MS_PER_SECOND);
    }

    // Stack traces can hold control characters that are illegal in XML 1.0.
    private static String esc(final String text) {
        return text.replaceAll("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]", "")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
