package com.ai.healer.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ai.healer.HealOrchestrator.GroupOutcome;
import com.ai.healer.HealOrchestrator.HealedLocatorEntry;
import com.ai.healer.HealOrchestrator.Outcome;
import com.ai.healer.HealOrchestrator.PrOutcome;
import com.ai.healer.HealOrchestrator.Result;
import com.ai.healer.HealOrchestrator.RunSummary;
import com.ai.healer.report.ScenarioGroup;
import com.ai.healer.report.TestFailure;
import java.lang.reflect.Constructor;
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

    // Same reflection approach as HealerRunReportTest - Result's constructor is package-private.
    private static Result newResult(TestFailure failure, Outcome outcome, List<HealedLocatorEntry> healedAndKept,
            List<Path> changedFiles, String note) throws Exception {
        Constructor<Result> constructor = Result.class.getDeclaredConstructor(
                TestFailure.class, Outcome.class, List.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(failure, outcome, healedAndKept, changedFiles, note);
    }
}
