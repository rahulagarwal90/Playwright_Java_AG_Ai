package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.healer.github.HealerGitClient;
import com.ai.healer.github.HealerPullRequestCreator;
import com.ai.healer.github.NotFixablePrCommenter;
import com.ai.healer.ollama.LocatorHealer;
import com.ai.healer.patch.PageObjectPatcher;
import com.ai.healer.report.FeatureFileResolver;
import com.ai.healer.report.SurefireReportReader;
import com.ai.healer.report.TestFailure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HealOrchestratorTest {

    private static final int DEFAULT_MAX_RETRIES = 2;

    @Test
    void notFixableFailureIsLoggedAndNeverReachesLocatorHealer() throws Exception {
        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure notFixable = new TestFailure();
        notFixable.testName = "Some assertion failure";
        notFixable.className = "User Login Flow";
        notFixable.failureType = "org.opentest4j.AssertionFailedError";
        notFixable.failureMessage = "expected: <a> but was: <b>";
        when(reader.readFailures()).thenReturn(List.of(notFixable));

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        PageObjectPatcher patcher = mock(PageObjectPatcher.class);

        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, patcher, name -> null, false, DEFAULT_MAX_RETRIES);

        List<HealOrchestrator.Result> results = orchestrator.run();

        assertEquals(1, results.size());
        assertEquals(HealOrchestrator.Outcome.NOT_FIXABLE, results.get(0).outcome);
        verify(locatorHealer, never()).heal(any());
    }

    // Pipeline context now heals/patches/re-runs exactly the same way local context does (see
    // localContextHealsWhenPatchAppliesAndRerunPasses below) - the only thing that differs is
    // what happens AFTERWARD: a healed group gets its changes pushed to a branch and opened as a
    // PR (HealerGitClient/HealerPullRequestCreator) instead of being left as an uncommitted local
    // change. This replaces the old "pipeline context only logs a suggestion, never patches"
    // test - that behavior no longer exists now that there's somewhere for a pipeline-context
    // patch to go (a PR) instead of an uncommitted local file.
    @Test
    void pipelineContextHealsPatchesAndRerunsThenOpensAPrForTheHealedGroup(@TempDir Path tempDir) throws Exception {
        Path pageFile = tempDir.resolve("LoginPage.java");
        Files.writeString(pageFile, "private final String loginButton = \"#login-button-BROKEN\";\n", StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#login-button-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        LocatorHealer.HealResult healResult = healResult("#login-button-BROKEN", "#login-button", pageFile, 1);
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        PageObjectPatcher realPatcher = new PageObjectPatcher();

        HealerGitClient gitClient = mock(HealerGitClient.class);
        HealerGitClient.BranchResult branch = new HealerGitClient.BranchResult("heal/login-feature-20260101-000000");
        when(gitClient.commitAndPushHealedGroup(any(), any(), any())).thenReturn(branch);

        HealerPullRequestCreator pullRequestCreator = mock(HealerPullRequestCreator.class);
        HealerPullRequestCreator.PullRequest pullRequest =
                new HealerPullRequestCreator.PullRequest(42, "https://github.com/example/repo/pull/42");
        when(pullRequestCreator.createPullRequest(eq(branch.branchName()), any(), any())).thenReturn(pullRequest);

        NotFixablePrCommenter commenter = mock(NotFixablePrCommenter.class);

        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, name -> null, true, DEFAULT_MAX_RETRIES,
                new FeatureFileResolver(), gitClient, pullRequestCreator, commenter);

        List<HealOrchestrator.Result> results = orchestrator.run();

        assertEquals(HealOrchestrator.Outcome.HEALED, results.get(0).outcome);
        assertEquals(List.of(new HealOrchestrator.HealedLocatorEntry(
                        "LoginPage.java:1 \"#login-button-BROKEN\" -> \"#login-button\"", "high", false)),
                results.get(0).healedAndKept);
        assertEquals("private final String loginButton = \"#login-button\";\n",
                Files.readString(pageFile, StandardCharsets.UTF_8));

        verify(gitClient).commitAndPushHealedGroup(any(), eq(List.of(pageFile)), any());
        verify(pullRequestCreator).createPullRequest(eq(branch.branchName()), any(), any());
        verify(commenter, never()).postNotFixableComments(anyInt(), any());
    }

    @Test
    void localContextHealsWhenPatchAppliesAndRerunPasses(@TempDir Path tempDir) throws Exception {
        Path pageFile = tempDir.resolve("LoginPage.java");
        Files.writeString(pageFile, "private final String loginButton = \"#login-button-BROKEN\";\n", StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#login-button-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        LocatorHealer.HealResult healResult = healResult("#login-button-BROKEN", "#login-button", pageFile, 1);
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, name -> null, false, DEFAULT_MAX_RETRIES);

        List<HealOrchestrator.Result> results = orchestrator.run();

        assertEquals(HealOrchestrator.Outcome.HEALED, results.get(0).outcome);
        assertEquals(List.of(new HealOrchestrator.HealedLocatorEntry(
                        "LoginPage.java:1 \"#login-button-BROKEN\" -> \"#login-button\"", "high", false)),
                results.get(0).healedAndKept);
        assertEquals("private final String loginButton = \"#login-button\";\n",
                Files.readString(pageFile, StandardCharsets.UTF_8));
    }

    @Test
    void revertsOnlyWhenRerunStillFailsAtTheSameLocator(@TempDir Path tempDir) throws Exception {
        Path pageFile = tempDir.resolve("LoginPage.java");
        String original = "private final String loginButton = \"#login-button-BROKEN\";\n";
        Files.writeString(pageFile, original, StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#login-button-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        LocatorHealer.HealResult healResult = healResult("#login-button-BROKEN", "#still-wrong", pageFile, 1);
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        // The re-run reports the SAME locator ("#still-wrong", exactly what we just patched in)
        // still failing - the fix genuinely didn't work.
        HealOrchestrator.ScenarioRerunner rerunner = name -> locatorFailure("#still-wrong");

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, rerunner, false, DEFAULT_MAX_RETRIES);

        List<HealOrchestrator.Result> results = orchestrator.run();

        assertEquals(HealOrchestrator.Outcome.HEAL_FAILED, results.get(0).outcome);
        assertTrue(results.get(0).healedAndKept.isEmpty(), "a reverted patch must not be reported as healed");
        assertEquals(original, Files.readString(pageFile, StandardCharsets.UTF_8),
                "file must be reverted to its exact pre-patch content when the re-run still fails at the same locator");
    }

    // Task 1's other requirement: confidence must never skip or shortcut the live re-run/verify
    // step. Runs the exact same "still fails at the same locator -> revert" scenario twice, once
    // with a "high" confidence heal and once with "low", and verifies the mocked rerunner is
    // actually invoked - and the outcome (reverted) is identical - both times. If confidence ever
    // gated the re-run, a "high" confidence heal here would be kept unverified instead of reverted.
    @Test
    void rerunIsAlwaysInvokedAndCanRevertAHighConfidenceHealJustAsReadilyAsALowConfidenceOne(
            @TempDir Path tempDir) throws Exception {
        assertRerunIsUnconditionalForConfidence(tempDir, "high");
    }

    @Test
    void rerunIsAlwaysInvokedForALowConfidenceHealToo(@TempDir Path tempDir) throws Exception {
        assertRerunIsUnconditionalForConfidence(tempDir, "low");
    }

    private void assertRerunIsUnconditionalForConfidence(Path tempDir, String confidence) throws Exception {
        Path pageFile = tempDir.resolve("LoginPage.java");
        String original = "private final String loginButton = \"#login-button-BROKEN\";\n";
        Files.writeString(pageFile, original, StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#login-button-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        LocatorHealer.HealResult healResult = healResult("#login-button-BROKEN", "#still-wrong", pageFile, 1);
        healResult.confidence = confidence;
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        // Reports the SAME locator still failing regardless of what confidence was reported - a
        // genuinely unverified fix must be reverted whether the AI called it "high" or "low".
        HealOrchestrator.ScenarioRerunner rerunner = mock(HealOrchestrator.ScenarioRerunner.class);
        when(rerunner.rerun(anyString())).thenReturn(locatorFailure("#still-wrong"));

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, rerunner, false, DEFAULT_MAX_RETRIES);

        List<HealOrchestrator.Result> results = orchestrator.run();

        verify(rerunner).rerun(anyString());
        assertEquals(HealOrchestrator.Outcome.HEAL_FAILED, results.get(0).outcome);
        assertTrue(results.get(0).healedAndKept.isEmpty(),
                "a " + confidence + "-confidence patch that fails live re-verification must still be reverted");
        assertEquals(original, Files.readString(pageFile, StandardCharsets.UTF_8),
                "confidence must never skip the live re-run/revert check");
    }

    @Test
    void keepsACorrectPatchWhenRerunFailsAtADifferentLocatorAndChasesIt(@TempDir Path tempDir) throws Exception {
        // The real double-break scenario: passwordInput gets healed correctly, but the re-run
        // then reveals loginButton, previously masked. Both should end up healed and kept.
        Path pageFile = tempDir.resolve("LoginPage.java");
        Files.writeString(pageFile,
                "private final String passwordInput = \"password\";\n"
                + "private final String loginButton = \"#login-button-BROKEN\";\n",
                StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure originalFailure = locatorFailure("password");
        when(reader.readFailures()).thenReturn(List.of(originalFailure));

        TestFailure loginButtonFailure = locatorFailure("#login-button-BROKEN");

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(originalFailure))
                .thenReturn(healResult("password", "#password", pageFile, 1));
        when(locatorHealer.heal(loginButtonFailure))
                .thenReturn(healResult("#login-button-BROKEN", "#login-button", pageFile, 2));

        // First rerun (after fixing passwordInput): still fails, but now on loginButton.
        // Second rerun (after fixing loginButton too): passes.
        HealOrchestrator.ScenarioRerunner rerunner = mock(HealOrchestrator.ScenarioRerunner.class);
        when(rerunner.rerun(anyString())).thenReturn(loginButtonFailure, (TestFailure) null);

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, rerunner, false, DEFAULT_MAX_RETRIES);

        List<HealOrchestrator.Result> results = orchestrator.run();

        assertEquals(HealOrchestrator.Outcome.HEALED, results.get(0).outcome);
        assertEquals(List.of(
                        new HealOrchestrator.HealedLocatorEntry(
                                "LoginPage.java:1 \"password\" -> \"#password\"", "high", false),
                        new HealOrchestrator.HealedLocatorEntry(
                                "LoginPage.java:2 \"#login-button-BROKEN\" -> \"#login-button\"", "high", false)),
                results.get(0).healedAndKept);
        assertEquals(
                "private final String passwordInput = \"#password\";\n"
                + "private final String loginButton = \"#login-button\";\n",
                Files.readString(pageFile, StandardCharsets.UTF_8));
    }

    @Test
    void stopsAndReportsWhenRetryBudgetIsExhaustedBeforeFullResolution(@TempDir Path tempDir) throws Exception {
        // Same double-break shape as above, but maxRetries=1: only the first locator gets a heal
        // attempt at all. The correct passwordInput fix must still be kept (not reverted), and the
        // still-unresolved loginButton failure must be reported with a plain-English note.
        Path pageFile = tempDir.resolve("LoginPage.java");
        Files.writeString(pageFile,
                "private final String passwordInput = \"password\";\n"
                + "private final String loginButton = \"#login-button-BROKEN\";\n",
                StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure originalFailure = locatorFailure("password");
        when(reader.readFailures()).thenReturn(List.of(originalFailure));

        TestFailure loginButtonFailure = locatorFailure("#login-button-BROKEN");

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(originalFailure))
                .thenReturn(healResult("password", "#password", pageFile, 1));

        HealOrchestrator.ScenarioRerunner rerunner = name -> loginButtonFailure;

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, rerunner, false, 1);

        List<HealOrchestrator.Result> results = orchestrator.run();

        assertEquals(HealOrchestrator.Outcome.MAX_RETRIES_EXCEEDED, results.get(0).outcome);
        assertEquals(List.of(new HealOrchestrator.HealedLocatorEntry(
                        "LoginPage.java:1 \"password\" -> \"#password\"", "high", false)),
                results.get(0).healedAndKept,
                "the correct first fix must be kept, not reverted, even though the budget ran out "
                + "before the second locator could be attempted");
        assertTrue(results.get(0).note.contains("#login-button-BROKEN"),
                "the remaining-failure note should mention the newly-unmasked locator; was: " + results.get(0).note);
        assertEquals(
                "private final String passwordInput = \"#password\";\n"
                + "private final String loginButton = \"#login-button-BROKEN\";\n",
                Files.readString(pageFile, StandardCharsets.UTF_8),
                "the kept patch must survive on disk even though the second locator was never resolved");
        verify(locatorHealer, never()).heal(loginButtonFailure);
    }

    @Test
    void runWithSummaryTracksEachHealAttemptWithSequentialNumbers(@TempDir Path tempDir) throws Exception {
        // Same real double-break shape as keepsACorrectPatchWhenRerunFailsAtADifferentLocator
        // AndChasesIt above: fixing checkoutButton unmasks lastNameInput, previously masked.
        // Confirms the "one heal attempt per unit of retriesUsed" behavior directly, not just
        // the per-original-failure Result it produces.
        Path cartPage = tempDir.resolve("CartPage.java");
        Path checkoutStepOnePage = tempDir.resolve("CheckoutStepOnePage.java");
        Files.writeString(cartPage, "private final String checkoutButton = \"[datatest='checkout']\";\n", StandardCharsets.UTF_8);
        Files.writeString(checkoutStepOnePage, "private final String lastNameInput = \"[data-test'lastName']\";\n", StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure originalFailure = locatorFailure("[datatest='checkout']");
        when(reader.readFailures()).thenReturn(List.of(originalFailure));

        TestFailure lastNameFailure = locatorFailure("[data-test'lastName']");

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(originalFailure)).thenReturn(
                healResult("[datatest='checkout']", "[data-test='checkout']", cartPage, 1, "checkoutButton"));
        when(locatorHealer.heal(lastNameFailure)).thenReturn(
                healResult("[data-test'lastName']", "[data-test='lastName']", checkoutStepOnePage, 1, "lastNameInput"));

        HealOrchestrator.ScenarioRerunner rerunner = mock(HealOrchestrator.ScenarioRerunner.class);
        when(rerunner.rerun(anyString())).thenReturn(lastNameFailure, (TestFailure) null);

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, rerunner, false, DEFAULT_MAX_RETRIES);

        HealOrchestrator.RunSummary summary = orchestrator.runWithSummary();

        assertEquals(2, summary.attempts().size());
        assertEquals(1, summary.attempts().get(0).attemptNumber());
        assertTrue(summary.attempts().get(0).succeeded());
        assertEquals("HEALED", summary.attempts().get(0).label());
        assertEquals("CartPage.checkoutButton: \"[datatest='checkout']\" -> \"[data-test='checkout']\"",
                summary.attempts().get(0).description());
        assertEquals(2, summary.attempts().get(1).attemptNumber());
        assertTrue(summary.attempts().get(1).succeeded());
        assertEquals("CheckoutStepOnePage.lastNameInput: \"[data-test'lastName']\" -> \"[data-test='lastName']\"",
                summary.attempts().get(1).description());
        assertEquals(DEFAULT_MAX_RETRIES, summary.maxRetries());
    }

    @Test
    void buildSummaryRendersAttemptsResultAndFilesChanged(@TempDir Path tempDir) throws Exception {
        Path cartPage = tempDir.resolve("CartPage.java");
        Path checkoutStepOnePage = tempDir.resolve("CheckoutStepOnePage.java");
        Files.writeString(cartPage, "private final String checkoutButton = \"[datatest='checkout']\";\n", StandardCharsets.UTF_8);
        Files.writeString(checkoutStepOnePage, "private final String lastNameInput = \"[data-test'lastName']\";\n", StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure originalFailure = locatorFailure("[datatest='checkout']");
        when(reader.readFailures()).thenReturn(List.of(originalFailure));

        TestFailure lastNameFailure = locatorFailure("[data-test'lastName']");

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(originalFailure)).thenReturn(
                healResult("[datatest='checkout']", "[data-test='checkout']", cartPage, 1, "checkoutButton"));
        when(locatorHealer.heal(lastNameFailure)).thenReturn(
                healResult("[data-test'lastName']", "[data-test='lastName']", checkoutStepOnePage, 1, "lastNameInput"));

        HealOrchestrator.ScenarioRerunner rerunner = mock(HealOrchestrator.ScenarioRerunner.class);
        when(rerunner.rerun(anyString())).thenReturn(lastNameFailure, (TestFailure) null);

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, rerunner, false, DEFAULT_MAX_RETRIES);

        String rendered = HealOrchestrator.buildSummary(orchestrator.runWithSummary());

        assertTrue(rendered.contains("HEALER RUN SUMMARY"));
        assertTrue(rendered.contains("Attempt 1/2:"));
        assertTrue(rendered.contains("[HEALED]"));
        assertTrue(rendered.contains("CartPage.checkoutButton: \"[datatest='checkout']\" -> \"[data-test='checkout']\""));
        assertTrue(rendered.contains("Attempt 2/2:"));
        assertTrue(rendered.contains("CheckoutStepOnePage.lastNameInput"));
        assertTrue(rendered.contains("RESULT: 2 of 2 attempts succeeded"),
                "rendered summary was:\n" + rendered);
        assertTrue(rendered.contains("Files changed (uncommitted, please review): CartPage.java, CheckoutStepOnePage.java"),
                "rendered summary was:\n" + rendered);
    }

    @Test
    void buildSummaryTellsUserToRerunWhenBudgetIsReached(@TempDir Path tempDir) throws Exception {
        // Reuses the exact maxRetries=1 fixture from
        // stopsAndReportsWhenRetryBudgetIsExhaustedBeforeFullResolution above.
        Path pageFile = tempDir.resolve("LoginPage.java");
        Files.writeString(pageFile,
                "private final String passwordInput = \"password\";\n"
                + "private final String loginButton = \"#login-button-BROKEN\";\n",
                StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure originalFailure = locatorFailure("password");
        when(reader.readFailures()).thenReturn(List.of(originalFailure));

        TestFailure loginButtonFailure = locatorFailure("#login-button-BROKEN");

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(originalFailure))
                .thenReturn(healResult("password", "#password", pageFile, 1));

        HealOrchestrator.ScenarioRerunner rerunner = name -> loginButtonFailure;

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, rerunner, false, 1);

        String rendered = HealOrchestrator.buildSummary(orchestrator.runWithSummary());

        assertTrue(rendered.contains("RESULT: 1 of 1 attempt succeeded, retry budget (maxRetriesPerScenario=1) reached."),
                "rendered summary was:\n" + rendered);
        assertTrue(rendered.contains("re-run this command again to continue healing further"),
                "rendered summary was:\n" + rendered);
    }

    @Test
    void buildSummaryExplainsWhenEveryFailureWasNotFixable() throws Exception {
        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure notFixable = new TestFailure();
        notFixable.testName = "Some assertion failure";
        notFixable.className = "User Login Flow";
        notFixable.failureType = "org.opentest4j.AssertionFailedError";
        notFixable.failureMessage = "expected: <a> but was: <b>";
        when(reader.readFailures()).thenReturn(List.of(notFixable));

        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, mock(LocatorHealer.class), mock(PageObjectPatcher.class), name -> null, false, DEFAULT_MAX_RETRIES);

        String rendered = HealOrchestrator.buildSummary(orchestrator.runWithSummary());

        assertTrue(rendered.contains("No heal attempts were made"), "rendered summary was:\n" + rendered);
        assertTrue(rendered.contains("NOT_FIXABLE"), "rendered summary was:\n" + rendered);
    }

    @Test
    void healedAttemptSurfacesLowConfidenceAndAmbiguousMatchInSummaryAndResult(@TempDir Path tempDir) throws Exception {
        Path pageFile = tempDir.resolve("LoginPage.java");
        Files.writeString(pageFile, "private final String loginButton = \"#login-button-BROKEN\";\n", StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#login-button-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer.HealResult healResult = healResult("#login-button-BROKEN", "#login-button", pageFile, 1);
        healResult.confidence = "low";
        healResult.matchedElement = "text=\"Login\" [NOT UNIQUE]";
        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, name -> null, false, DEFAULT_MAX_RETRIES);

        HealOrchestrator.RunSummary summary = orchestrator.runWithSummary();

        assertEquals(HealOrchestrator.Outcome.HEALED, summary.results().get(0).outcome);
        assertEquals(List.of(new HealOrchestrator.HealedLocatorEntry(
                        "LoginPage.java:1 \"#login-button-BROKEN\" -> \"#login-button\"", "low", true)),
                summary.results().get(0).healedAndKept);

        String rendered = HealOrchestrator.buildSummary(summary);
        assertTrue(rendered.contains(
                        "LoginPage.java:1: \"#login-button-BROKEN\" -> \"#login-button\" (confidence: low, ambiguous match)"),
                "rendered summary was:\n" + rendered);
    }

    // Real case that surfaced the bug: the fix was correctly built from a [VERIFIED UNIQUE]
    // data-test value, but the SAME element's shared product text also happens to be listed as
    // [NOT UNIQUE] in the same matchedElement string - an unrelated field, not the one the
    // selector was actually based on. The old "does matchedElement contain [NOT UNIQUE]
    // anywhere" check flagged this as ambiguous even though the used value is verified unique.
    @Test
    void ambiguousMatchIsNotFlaggedWhenAnUnrelatedFieldOnTheSameElementIsNotUnique(@TempDir Path tempDir) throws Exception {
        Path pageFile = tempDir.resolve("InventoryPage.java");
        Files.writeString(pageFile,
                "private final String productName = \"#nventory-item-name-BROKEN\";\n", StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#nventory-item-name-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer.HealResult healResult =
                healResult("#nventory-item-name-BROKEN", "[data-test='inventory-item-name']", pageFile, 1);
        healResult.confidence = "high";
        healResult.matchedElement =
                "data-test=inventory-item-name [VERIFIED UNIQUE] text=\"Sauce Labs Backpack\" [NOT UNIQUE]";
        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, name -> null, false, DEFAULT_MAX_RETRIES);

        HealOrchestrator.RunSummary summary = orchestrator.runWithSummary();

        assertEquals(List.of(new HealOrchestrator.HealedLocatorEntry(
                        "InventoryPage.java:1 \"#nventory-item-name-BROKEN\" -> \"[data-test='inventory-item-name']\"",
                        "high", false)),
                summary.results().get(0).healedAndKept);

        String rendered = HealOrchestrator.buildSummary(summary);
        assertTrue(!rendered.contains("ambiguous match"),
                "rendered summary should not flag an ambiguous match:\n" + rendered);
    }

    // Original intent preserved: a fix genuinely built from a [NOT UNIQUE] value - here a
    // text-based selector matched against a text value that IS marked [NOT UNIQUE] - must still
    // surface "(ambiguous match)", even at high confidence (the existing low-confidence case is
    // covered by healedAttemptSurfacesLowConfidenceAndAmbiguousMatchInSummaryAndResult above).
    @Test
    void ambiguousMatchIsFlaggedWhenSelectorItselfIsBuiltFromANotUniqueValue(@TempDir Path tempDir) throws Exception {
        Path pageFile = tempDir.resolve("InventoryPage.java");
        Files.writeString(pageFile,
                "private final String addToCartButton = \"#add-to-cart-BROKEN\";\n", StandardCharsets.UTF_8);

        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#add-to-cart-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer.HealResult healResult =
                healResult("#add-to-cart-BROKEN", "text='Add to cart'", pageFile, 1);
        healResult.confidence = "high";
        healResult.matchedElement = "text=\"Add to cart\" [NOT UNIQUE]";
        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        PageObjectPatcher realPatcher = new PageObjectPatcher();
        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, realPatcher, name -> null, false, DEFAULT_MAX_RETRIES);

        HealOrchestrator.RunSummary summary = orchestrator.runWithSummary();

        assertEquals(List.of(new HealOrchestrator.HealedLocatorEntry(
                        "InventoryPage.java:1 \"#add-to-cart-BROKEN\" -> \"text='Add to cart'\"",
                        "high", true)),
                summary.results().get(0).healedAndKept);

        String rendered = HealOrchestrator.buildSummary(summary);
        assertTrue(rendered.contains("(ambiguous match)"), "rendered summary was:\n" + rendered);
    }

    // testName/className are a real scenario from a real feature file in this repo
    // (playwright-tests/src/test/resources/features/saucedemo/login/login.feature) so the
    // default FeatureFileResolver() the 6-arg HealOrchestrator constructor wires up (which scans
    // the real repo, not a fixture) resolves this failure to a real ScenarioGroup instead of an
    // "unresolved feature."
    private static TestFailure locatorFailure(String brokenLocator) {
        TestFailure failure = new TestFailure();
        failure.testName = "Standard User can login successfully";
        failure.className = "User Login Flow";
        failure.failureType = "com.microsoft.playwright.TimeoutError";
        failure.failureMessage = "Call log:\n- waiting for locator(\"" + brokenLocator + "\")\n";
        failure.domSnapshotFound = true;
        return failure;
    }

    private static LocatorHealer.HealResult healResult(String brokenLocator, String newSelector, Path filePath, int lineNumber) {
        return healResult(brokenLocator, newSelector, filePath, lineNumber, null);
    }

    private static LocatorHealer.HealResult healResult(String brokenLocator, String newSelector, Path filePath,
            int lineNumber, String fieldName) {
        LocatorHealer.HealResult result = new LocatorHealer.HealResult();
        result.brokenLocator = brokenLocator;
        result.newSelector = newSelector;
        result.matchedElement = "id=" + newSelector.replace("#", "");
        result.confidence = "high";
        result.filePath = filePath;
        result.lineNumber = lineNumber;
        result.fieldName = fieldName;
        return result;
    }
}
