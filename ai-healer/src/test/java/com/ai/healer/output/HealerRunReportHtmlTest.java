package com.ai.healer.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ai.healer.HealOrchestrator.GroupOutcome;
import com.ai.healer.HealOrchestrator.HealedLocatorEntry;
import com.ai.healer.HealOrchestrator.Outcome;
import com.ai.healer.HealOrchestrator.PrOutcome;
import com.ai.healer.HealOrchestrator.Result;
import com.ai.healer.HealOrchestrator.RunSummary;
import com.ai.healer.HealOrchestrator.UnresolvedFeature;
import com.ai.healer.report.ScenarioGroup;
import com.ai.healer.report.TestFailure;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HealerRunReportHtmlTest {

    @Test
    void writesFeatureFilePrUrlAndHealedLocatorDescription(@TempDir Path tempDir) throws Exception {
        TestFailure failure = new TestFailure();
        failure.testName = "Standard user can check out";
        failure.className = "Shopping Cart";

        Result healed = newResult(failure, Outcome.HEALED,
                List.of(new HealedLocatorEntry("CartPage.checkoutButton: [datatest=checkout] -> #checkout", "high", false)),
                List.of(), "");
        ScenarioGroup group = new ScenarioGroup(tempDir.resolve("cart.feature"), List.of(failure));
        GroupOutcome groupOutcome = new GroupOutcome(group, List.of(healed), List.of());
        RunSummary summary = new RunSummary(List.of(healed), List.of(), 2, List.of(groupOutcome), List.of());

        Map<Path, PrOutcome> prOutcomes =
                Map.of(group.featureFilePath(), new PrOutcome("heal/cart-feature", 7, "https://example/pr/7", true));

        Path outputPath = tempDir.resolve("nested/healer-run-report.html");
        Path written = HealerRunReportHtml.write(summary, prOutcomes, outputPath);

        assertTrue(Files.exists(written));
        String html = Files.readString(written, StandardCharsets.UTF_8);
        assertTrue(html.contains("cart.feature"));
        assertTrue(html.contains("https://example/pr/7"));
        assertTrue(html.contains("CartPage.checkoutButton: [datatest=checkout] -&gt; #checkout"));
        assertTrue(html.contains("COMPLETED"));
    }

    @Test
    void escapesHtmlSpecialCharactersInDescriptions(@TempDir Path tempDir) throws Exception {
        TestFailure failure = new TestFailure();
        failure.testName = "Standard user can log in";
        failure.className = "User Login Flow";

        Result healed = newResult(failure, Outcome.HEALED,
                List.of(new HealedLocatorEntry("LoginPage.java:1 \"<script>\" -> \"#login-button\"", "low", true)),
                List.of(), "");
        ScenarioGroup group = new ScenarioGroup(tempDir.resolve("login.feature"), List.of(failure));
        GroupOutcome groupOutcome = new GroupOutcome(group, List.of(healed), List.of());
        RunSummary summary = new RunSummary(List.of(healed), List.of(), 2, List.of(groupOutcome), List.of());

        Path outputPath = tempDir.resolve("healer-run-report.html");
        HealerRunReportHtml.write(summary, Map.of(), outputPath);

        String html = Files.readString(outputPath, StandardCharsets.UTF_8);
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&quot;&lt;script&gt;&quot; -&gt; &quot;#login-button&quot;"));
        assertTrue(html.contains("Label applied: N/A"));
    }

    @Test
    void writesStylesheetNextToHtmlAndLinksItInsteadOfInlineStyle(@TempDir Path tempDir) throws Exception {
        RunSummary summary = new RunSummary(List.of(), List.of(), 2, List.of(), List.of());

        Path outputPath = tempDir.resolve("nested/healer-run-report.html");
        HealerRunReportHtml.write(summary, Map.of(), outputPath);

        Path cssPath = outputPath.resolveSibling("healer-run-report.css");
        assertTrue(Files.exists(cssPath));
        Field styleField = HealerRunReportHtml.class.getDeclaredField("STYLE");
        styleField.setAccessible(true);
        String style = (String) styleField.get(null);
        assertEquals(style, Files.readString(cssPath, StandardCharsets.UTF_8));

        String html = Files.readString(outputPath, StandardCharsets.UTF_8);
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"healer-run-report.css\">"));
        assertFalse(html.contains("<style"));
    }

    @Test
    void rendersEverySectionOfARealisticMixedRunWithLinkedStylesheet(@TempDir Path tempDir) throws Exception {
        TestFailure cartFailure = failure("Standard user can check out", "Shopping Cart", "TimeoutError");
        Result healed = newResult(cartFailure, Outcome.HEALED,
                List.of(new HealedLocatorEntry("CartPage.checkoutButton: \"[datatest='checkout']\" -> \"#checkout\"",
                        "high", false)),
                List.of(tempDir.resolve("CartPage.java")), "");
        ScenarioGroup cartGroup = new ScenarioGroup(tempDir.resolve("cart.feature"), List.of(cartFailure));

        TestFailure loginFailure = failure("Locked out user sees error", "User Login Flow", "AssertionFailedError");
        Result notFixable = newResult(loginFailure, Outcome.NOT_FIXABLE, List.of(), List.of(),
                "Value mismatch - needs a human");
        ScenarioGroup loginGroup = new ScenarioGroup(tempDir.resolve("login.feature"), List.of(loginFailure));

        TestFailure checkoutFailure = failure("Standard user completes order", "Checkout", "TimeoutError");
        Result healError = newResult(checkoutFailure, Outcome.HEAL_ERROR, List.of(), List.of(),
                "Ollama request failed: connection refused");
        ScenarioGroup checkoutGroup = new ScenarioGroup(tempDir.resolve("checkout.feature"), List.of(checkoutFailure));

        TestFailure orphanFailure = failure("Orphan scenario", "Ghost Feature", "TimeoutError");
        UnresolvedFeature unresolved = new UnresolvedFeature("Ghost Feature", 4,
                List.of(tempDir.resolve("a.feature")), List.of(orphanFailure));

        RunSummary summary = new RunSummary(List.of(healed, notFixable, healError), List.of(), 2,
                List.of(new GroupOutcome(cartGroup, List.of(healed), List.of()),
                        new GroupOutcome(loginGroup, List.of(notFixable), List.of()),
                        new GroupOutcome(checkoutGroup, List.of(healError), List.of())),
                List.of(unresolved));
        Map<Path, PrOutcome> prOutcomes = Map.of(cartGroup.featureFilePath(),
                new PrOutcome("heal/cart-feature", 90, "https://github.com/example/repo/pull/90", true));

        Path outputPath = tempDir.resolve("healer-run-report.html");
        HealerRunReportHtml.write(summary, prOutcomes, outputPath);
        String html = Files.readString(outputPath, StandardCharsets.UTF_8);

        // Header summary
        assertTrue(html.contains("<dt>Run status</dt><dd>PARTIAL_ERRORS</dd>"));
        assertTrue(html.contains("<dt>Total failures processed</dt><dd>3</dd>"));
        assertTrue(html.contains("<dt>Heal errors</dt><dd>1</dd>"));

        // Group with a PR + healed locator
        String cart = section(html, "cart.feature");
        assertTrue(cart.contains("<a href=\"https://github.com/example/repo/pull/90\">"));
        assertTrue(cart.contains("Label applied: Yes"));
        assertTrue(cart.contains("<td>CartPage.checkoutButton: &quot;[datatest=&#39;checkout&#39;]&quot; -&gt; "
                + "&quot;#checkout&quot;</td><td>high</td><td>No</td>"));
        assertFalse(cart.contains("Not fixable"));

        // Group with no PR + NOT_FIXABLE
        String login = section(html, "login.feature");
        assertTrue(login.contains("Pull request: none"));
        assertTrue(login.contains("Label applied: N/A"));
        assertTrue(login.contains("<h3>Not fixable</h3>"));
        assertTrue(login.contains("<td>Locked out user sees error</td><td>AssertionFailedError</td>"
                + "<td>Value mismatch - needs a human</td>"));

        // Group with a HEAL_ERROR
        String checkout = section(html, "checkout.feature");
        assertTrue(checkout.contains("<h3>Heal errors</h3>"));
        assertTrue(checkout.contains("<td>Standard user completes order</td>"
                + "<td>Ollama request failed: connection refused</td>"));

        // Unresolved feature
        assertTrue(html.contains("<h2>Unresolved features</h2>"));
        assertTrue(html.contains("<td>Ghost Feature</td><td>4</td><td>1</td>"));

        // Styling is linked, never inline
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"healer-run-report.css\">"));
        assertFalse(html.contains("<style"));
        assertTrue(Files.exists(tempDir.resolve("healer-run-report.css")));
    }

    @Test
    void rewritingReportOverwritesStaleStylesheet(@TempDir Path tempDir) throws Exception {
        // A previous run's (or hand-edited) CSS left in target/ must not survive a new write.
        Path outputPath = tempDir.resolve("healer-run-report.html");
        Path cssPath = tempDir.resolve("healer-run-report.css");
        Files.writeString(cssPath, "stale { color: red; }");

        HealerRunReportHtml.write(new RunSummary(List.of(), List.of(), 2, List.of(), List.of()), Map.of(), outputPath);

        String css = Files.readString(cssPath, StandardCharsets.UTF_8);
        assertFalse(css.contains("stale"));
        assertTrue(css.contains("dl.summary"));
        assertTrue(Files.readString(outputPath, StandardCharsets.UTF_8).contains("NOTHING_TO_HEAL"));
    }

    private static TestFailure failure(String testName, String className, String failureType) {
        TestFailure failure = new TestFailure();
        failure.testName = testName;
        failure.className = className;
        failure.failureType = failureType;
        return failure;
    }

    // The <section> for one feature file, so assertions can't accidentally match another group's content.
    private static String section(String html, String featureFileName) {
        int start = html.indexOf(featureFileName + "</h2>");
        assertTrue(start >= 0, "no section for " + featureFileName);
        return html.substring(start, html.indexOf("</section>", start));
    }

    // Same reflection approach as HealerRunReportTest - Result's constructor is package-private.
    private static Result newResult(TestFailure failure, Outcome outcome, List<HealedLocatorEntry> healedAndKept,
            List<Path> changedFiles, String note) throws Exception {
        Constructor<Result> constructor = Result.class.getDeclaredConstructor(
                TestFailure.class, Outcome.class, List.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(failure, outcome, healedAndKept, changedFiles, note);
    }
}
