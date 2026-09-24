package com.ai.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

public class AlreadyAppliedFindingFilterTest {

    private static final String INVENTORY_PAGE = "playwright-tests/src/main/java/com/framework/pages/saucedemo/InventoryPage.java";
    private static final String LOGIN_PAGE = "playwright-tests/src/main/java/com/framework/pages/saucedemo/LoginPage.java";

    private static final String DIFF =
            "diff --git a/" + INVENTORY_PAGE + " b/" + INVENTORY_PAGE + "\n"
            + "--- a/" + INVENTORY_PAGE + "\n"
            + "+++ b/" + INVENTORY_PAGE + "\n"
            + "@@ -10,1 +1,1 @@\n"
            + "-    private final String addBackpackToCartButton = \"#add-to-cart-sauce-labs-backpack\";\n"
            + "+    private final String addBackpackToCartButton = \"[data-test='add-to-cart-sauce-labs-backpack']\";\n"
            + "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
            + "--- a/" + LOGIN_PAGE + "\n"
            + "+++ b/" + LOGIN_PAGE + "\n"
            + "@@ -5,2 +1,2 @@\n"
            + "-    private final String usernameInput = \"#user-name\";\n"
            + "+    private final String usernameInput = \"[data-test='username']\";\n"
            + "+    private final String helpLink = \"https://saucelabs.com/a\";\n";

    @Test
    void dropsBothRealAlreadyAppliedCases() {
        ReviewFinding backpack = failed(INVENTORY_PAGE,
                "private final String addBackpackToCartButton = \"[data-test='add-to-cart-sauce-labs-backpack']\";");
        ReviewFinding username = failed(LOGIN_PAGE,
                "private final String usernameInput = \"[data-test='username']\"; // VERIFY: Confirm that 'username' is the correct data-test value in the application");

        assertTrue(AlreadyAppliedFindingFilter.filter(List.of(backpack, username), DIFF).isEmpty());
    }

    @Test
    void keepsFixThatDiffersFromEveryAddedLine() {
        ReviewFinding finding = failed(LOGIN_PAGE, "private final String usernameInput = \"[data-test='user-name']\";");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), DIFF));
    }

    @Test
    void keepsFixThatOnlyMatchesARemovedLine() {
        ReviewFinding finding = failed(LOGIN_PAGE, "private final String usernameInput = \"#user-name\";");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), DIFF));
    }

    @Test
    void keepsFixWhoseTextMatchesAnAddedLineInADifferentFile() {
        ReviewFinding finding = failed(INVENTORY_PAGE, "private final String usernameInput = \"[data-test='username']\";");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), DIFF));
    }

    @Test
    void doesNotTreatDoubleSlashInsideStringLiteralAsComment() {
        // If "//" inside the literal were stripped, both of these would collapse to `... = "https:`.
        ReviewFinding differentUrl = failed(LOGIN_PAGE, "private final String helpLink = \"https://saucelabs.com/b\";");
        ReviewFinding sameUrlWithComment = failed(LOGIN_PAGE,
                "private final String helpLink = \"https://saucelabs.com/a\"; // docs link");

        assertEquals(List.of(differentUrl),
                AlreadyAppliedFindingFilter.filter(List.of(differentUrl, sameUrlWithComment), DIFF));
        assertEquals("x = \"https://a.com\";", AlreadyAppliedFindingFilter.normalise("  x   =  \"https://a.com\";  "));
    }

    @Test
    void neverDropsPassedFindingsEvenWhenFixMatches() {
        ReviewFinding passed = failed(LOGIN_PAGE, "private final String usernameInput = \"[data-test='username']\";");
        passed.status = "PASSED";

        assertEquals(List.of(passed), AlreadyAppliedFindingFilter.filter(List.of(passed), DIFF));
    }

    @Test
    void keepsMultiLineFixEvenWhenEachLineWasAdded() {
        ReviewFinding finding = failed(LOGIN_PAGE, "private final String usernameInput = \"[data-test='username']\";\n"
                + "private final String helpLink = \"https://saucelabs.com/a\";");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), DIFF));
    }

    @Test
    void keepsEmptyFixEvenWhenDiffAddsABlankLine() {
        // Without the empty-fix guard, "" would match a blank "+" line and silently drop a real finding.
        String diffWithBlankAddedLine = DIFF + "+\n";
        ReviewFinding finding = failed(LOGIN_PAGE, "   ");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), diffWithBlankAddedLine));
    }

    @Test
    void matchesFindingReportedWithShorterPathButNotAPartialFileName() {
        ReviewFinding basename = failed("LoginPage.java", "private final String usernameInput = \"[data-test='username']\";");
        ReviewFinding partialName = failed("Page.java", "private final String usernameInput = \"[data-test='username']\";");

        assertEquals(List.of(partialName), AlreadyAppliedFindingFilter.filter(List.of(basename, partialName), DIFF));
    }

    @Test
    void keepsEverythingWhenDiffIsNullOrEmpty() {
        ReviewFinding finding = failed(LOGIN_PAGE, "private final String usernameInput = \"[data-test='username']\";");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), null));
        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), ""));
    }

    @Test
    void handlesNewFilesAndIgnoresDeletedFiles() {
        String newFile = "playwright-tests/src/main/java/com/framework/pages/saucedemo/NewPage.java";
        String deletedFile = "playwright-tests/src/main/java/com/framework/pages/saucedemo/OldPage.java";
        String diff = "diff --git a/" + newFile + " b/" + newFile + "\n"
                + "new file mode 100644\n"
                + "--- /dev/null\n"
                + "+++ b/" + newFile + "\n"
                + "@@ -0,0 +1,1 @@\n"
                + "+    private final String title = \".title\";\n"
                + "diff --git a/" + deletedFile + " b/" + deletedFile + "\n"
                + "deleted file mode 100644\n"
                + "--- a/" + deletedFile + "\n"
                + "+++ /dev/null\n"
                + "@@ -1,1 +0,0 @@\n"
                + "-    private final String title = \".title\";\n";
        ReviewFinding onNewFile = failed(newFile, "private final String title = \".title\";");
        ReviewFinding onDeletedFile = failed(deletedFile, "private final String title = \".title\";");

        assertEquals(List.of(onDeletedFile),
                AlreadyAppliedFindingFilter.filter(List.of(onNewFile, onDeletedFile), diff));
    }

    @Test
    void logsOneSuppressedLinePerDroppedFindingNamingFileAndFix() {
        Logger logger = Logger.getLogger(AlreadyAppliedFindingFilter.class.getName());
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            String fix = "private final String usernameInput = \"[data-test='username']\";";
            AlreadyAppliedFindingFilter.filter(List.of(failed(LOGIN_PAGE, fix),
                    failed(LOGIN_PAGE, "something else;")), DIFF);

            assertEquals(1, messages.size());
            assertTrue(messages.get(0).startsWith("INFO [SUPPRESSED_ALREADY_APPLIED]"));
            assertTrue(messages.get(0).contains(LOGIN_PAGE));
            assertTrue(messages.get(0).contains(fix));
        } finally {
            logger.removeHandler(capture);
        }
    }

    // Two methods in one file: one adds the proper wait, the other adds a new Thread.sleep.
    private static final String TWO_METHOD_DIFF =
            "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
            + "--- a/" + LOGIN_PAGE + "\n"
            + "+++ b/" + LOGIN_PAGE + "\n"
            + "@@ -20,4 +20,5 @@ public class LoginPage extends BasePage {\n"
            + "     public void submit() {\n"
            + "         type(passwordInput, password);\n"
            + "+        waitForVisible(loginButton);\n"
            + "         click(loginButton);\n"
            + "     }\n"
            + "@@ -38,4 +39,5 @@ public class LoginPage extends BasePage {\n"
            + "     public void logout() {\n"
            + "         click(menuButton);\n"
            + "+        Thread.sleep(2000);\n"
            + "         click(logoutLink);\n"
            + "     }\n";

    @Test
    void keepsGenuineThreadSleepFindingWhoseFixMatchesALineAddedInAnotherMethod() {
        assertEquals(Map.of("waitForVisible(loginButton);", List.of(22), "Thread.sleep(2000);", List.of(41)),
                AlreadyAppliedFindingFilter.addedLinesByFile(TWO_METHOD_DIFF).get(LOGIN_PAGE));
        ReviewFinding threadSleep = failed(LOGIN_PAGE, 41, "waitForVisible(loginButton);");
        threadSleep.category = "Hardcoded Configurations";

        assertEquals(List.of(threadSleep), AlreadyAppliedFindingFilter.filter(List.of(threadSleep), TWO_METHOD_DIFF));
    }

    @Test
    void keepsCorrectFixWhenFindingLineIsFarFromTheMatchingAddedLine() {
        ReviewFinding finding = failed(LOGIN_PAGE, 62, "waitForVisible(loginButton);");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), TWO_METHOD_DIFF));
    }

    @Test
    void dropsWithinTwoLinesEitherSideButKeepsAtThree() {
        // The matching "+" line (waitForVisible) is at new-file line 22.
        for (int line : new int[] {20, 21, 22, 23, 24}) {
            assertTrue(AlreadyAppliedFindingFilter.filter(
                    List.of(failed(LOGIN_PAGE, line, "waitForVisible(loginButton);")), TWO_METHOD_DIFF).isEmpty(),
                    "line " + line + " should be dropped");
        }
        for (int line : new int[] {19, 25}) {
            ReviewFinding finding = failed(LOGIN_PAGE, line, "waitForVisible(loginButton);");
            assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), TWO_METHOD_DIFF),
                    "line " + line + " should be kept");
        }
    }

    @Test
    void keepsFindingWithNoUsableLineNumber() {
        ReviewFinding zero = failed(LOGIN_PAGE, 0, "waitForVisible(loginButton);");
        ReviewFinding negative = failed(LOGIN_PAGE, -1, "waitForVisible(loginButton);");

        assertEquals(List.of(zero, negative),
                AlreadyAppliedFindingFilter.filter(List.of(zero, negative), TWO_METHOD_DIFF));
    }

    @Test
    void carriesLineNumbersAcrossHunksAndRemovedLines() {
        String diff = "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
                + "--- a/" + LOGIN_PAGE + "\n"
                + "+++ b/" + LOGIN_PAGE + "\n"
                + "@@ -3,6 +3,5 @@\n"
                + " import a;\n"                 // 3
                + "-import b;\n"
                + "-import c;\n"
                + "+import d;\n"                 // 4
                + " import e;\n"                 // 5
                + "-import f;\n"
                + "+import g;\n"                 // 6
                + " import h;\n"                 // 7
                + "@@ -30,3 +29,4 @@ class X {\n"
                + "     int x;\n"               // 29
                + "+    int y;\n"               // 30
                + "-    int z;\n"
                + "+    int w;\n"               // 31
                + "+    int v;\n"               // 32
                + "\\ No newline at end of file\n"
                + "@@ -50,1 +51,1 @@\n"
                + "-    int old;\n"
                + "+    int fresh;\n";           // 51

        assertEquals(Map.of("import d;", List.of(4), "import g;", List.of(6), "int y;", List.of(30),
                "int w;", List.of(31), "int v;", List.of(32), "int fresh;", List.of(51)),
                AlreadyAppliedFindingFilter.addedLinesByFile(diff).get(LOGIN_PAGE));
    }

    @Test
    void parsesHunkHeaderWithoutLineCounts() {
        // git omits ",b"/",d" when a hunk covers exactly one line.
        String diff = "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
                + "--- a/" + LOGIN_PAGE + "\n"
                + "+++ b/" + LOGIN_PAGE + "\n"
                + "@@ -7 +7 @@\n"
                + "-    int a = 1;\n"
                + "+    int a = 2;\n";

        assertEquals(Map.of("int a = 2;", List.of(7)), AlreadyAppliedFindingFilter.addedLinesByFile(diff).get(LOGIN_PAGE));
    }

    @Test
    void countsBlankContextLineWhoseLeadingSpaceWasStripped() {
        String diff = "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
                + "--- a/" + LOGIN_PAGE + "\n"
                + "+++ b/" + LOGIN_PAGE + "\n"
                + "@@ -10,3 +10,4 @@\n"
                + " int a;\n"                   // 10
                + "\n"                           // 11 - blank context line, leading space stripped
                + "+int b;\n"                   // 12
                + " int c;\n";                  // 13

        assertEquals(Map.of("int b;", List.of(12)), AlreadyAppliedFindingFilter.addedLinesByFile(diff).get(LOGIN_PAGE));
    }

    @Test
    void recordsNothingUnderAnUnparseableHunkHeader() {
        String diff = "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
                + "--- a/" + LOGIN_PAGE + "\n"
                + "+++ b/" + LOGIN_PAGE + "\n"
                + "@@ garbled @@\n"
                + "+int b;\n";
        ReviewFinding finding = failed(LOGIN_PAGE, 1, "int b;");

        assertEquals(List.of(finding), AlreadyAppliedFindingFilter.filter(List.of(finding), diff));
    }

    @Test
    void dropsWhenSameTextIsAddedInTwoPlacesAndOneIsNearTheFindingLine() {
        String diff = "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
                + "--- a/" + LOGIN_PAGE + "\n"
                + "+++ b/" + LOGIN_PAGE + "\n"
                + "@@ -5,0 +5,1 @@\n"
                + "+        waitForVisible(loginButton);\n"   // 5
                + "@@ -40,0 +41,1 @@\n"
                + "+        waitForVisible(loginButton);\n";  // 41
        ReviewFinding nearSecond = failed(LOGIN_PAGE, 42, "waitForVisible(loginButton);");

        assertTrue(AlreadyAppliedFindingFilter.filter(List.of(nearSecond), diff).isEmpty());
    }

    private static ReviewFinding failed(String file, int line, String suggestedFix) {
        ReviewFinding finding = failed(file, suggestedFix);
        finding.line = line;
        return finding;
    }

    private static ReviewFinding failed(String file, String suggestedFix) {
        ReviewFinding finding = new ReviewFinding();
        finding.category = "Locator Robustness";
        finding.status = "FAILED";
        finding.file = file;
        finding.line = 1;
        finding.problem = "problem";
        finding.suggestedFix = suggestedFix;
        return finding;
    }
}
