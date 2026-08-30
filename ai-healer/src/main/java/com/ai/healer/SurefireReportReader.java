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

/**
 * Reads playwright-tests' Surefire XML reports and returns one TestFailure per failed testcase,
 * each carrying whether a matching DOM snapshot (captured by Hooks on scenario failure) was found.
 * Only reads and parses — no classification of *why* a test failed, no Ollama calls, no patching.
 * A Playwright locator timeout surfaces in Surefire as a JUnit &lt;error&gt; element rather than
 * &lt;failure&gt; (confirmed against a real broken-locator run), so both are treated as failures here.
 */
public class SurefireReportReader {

    private final Path surefireReportsDir;
    private final Path domSnapshotsDir;

    public SurefireReportReader() {
        Path repoRoot = findRepoRoot();
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
            return failures;
        }

        try (DirectoryStream<Path> xmlFiles = Files.newDirectoryStream(surefireReportsDir, "*.xml")) {
            for (Path xmlFile : xmlFiles) {
                failures.addAll(parseFile(xmlFile));
            }
        }
        return failures;
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

    // Locates the repo root by walking up from wherever this class was loaded from until a
    // directory containing both sibling modules is found, so this works regardless of the JVM's
    // working directory (mirrors ai-reviewer's ModuleRoot, kept self-contained here since
    // ai-healer has no dependency on the ai-reviewer module).
    private static Path findRepoRoot() {
        try {
            Path codeSource = Path.of(
                    SurefireReportReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            while (candidate != null) {
                if (Files.isDirectory(candidate.resolve("playwright-tests"))
                        && Files.isDirectory(candidate.resolve("ai-reviewer"))) {
                    return candidate;
                }
                candidate = candidate.getParent();
            }
        } catch (Exception e) {
            // Fall through to the working-directory fallback below.
        }
        return Path.of("").toAbsolutePath();
    }
}
