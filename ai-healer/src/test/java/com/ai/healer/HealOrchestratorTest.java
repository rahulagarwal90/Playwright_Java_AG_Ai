package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void pipelineContextOnlyLogsTheSuggestionAndNeverPatchesOrReruns(@TempDir Path tempDir) throws Exception {
        SurefireReportReader reader = mock(SurefireReportReader.class);
        TestFailure failure = locatorFailure("#login-button-BROKEN");
        when(reader.readFailures()).thenReturn(List.of(failure));

        LocatorHealer locatorHealer = mock(LocatorHealer.class);
        LocatorHealer.HealResult healResult = healResult("#login-button-BROKEN", "#login-button", tempDir.resolve("LoginPage.java"), 13);
        when(locatorHealer.heal(failure)).thenReturn(healResult);

        PageObjectPatcher patcher = mock(PageObjectPatcher.class);
        HealOrchestrator.ScenarioRerunner rerunner = mock(HealOrchestrator.ScenarioRerunner.class);

        HealOrchestrator orchestrator = new HealOrchestrator(
                reader, locatorHealer, patcher, rerunner, true, DEFAULT_MAX_RETRIES);

        List<HealOrchestrator.Result> results = orchestrator.run();

        assertEquals(1, results.size());
        assertEquals(HealOrchestrator.Outcome.SUGGESTION_LOGGED, results.get(0).outcome);
        assertTrue(results.get(0).healedAndKept.isEmpty());
        verify(patcher, never()).patch(any(), anyInt(), any());
        verify(rerunner, never()).rerun(anyString());
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
        assertEquals(List.of("LoginPage.java:1 \"#login-button-BROKEN\" -> \"#login-button\""), results.get(0).healedAndKept);
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
                        "LoginPage.java:1 \"password\" -> \"#password\"",
                        "LoginPage.java:2 \"#login-button-BROKEN\" -> \"#login-button\""),
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
        assertEquals(List.of("LoginPage.java:1 \"password\" -> \"#password\""), results.get(0).healedAndKept,
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

    private static TestFailure locatorFailure(String brokenLocator) {
        TestFailure failure = new TestFailure();
        failure.testName = "Standard User can login successfully";
        failure.failureType = "com.microsoft.playwright.TimeoutError";
        failure.failureMessage = "Call log:\n- waiting for locator(\"" + brokenLocator + "\")\n";
        failure.domSnapshotFound = true;
        return failure;
    }

    private static LocatorHealer.HealResult healResult(String brokenLocator, String newSelector, Path filePath, int lineNumber) {
        LocatorHealer.HealResult result = new LocatorHealer.HealResult();
        result.brokenLocator = brokenLocator;
        result.newSelector = newSelector;
        result.matchedElement = "id=" + newSelector.replace("#", "");
        result.confidence = "high";
        result.filePath = filePath;
        result.lineNumber = lineNumber;
        return result;
    }
}
