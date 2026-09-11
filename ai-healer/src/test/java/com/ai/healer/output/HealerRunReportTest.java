package com.ai.healer.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ai.healer.HealOrchestrator.GroupOutcome;
import com.ai.healer.HealOrchestrator.Outcome;
import com.ai.healer.HealOrchestrator.PrOutcome;
import com.ai.healer.HealOrchestrator.Result;
import com.ai.healer.HealOrchestrator.RunSummary;
import com.ai.healer.HealOrchestrator.UnresolvedFeature;
import com.ai.healer.report.ScenarioGroup;
import com.ai.healer.report.TestFailure;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HealerRunReportTest {

    @Test
    void writesHealedAndKeptEntriesForAGroupWithNoPr(@TempDir Path tempDir) throws Exception {
        TestFailure failure = new TestFailure();
        failure.testName = "Standard User can login successfully";
        failure.className = "User Login Flow";

        Result result = newResult(failure, Outcome.HEALED,
                List.of("LoginPage.java:1 \"#broken\" -> \"#login-button\""), List.of(), "");
        ScenarioGroup group = new ScenarioGroup(tempDir.resolve("login.feature"), List.of(failure));
        GroupOutcome groupOutcome = new GroupOutcome(group, List.of(result), List.of());
        RunSummary summary = new RunSummary(List.of(result), List.of(), 2, List.of(groupOutcome), List.of());

        Path outputPath = tempDir.resolve("healer-run-report.json");
        HealerRunReport.write(summary, Map.of(), outputPath);

        JsonObject document = JsonParser.parseString(Files.readString(outputPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject featureGroup = document.getAsJsonArray("featureGroups").get(0).getAsJsonObject();
        assertEquals(false, featureGroup.get("prCreated").getAsBoolean());
        assertEquals("LoginPage.java:1 \"#broken\" -> \"#login-button\"",
                featureGroup.getAsJsonArray("healedAndKept").get(0).getAsString());
    }

    @Test
    void excludesNotFixableEntriesForAGroupThatGotAPr(@TempDir Path tempDir) throws Exception {
        TestFailure failure = new TestFailure();
        failure.testName = "Some other scenario";
        failure.className = "Shopping Cart";
        failure.failureType = "org.opentest4j.AssertionFailedError";

        Result notFixable = newResult(failure, Outcome.NOT_FIXABLE, List.of(), List.of(), failure.failureType);
        ScenarioGroup group = new ScenarioGroup(tempDir.resolve("cart.feature"), List.of(failure));
        GroupOutcome groupOutcome = new GroupOutcome(group, List.of(notFixable), List.of());
        RunSummary summary = new RunSummary(List.of(notFixable), List.of(), 2, List.of(groupOutcome), List.of());

        Map<Path, PrOutcome> prOutcomes = Map.of(group.featureFilePath(), new PrOutcome("heal/cart-feature", 7, "https://example/pr/7"));

        Path outputPath = tempDir.resolve("healer-run-report.json");
        HealerRunReport.write(summary, prOutcomes, outputPath);

        JsonObject document = JsonParser.parseString(Files.readString(outputPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject featureGroup = document.getAsJsonArray("featureGroups").get(0).getAsJsonObject();
        assertEquals(true, featureGroup.get("prCreated").getAsBoolean());
        assertEquals("https://example/pr/7", featureGroup.get("pullRequestUrl").getAsString());
        assertEquals(0, featureGroup.getAsJsonArray("notFixable").size());
    }

    @Test
    void writesUnresolvedFeatureDiagnostics(@TempDir Path tempDir) throws Exception {
        TestFailure failure = new TestFailure();
        failure.testName = "Orphaned scenario";
        failure.className = "No Such Feature";

        UnresolvedFeature unresolved = new UnresolvedFeature("No Such Feature", 4,
                List.of(Path.of("a.feature"), Path.of("b.feature")), List.of(failure));
        RunSummary summary = new RunSummary(List.of(), List.of(), 2, List.of(), List.of(unresolved));

        Path outputPath = tempDir.resolve("nested/healer-run-report.json");
        Path written = HealerRunReport.write(summary, Map.of(), outputPath);

        assertTrue(Files.exists(written));
        JsonObject document = JsonParser.parseString(Files.readString(written, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject unresolvedEntry = document.getAsJsonArray("unresolvedFeatures").get(0).getAsJsonObject();
        assertEquals("No Such Feature", unresolvedEntry.get("className").getAsString());
        assertEquals(4, unresolvedEntry.get("featureFilesScanned").getAsInt());
        assertEquals(1, unresolvedEntry.get("unprocessedFailureCount").getAsInt());
        assertEquals(2, unresolvedEntry.getAsJsonArray("candidateFeatureFiles").size());
    }

    // HealOrchestrator.Result's constructor is package-private (com.ai.healer) - reflection avoids
    // needing to either widen it just for this test or run a full HealOrchestrator to get one.
    private static Result newResult(TestFailure failure, Outcome outcome, List<String> healedAndKept,
            List<Path> changedFiles, String note) throws Exception {
        Constructor<Result> constructor = Result.class.getDeclaredConstructor(
                TestFailure.class, Outcome.class, List.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(failure, outcome, healedAndKept, changedFiles, note);
    }
}
