package com.ai.healer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Reads playwright-tests' Surefire XML reports and returns one TestFailure per failed testcase,
 * each carrying whether a matching DOM snapshot (captured by Hooks on scenario failure) was found.
 * Only reads and parses — no classification of *why* a test failed, no Ollama calls, no patching.
 * A Playwright locator timeout surfaces in Surefire as a JUnit &lt;error&gt; element rather than
 * &lt;failure&gt; (confirmed against a real broken-locator run), so both are treated as failures here.
 *
 * Also refuses to silently process nothing: a missing or empty reports directory, or one that's
 * stale relative to the invocation that triggered this read (see {@code healer.minReportTimestamp}
 * below), all log a specific, human-readable reason and return an empty list rather than let a
 * caller mistake "no report" or "an old report" for "the test suite just passed."
 */
public class SurefireReportReader {

    private static final Logger LOGGER = Logger.getLogger(SurefireReportReader.class.getName());

    // Set by run-tests-and-heal.sh / TestRunAndHeal (or any other caller) to the epoch-millis
    // timestamp captured right before it ran the test suite, so a report left over from an
    // earlier, unrelated run can't be silently processed as though it belongs to this invocation.
    // Optional: when unset, whatever report is currently on disk is trusted as-is (e.g. a direct
    // `mvn -pl ai-healer exec:java` invocation has no "current invocation" to compare against).
    // Package-private (not private) so TestRunAndHeal can set it without duplicating the literal.
    static final String MIN_REPORT_TIMESTAMP_PROPERTY = "healer.minReportTimestamp";

    private final Path surefireReportsDir;
    private final Path domSnapshotsDir;

    public SurefireReportReader() {
        Path repoRoot = RepoRoot.resolve(SurefireReportReader.class);
        this.surefireReportsDir = repoRoot.resolve("playwright-tests/target/surefire-reports");
        this.domSnapshotsDir = repoRoot.resolve("playwright-tests/target/dom-snapshots");
    }

    public SurefireReportReader(Path surefireReportsDir, Path domSnapshotsDir) {
        this.surefireReportsDir = surefireReportsDir;
        this.domSnapshotsDir = domSnapshotsDir;
    }

    public List<TestFailure> readFailures() throws IOException {
        List<TestFailure> failures = new ArrayList<>();
        if (Files.notExists(surefireReportsDir)) {
            LOGGER.info("No recent test failures found - run the test suite first (no report at "
                    + surefireReportsDir + ").");
            return failures;
        }

        List<Path> xmlFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(surefireReportsDir, "*.xml")) {
            stream.forEach(xmlFiles::add);
        }
        if (xmlFiles.isEmpty()) {
            LOGGER.info("No recent test failures found - run the test suite first (" + surefireReportsDir
                    + " exists but contains no reports).");
            return failures;
        }
        if (!isFreshEnough(xmlFiles)) {
            LOGGER.info("The Surefire reports in " + surefireReportsDir + " are from a previous test run, "
                    + "not this invocation - run the test suite first, then try again.");
            return failures;
        }

        for (Path xmlFile : xmlFiles) {
            failures.addAll(parseFile(xmlFile));
        }
        if (failures.isEmpty()) {
            LOGGER.info("Test suite passed - nothing to heal.");
        }
        return failures;
    }

    // Compares the newest report file's last-modified time against healer.minReportTimestamp (if
    // set) so a report left over from an earlier, unrelated test run isn't mistaken for this one.
    private static boolean isFreshEnough(List<Path> xmlFiles) {
        String minTimestampProperty = System.getProperty(MIN_REPORT_TIMESTAMP_PROPERTY);
        if (minTimestampProperty == null || minTimestampProperty.isBlank()) {
            return true;
        }
        long minTimestampMs;
        try {
            minTimestampMs = Long.parseLong(minTimestampProperty.trim());
        } catch (NumberFormatException e) {
            return true;
        }
        long newestMs = 0;
        for (Path xmlFile : xmlFiles) {
            try {
                newestMs = Math.max(newestMs, Files.getLastModifiedTime(xmlFile).toMillis());
            } catch (IOException e) {
                // Can't read this file's timestamp - don't let it block the check on its own.
            }
        }
        return newestMs >= minTimestampMs;
    }

    private List<TestFailure> parseFile(Path xmlFile) throws IOException {
        List<TestFailure> failures = new ArrayList<>();
        try {
            Document doc = newDocumentBuilder().parse(xmlFile.toFile());
            NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                Element testcase = (Element) testcases.item(i);
                Element failureNode = firstChildElement(testcase, "failure");
                if (failureNode == null) {
                    failureNode = firstChildElement(testcase, "error");
                }
                if (failureNode == null) {
                    continue;
                }

                TestFailure failure = new TestFailure();
                failure.testName = testcase.getAttribute("name");
                failure.className = testcase.getAttribute("classname");
                failure.failureType = failureNode.getAttribute("type");
                failure.failureMessage = failureNode.getAttribute("message");
                failure.stackTrace = failureNode.getTextContent();
                resolveDomSnapshot(failure);
                failures.add(failure);
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse Surefire report: " + xmlFile, e);
        }
        return failures;
    }

    private void resolveDomSnapshot(TestFailure failure) {
        String sanitized = ScenarioNameSanitizer.sanitize(failure.testName);
        Path snapshotPath = domSnapshotsDir.resolve(sanitized + "-dom.json");
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = Files.exists(snapshotPath);
    }

    private static Element firstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
