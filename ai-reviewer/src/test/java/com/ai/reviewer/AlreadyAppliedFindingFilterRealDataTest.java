package com.ai.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ai.reviewer.diff.DiffFetcher;
import com.ai.reviewer.ollama.FindingParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs AlreadyAppliedFindingFilter against real data: the diffs of PR #90 and PR #89 exactly as
 * `gh pr diff` returned them (src/test/resources/fixtures), and the findings the reviewer really
 * posted on those PRs, fed through the real FindingParser as Ollama's structured-output JSON.
 * The gate check below mirrors LocalCodeReviewer.main(): filter, keep FAILED, non-empty = exit 1.
 */
public class AlreadyAppliedFindingFilterRealDataTest {

    private static final String INVENTORY_PAGE = "playwright-tests/src/main/java/com/framework/pages/saucedemo/InventoryPage.java";
    private static final String LOGIN_PAGE = "playwright-tests/src/main/java/com/framework/pages/saucedemo/LoginPage.java";

    // Verbatim problem/suggestedFix text of the real review comment posted on PR #90.
    private static final JsonObject PR90_FINDING = finding("Locator Robustness", "FAILED", INVENTORY_PAGE, 13,
            "The locator for addBackpackToCartButton is missing an equals sign in the data-test attribute selector, "
                    + "making it invalid CSS syntax.",
            "private final String addBackpackToCartButton = \"[data-test='add-to-cart-sauce-labs-backpack']\";");

    // Verbatim problem/suggestedFix text of the real review comment posted on PR #89.
    private static final JsonObject PR89_FINDING = finding("Locator Robustness", "FAILED", LOGIN_PAGE, 11,
            "The locator for usernameInput was changed from an ID selector (#username) to a data-test attribute "
                    + "selector ([data-test='username']). While this is an improvement, the data-test attribute value "
                    + "should be verified against the actual application code to ensure it matches.",
            "private final String usernameInput = \"[data-test='username']\"; // VERIFY: Confirm that 'username' "
                    + "is the correct data-test value in the application");

    // A genuine violation added to a page object - not something the diff already contains.
    private static final String THREAD_SLEEP_DIFF =
            "diff --git a/" + LOGIN_PAGE + " b/" + LOGIN_PAGE + "\n"
            + "index b978a7b..c1d2e3f 100644\n"
            + "--- a/" + LOGIN_PAGE + "\n"
            + "+++ b/" + LOGIN_PAGE + "\n"
            + "@@ -20,6 +20,7 @@ public class LoginPage extends BasePage {\n"
            + "     public void login(String username, String password) {\n"
            + "         type(usernameInput, username);\n"
            + "         type(passwordInput, password);\n"
            + "+        Thread.sleep(2000);\n"
            + "         click(loginButton);\n"
            + "     }\n";

    private static final JsonObject THREAD_SLEEP_FINDING = finding("Hardcoded Configurations", "FAILED", LOGIN_PAGE, 23,
            "Hard-coded Thread.sleep(2000) introduces a fixed wait; use Playwright's auto-waiting instead.",
            "waitForVisible(loginButton);");

    @Test
    void realPr90AndPr89FindingsAreBothSuppressedAndGatePasses() throws IOException {
        String pr90 = fixture("pr90.diff");
        String pr89 = fixture("pr89.diff");

        List<ReviewFinding> pr90Kept = AlreadyAppliedFindingFilter.filter(
                FindingParser.parse(ollamaJson(PR90_FINDING)), DiffFetcher.filterDiff(pr90));
        List<ReviewFinding> pr89Kept = AlreadyAppliedFindingFilter.filter(
                FindingParser.parse(ollamaJson(PR89_FINDING)), DiffFetcher.filterDiff(pr89));

        assertTrue(pr90Kept.isEmpty());
        assertTrue(pr89Kept.isEmpty());
        assertFalse(gateFails(pr90Kept));
        assertFalse(gateFails(pr89Kept));
    }

    @Test
    void realPrFindingsAreSuppressedWhenBothDiffsAreReviewedTogether() throws IOException {
        // Two files in one diff: the parser must not bleed one file's "+" lines into the other.
        String combined = DiffFetcher.filterDiff(fixture("pr90.diff") + fixture("pr89.diff"));

        List<ReviewFinding> kept = AlreadyAppliedFindingFilter.filter(
                FindingParser.parse(ollamaJson(PR90_FINDING, PR89_FINDING)), combined);

        assertTrue(kept.isEmpty());
    }

    @Test
    void realPr90FixReportedAgainstPr89FileIsKept() throws IOException {
        // PR #90's exact fix text, but attributed to LoginPage - which never adds that line.
        String combined = DiffFetcher.filterDiff(fixture("pr90.diff") + fixture("pr89.diff"));
        JsonObject misattributed = PR90_FINDING.deepCopy();
        misattributed.addProperty("file", LOGIN_PAGE);

        List<ReviewFinding> kept = AlreadyAppliedFindingFilter.filter(
                FindingParser.parse(ollamaJson(misattributed)), combined);

        assertEquals(1, kept.size());
        assertTrue(gateFails(kept));
    }

    @Test
    void genuineThreadSleepViolationIsStillReportedAndFailsGate() {
        List<ReviewFinding> kept = AlreadyAppliedFindingFilter.filter(
                FindingParser.parse(ollamaJson(THREAD_SLEEP_FINDING)), DiffFetcher.filterDiff(THREAD_SLEEP_DIFF));

        assertEquals(1, kept.size());
        assertEquals("FAILED", kept.get(0).status);
        assertEquals("waitForVisible(loginButton);", kept.get(0).suggestedFix);
        assertTrue(gateFails(kept));
    }

    @Test
    void mixedDiffKeepsOnlyTheGenuineViolationAndGateStillFails() throws IOException {
        String mixed = DiffFetcher.filterDiff(fixture("pr90.diff") + THREAD_SLEEP_DIFF);
        JsonObject passed = finding("Logging", "PASSED", "", 0, "", "");

        List<ReviewFinding> kept = AlreadyAppliedFindingFilter.filter(
                FindingParser.parse(ollamaJson(PR90_FINDING, THREAD_SLEEP_FINDING, passed)), mixed);

        assertEquals(2, kept.size());
        List<ReviewFinding> failed = failedOnly(kept);
        assertEquals(1, failed.size());
        assertEquals("Hardcoded Configurations", failed.get(0).category);
        assertEquals(LOGIN_PAGE, failed.get(0).file);
        assertTrue(gateFails(kept));
    }

    // Same two steps LocalCodeReviewer.main() applies after filtering.
    private static List<ReviewFinding> failedOnly(List<ReviewFinding> findings) {
        return findings.stream().filter(finding -> "FAILED".equalsIgnoreCase(finding.status)).toList();
    }

    private static boolean gateFails(List<ReviewFinding> filteredFindings) {
        return !failedOnly(filteredFindings).isEmpty();
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = AlreadyAppliedFindingFilterRealDataTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String ollamaJson(JsonObject... findings) {
        JsonArray array = new JsonArray();
        for (JsonObject finding : findings) {
            array.add(finding);
        }
        JsonObject root = new JsonObject();
        root.add("findings", array);
        return root.toString();
    }

    private static JsonObject finding(String category, String status, String file, int line, String problem,
            String suggestedFix) {
        JsonObject obj = new JsonObject();
        obj.addProperty("category", category);
        obj.addProperty("status", status);
        obj.addProperty("file", file);
        obj.addProperty("line", line);
        obj.addProperty("problem", problem);
        obj.addProperty("suggestedFix", suggestedFix);
        return obj;
    }
}
