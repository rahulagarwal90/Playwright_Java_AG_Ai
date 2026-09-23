package com.ai.healer.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                List.of(new HealedLocatorEntry("LoginPage.java:1 \"#broken\" -> \"#login-button\"", "low", true)),
                List.of(), "");
        ScenarioGroup group = new ScenarioGroup(tempDir.resolve("login.feature"), List.of(failure));
        GroupOutcome groupOutcome = new GroupOutcome(group, List.of(result), List.of());
        RunSummary summary = new RunSummary(List.of(result), List.of(), 2, List.of(groupOutcome), List.of());

        Path outputPath = tempDir.resolve("healer-run-report.json");
        HealerRunReport.write(summary, Map.of(), outputPath);

        JsonObject document = JsonParser.parseString(Files.readString(outputPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject featureGroup = document.getAsJsonArray("featureGroups").get(0).getAsJsonObject();
        assertEquals(false, featureGroup.get("prCreated").getAsBoolean());
        JsonObject healedEntry = featureGroup.getAsJsonArray("healedAndKept").get(0).getAsJsonObject();
        assertEquals("LoginPage.java:1 \"#broken\" -> \"#login-button\"", healedEntry.get("description").getAsString());
        assertEquals("low", healedEntry.get("confidence").getAsString());
        assertEquals(true, healedEntry.get("ambiguousMatch").getAsBoolean());
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
    void aCleanPassWithNoFailuresIsUnmistakablyDifferentFromEveryAttemptErroring(@TempDir Path tempDir) throws Exception {
        // A genuinely clean run - SurefireReportReader found nothing to heal.
        RunSummary cleanPass = new RunSummary(List.of(), List.of(), 2, List.of(), List.of());
        Path cleanPassOutput = tempDir.resolve("clean-pass.json");
        HealerRunReport.write(cleanPass, Map.of(), cleanPassOutput);
        JsonObject cleanPassDocument =
                JsonParser.parseString(Files.readString(cleanPassOutput, StandardCharsets.UTF_8)).getAsJsonObject();

        // Every single heal attempt in the run errored out - e.g. Ollama was down for the whole run.
        TestFailure failureOne = new TestFailure();
        failureOne.testName = "Standard user can add item to cart";
        failureOne.className = "Shopping Cart";
        failureOne.failureMessage = "TimeoutError: waiting for locator(\"[data-test='checkout']\")";
        Result healErrorOne = newResult(failureOne, Outcome.HEAL_ERROR, List.of(), List.of(),
                "Connection refused");

        TestFailure failureTwo = new TestFailure();
        failureTwo.testName = "Standard user can log in";
        failureTwo.className = "User Login Flow";
        failureTwo.failureMessage = "TimeoutError: waiting for locator(\"#login-button\")";
        Result healErrorTwo = newResult(failureTwo, Outcome.HEAL_ERROR, List.of(), List.of(),
                "Connection refused");

        ScenarioGroup groupOne = new ScenarioGroup(tempDir.resolve("cart.feature"), List.of(failureOne));
        ScenarioGroup groupTwo = new ScenarioGroup(tempDir.resolve("login.feature"), List.of(failureTwo));
        GroupOutcome groupOutcomeOne = new GroupOutcome(groupOne, List.of(healErrorOne), List.of());
        GroupOutcome groupOutcomeTwo = new GroupOutcome(groupTwo, List.of(healErrorTwo), List.of());
        RunSummary allErrored = new RunSummary(List.of(healErrorOne, healErrorTwo), List.of(), 2,
                List.of(groupOutcomeOne, groupOutcomeTwo), List.of());
        Path allErroredOutput = tempDir.resolve("all-errored.json");
        HealerRunReport.write(allErrored, Map.of(), allErroredOutput);
        JsonObject allErroredDocument =
                JsonParser.parseString(Files.readString(allErroredOutput, StandardCharsets.UTF_8)).getAsJsonObject();

        // The clean pass reports itself as exactly that, with nothing needing attention.
        assertEquals("NOTHING_TO_HEAL", cleanPassDocument.get("runStatus").getAsString());
        assertEquals(0, cleanPassDocument.get("totalFailuresProcessed").getAsInt());
        assertEquals(0, cleanPassDocument.get("healErrorCount").getAsInt());
        assertEquals(0, cleanPassDocument.getAsJsonArray("featureGroups").size());

        // The all-errored run is unmistakably NOT a clean pass, at the top level alone.
        assertEquals("ALL_ATTEMPTS_ERRORED", allErroredDocument.get("runStatus").getAsString());
        assertEquals(2, allErroredDocument.get("totalFailuresProcessed").getAsInt());
        assertEquals(2, allErroredDocument.get("healErrorCount").getAsInt());
        assertTrue(!cleanPassDocument.get("runStatus").getAsString()
                .equals(allErroredDocument.get("runStatus").getAsString()));
    }

    @Test
    void healErrorEntriesCarryScenarioAndErrorDetailAndAreExcludedFromNotFixable(@TempDir Path tempDir) throws Exception {
        TestFailure failure = new TestFailure();
        failure.testName = "Standard user can check out";
        failure.className = "Checkout Flow";
        failure.failureMessage = "TimeoutError: waiting for locator(\"[data-test='checkout']\")";

        Result healError = newResult(failure, Outcome.HEAL_ERROR, List.of(), List.of(), "Connection refused");
        ScenarioGroup group = new ScenarioGroup(tempDir.resolve("checkout.feature"), List.of(failure));
        GroupOutcome groupOutcome = new GroupOutcome(group, List.of(healError), List.of());
        RunSummary summary = new RunSummary(List.of(healError), List.of(), 2, List.of(groupOutcome), List.of());

        Path outputPath = tempDir.resolve("heal-error.json");
        HealerRunReport.write(summary, Map.of(), outputPath);

        JsonObject document = JsonParser.parseString(Files.readString(outputPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject featureGroup = document.getAsJsonArray("featureGroups").get(0).getAsJsonObject();

        // Not silently dropped, and not folded into notFixable - HEAL_ERROR is not a NOT_FIXABLE
        // classification.
        assertEquals(0, featureGroup.getAsJsonArray("notFixable").size());
        assertEquals(1, featureGroup.getAsJsonArray("healErrors").size());

        JsonObject healErrorEntry = featureGroup.getAsJsonArray("healErrors").get(0).getAsJsonObject();
        assertEquals("Standard user can check out", healErrorEntry.get("testName").getAsString());
        assertEquals("TimeoutError: waiting for locator(\"[data-test='checkout']\")",
                healErrorEntry.get("failureMessage").getAsString());
        assertEquals("Connection refused", healErrorEntry.get("error").getAsString());

        assertEquals("ALL_ATTEMPTS_ERRORED", document.get("runStatus").getAsString());
    }

    @Test
    void aMixOfHealedAndErroredResultsIsReportedAsPartialErrorsNotAllErrored(@TempDir Path tempDir) throws Exception {
        TestFailure healedFailure = new TestFailure();
        healedFailure.testName = "Standard user can log in";
        healedFailure.className = "User Login Flow";
        Result healed = newResult(healedFailure, Outcome.HEALED,
                List.of(new HealedLocatorEntry("LoginPage.java:1 \"#broken\" -> \"#login-button\"", "high", false)),
                List.of(), "");

        TestFailure erroredFailure = new TestFailure();
        erroredFailure.testName = "Standard user can add item to cart";
        erroredFailure.className = "Shopping Cart";
        erroredFailure.failureMessage = "TimeoutError: waiting for locator(\"[data-test='checkout']\")";
        Result healError = newResult(erroredFailure, Outcome.HEAL_ERROR, List.of(), List.of(), "Connection refused");

        ScenarioGroup group = new ScenarioGroup(tempDir.resolve("mixed.feature"), List.of(healedFailure, erroredFailure));
        GroupOutcome groupOutcome = new GroupOutcome(group, List.of(healed, healError), List.of());
        RunSummary summary = new RunSummary(List.of(healed, healError), List.of(), 2, List.of(groupOutcome), List.of());

        Path outputPath = tempDir.resolve("partial-errors.json");
        HealerRunReport.write(summary, Map.of(), outputPath);

        JsonObject document = JsonParser.parseString(Files.readString(outputPath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("PARTIAL_ERRORS", document.get("runStatus").getAsString());
        assertEquals(2, document.get("totalFailuresProcessed").getAsInt());
        assertEquals(1, document.get("healErrorCount").getAsInt());
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
    private static Result newResult(TestFailure failure, Outcome outcome, List<HealedLocatorEntry> healedAndKept,
            List<Path> changedFiles, String note) throws Exception {
        Constructor<Result> constructor = Result.class.getDeclaredConstructor(
                TestFailure.class, Outcome.class, List.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(failure, outcome, healedAndKept, changedFiles, note);
    }
}
