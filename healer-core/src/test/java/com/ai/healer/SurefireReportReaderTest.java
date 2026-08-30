package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SurefireReportReaderTest {

    private static final String REPORT_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"com.framework.runners.TestRunner\" tests=\"3\" errors=\"1\" failures=\"1\" skipped=\"0\">\n"
            + "  <testcase name=\"Standard User can login successfully\" classname=\"User Login Flow\" time=\"32.8\">\n"
            + "    <error message=\"Timeout 30000ms exceeded.\" type=\"com.microsoft.playwright.TimeoutError\">"
            + "<![CDATA[stack trace]]></error>\n"
            + "  </testcase>\n"
            + "  <testcase name=\"Some assertion style failure\" classname=\"Other Flow\" time=\"1.0\">\n"
            + "    <failure message=\"expected true but was false\" type=\"AssertionError\">"
            + "<![CDATA[stack trace]]></failure>\n"
            + "  </testcase>\n"
            + "  <testcase name=\"A passing scenario\" classname=\"Other Flow\" time=\"1.0\"/>\n"
            + "</testsuite>\n";

    @Test
    void extractsFailuresAndErrorsButIgnoresPassedTestcases(@TempDir Path tempDir) throws Exception {
        Path reportsDir = Files.createDirectory(tempDir.resolve("surefire-reports"));
        Path snapshotsDir = Files.createDirectory(tempDir.resolve("dom-snapshots"));
        Files.writeString(reportsDir.resolve("TEST-com.framework.runners.TestRunner.xml"), REPORT_XML);
        Files.writeString(snapshotsDir.resolve("Standard_User_can_login_successfully-dom.json"), "[]");

        SurefireReportReader reader = new SurefireReportReader(reportsDir, snapshotsDir);
        List<TestFailure> failures = reader.readFailures();

        assertEquals(2, failures.size());

        TestFailure timeoutFailure = failures.stream()
                .filter(f -> f.testName.equals("Standard User can login successfully"))
                .findFirst().orElseThrow();
        assertEquals("User Login Flow", timeoutFailure.className);
        assertEquals("com.microsoft.playwright.TimeoutError", timeoutFailure.failureType);
        assertEquals("Timeout 30000ms exceeded.", timeoutFailure.failureMessage);
        assertEquals("stack trace", timeoutFailure.stackTrace);
        assertTrue(timeoutFailure.domSnapshotFound);
        assertEquals(
                snapshotsDir.resolve("Standard_User_can_login_successfully-dom.json"),
                timeoutFailure.domSnapshotPath);

        TestFailure assertionFailure = failures.stream()
                .filter(f -> f.testName.equals("Some assertion style failure"))
                .findFirst().orElseThrow();
        assertEquals("AssertionError", assertionFailure.failureType);
        assertFalse(assertionFailure.domSnapshotFound);
    }

    @Test
    void returnsEmptyListWhenReportsDirectoryDoesNotExist(@TempDir Path tempDir) throws Exception {
        SurefireReportReader reader = new SurefireReportReader(
                tempDir.resolve("does-not-exist"), tempDir.resolve("dom-snapshots"));

        assertEquals(0, reader.readFailures().size());
    }
}
