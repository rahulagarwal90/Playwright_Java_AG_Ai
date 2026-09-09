package com.ai.healer;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Runs the whole Healer chain end to end: reads failures (SurefireReportReader), classifies each
 * (FailureClassifier), and for every LOCATOR_FAILURE asks LocatorHealer for a fix. In a local
 * checkout it goes further - applies the fix (PageObjectPatcher), re-runs just that one scenario
 * to verify, and only reverts the file if the re-run still fails at the SAME locator. In a GitHub
 * PR pipeline context it stops after producing the suggestion: never patches a file or re-runs
 * tests there, matching the "suggestion only" design CI needs. Never touches git, ever - a fix
 * that sticks is left as an uncommitted change for a human to review and commit themselves.
 *
 * A single scenario can have more than one broken locator, and Cucumber only ever reports the
 * first one it hits - fixing it can unmask a second failure that was previously hidden behind it.
 * Rather than discard a correct fix just because the scenario still fails afterward (on a
 * *different* locator), HealOrchestrator compares the fresh post-re-run failure's locator against
 * the one it just patched: same locator means the fix didn't work and gets reverted; a different
 * locator (or a failure that isn't locator-shaped at all) means the fix was fine and gets kept,
 * and the newly-unmasked failure is chased in turn - up to `ai.healer.maxRetries` heal attempts
 * total per `run()` invocation (a global budget across every failure processed, not per-failure).
 * Once that budget is exhausted, healing stops and the result records which locators were healed
 * and kept, and a plain-English note on whatever's left. (An earlier version reverted on *any*
 * still-failing re-run, which meant a second invocation could never make progress on a
 * multi-locator scenario - see ARCHITECTURE_EXPLAINED.md for that real experiment.)
 */
public class HealOrchestrator {

    private static final Logger LOGGER = Logger.getLogger(HealOrchestrator.class.getName());

    public enum Outcome {
        // Fully passes now - one or more patches were applied and kept.
        HEALED,
        // Passes without any new patch this run (fixed by an earlier failure in this batch).
        ALREADY_PASSING,
        // A patch was reverted because the re-run still failed at that exact same locator.
        HEAL_FAILED,
        // LocatorHealer couldn't resolve a patchable file/line, or PageObjectPatcher refused, and
        // the scenario is still failing.
        PATCH_REFUSED,
        // The global heal-attempt budget ran out before this scenario fully passed - may still
        // have made partial progress (see Result.healedAndKept).
        MAX_RETRIES_EXCEEDED,
        // Pipeline context: a suggestion was produced and logged; nothing was applied or re-run.
        SUGGESTION_LOGGED,
        // FailureClassifier said this isn't a locator problem.
        NOT_FIXABLE,
        // Something threw while healing (Ollama unreachable, no DOM snapshot, re-run couldn't
        // even start, ...).
        HEAL_ERROR
    }

    public static class Result {
        public final TestFailure originalFailure;
        public final Outcome outcome;
        // Human-readable "file:line \"old\" -> \"new\"" entries, in the order they were applied
        // and kept, for every locator that genuinely got fixed while processing this failure.
        public final List<String> healedAndKept;
        // Plain-English detail: why a failure remains, why a patch was reverted, or context for
        // an already-passing/suggestion-only result. Empty string if there's nothing to add.
        public final String note;

        Result(TestFailure originalFailure, Outcome outcome, List<String> healedAndKept, String note) {
            this.originalFailure = originalFailure;
            this.outcome = outcome;
            this.healedAndKept = healedAndKept;
            this.note = note;
        }
    }

    // One recorded heal attempt for the end-of-run human-readable summary. Every "unit" of the
    // maxRetries budget that gets consumed - i.e. every call to locatorHealer.heal() inside
    // healWithRetryChain, whether it ultimately succeeds or not - becomes exactly one of these,
    // in the order attempts actually happened. Deliberately flat and separate from Result (which
    // groups outcomes by *original* Surefire failure, not by individual attempt): a scenario with
    // a masked chain of three locators produces one Result but three HealAttempts, and that's
    // exactly the distinction the summary needs to show "Attempt N/maxRetries" correctly.
    public record HealAttempt(int attemptNumber, boolean succeeded, String label, String description) {
    }

    // Bundles what run() already returns (List<Result>) together with the flat attempt log the
    // human-readable summary is built from, plus the maxRetries value the summary reports
    // against. run() itself still returns just List<Result> (unchanged, so existing callers and
    // tests don't need to know this exists) - runWithSummary() is the richer entry point
    // TestRunAndHeal/main() use to print the summary.
    public record RunSummary(List<Result> results, List<HealAttempt> attempts, int maxRetries) {
    }

    // Re-runs exactly one Cucumber scenario by name and reports how it went: null if it passed,
    // or the fresh TestFailure describing how it's still failing. A separate interface (rather
    // than calling the real Maven subprocess directly) so tests can inject a fake instead of
    // actually spawning `mvn` and a real browser.
    @FunctionalInterface
    interface ScenarioRerunner {
        TestFailure rerun(String scenarioName) throws IOException, InterruptedException;
    }

    private final SurefireReportReader reportReader;
    private final LocatorHealer locatorHealer;
    private final PageObjectPatcher patcher;
    private final ScenarioRerunner rerunner;
    private final boolean pipelineContext;
    private final int maxRetries;

    public HealOrchestrator() {
        this(new SurefireReportReader(),
                new LocatorHealer(new HealerOllamaClient(HttpClient.newHttpClient())),
                new PageObjectPatcher(),
                HealOrchestrator::runScenarioViaMaven,
                isPipelineContext(),
                HealerConfig.maxRetries());
    }

    // Package-private, fully injectable constructor so tests can exercise the branching logic
    // (NOT_FIXABLE handling, pipeline vs. local, revert-vs-keep, retry budget) without a real
    // Ollama call, a real file, a real Maven subprocess, or depending on the real config file to
    // test the retry-budget boundary.
    HealOrchestrator(SurefireReportReader reportReader, LocatorHealer locatorHealer, PageObjectPatcher patcher,
            ScenarioRerunner rerunner, boolean pipelineContext, int maxRetries) {
        this.reportReader = reportReader;
        this.locatorHealer = locatorHealer;
        this.patcher = patcher;
        this.rerunner = rerunner;
        this.pipelineContext = pipelineContext;
        this.maxRetries = maxRetries;
    }

    public List<Result> run() throws IOException {
        return runWithSummary().results();
    }

    public RunSummary runWithSummary() throws IOException {
        // SurefireReportReader itself logs a clear, specific reason (missing directory, empty
        // directory, or a stale report from an earlier run) whenever it can't find anything fresh
        // to read - see its own staleness protection. An empty result here can mean any of those,
        // or a genuinely passing test suite; either way, there's nothing to do.
        List<TestFailure> failures = reportReader.readFailures();
        if (failures.isEmpty()) {
            return new RunSummary(List.of(), List.of(), maxRetries);
        }

        AtomicInteger retriesUsed = new AtomicInteger(0);
        List<HealAttempt> attempts = new ArrayList<>();
        List<Result> results = new ArrayList<>();
        for (TestFailure failure : failures) {
            results.add(processFailure(failure, retriesUsed, attempts));
        }
        return new RunSummary(results, attempts, maxRetries);
    }

    private Result processFailure(TestFailure failure, AtomicInteger retriesUsed, List<HealAttempt> attempts) {
        FailureClassifier.Classification classification = FailureClassifier.classify(failure);
        if (classification == FailureClassifier.Classification.NOT_FIXABLE) {
            LOGGER.warning("[NOT_FIXABLE] \"" + failure.testName + "\" (" + failure.failureType
                    + ") - needs human attention");
            return new Result(failure, Outcome.NOT_FIXABLE, List.of(), failure.failureType);
        }

        if (pipelineContext) {
            try {
                LocatorHealer.HealResult healResult = locatorHealer.heal(failure);
                LOGGER.info("[SUGGESTION] \"" + failure.testName + "\" -> " + healResult.filePath + ":"
                        + healResult.lineNumber + " newSelector=" + healResult.newSelector
                        + " confidence=" + healResult.confidence + " matchedElement=" + healResult.matchedElement
                        + " (pipeline context: suggestion only - no file changes, no test re-run)");
                return new Result(failure, Outcome.SUGGESTION_LOGGED, List.of(), healResult.newSelector);
            } catch (Exception e) {
                LOGGER.severe("[HEAL_ERROR] \"" + failure.testName + "\" - "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                return new Result(failure, Outcome.HEAL_ERROR, List.of(), e.getMessage());
            }
        }

        return healWithRetryChain(failure, retriesUsed, attempts);
    }

    // The core loop: heal the current known failure, patch, re-run, and decide whether to keep
    // or revert based on whether the fresh failure (if any) is at the same locator or a different
    // one. On "different locator," the loop continues with that fresh failure instead of stopping
    // - chasing a chain of previously-masked breaks - until it passes, hits a non-locator failure,
    // or the shared retry budget runs out.
    private Result healWithRetryChain(TestFailure originalFailure, AtomicInteger retriesUsed, List<HealAttempt> attempts) {
        List<String> healedAndKept = new ArrayList<>();
        TestFailure currentFailure = originalFailure;

        while (true) {
            if (currentFailure != originalFailure) {
                // A chained failure - re-classify it, since fixing a locator can unmask a
                // completely different, non-locator problem instead of another locator.
                FailureClassifier.Classification classification = FailureClassifier.classify(currentFailure);
                if (classification == FailureClassifier.Classification.NOT_FIXABLE) {
                    String note = "after healing " + lastHealed(healedAndKept) + ", the test now fails for a "
                            + "different, non-locator reason (" + currentFailure.failureType + ") - needs human attention.";
                    LOGGER.warning("[NOT_FIXABLE] \"" + originalFailure.testName + "\" - " + note);
                    return new Result(originalFailure, Outcome.NOT_FIXABLE, healedAndKept, note);
                }
            }

            if (retriesUsed.get() >= maxRetries) {
                String note = healedAndKept.isEmpty()
                        ? "retry budget (" + maxRetries + ") was already exhausted by earlier failures in this "
                            + "batch before this one could be attempted."
                        : "test still fails after healing " + lastHealed(healedAndKept) + " - the failure has "
                            + describeCurrentFailure(currentFailure) + ", which may indicate the original fix "
                            + "was correct but the test has more than one problem. Ran out of retries (max "
                            + maxRetries + ") before resolving it.";
                LOGGER.warning("[MAX_RETRIES_EXCEEDED] \"" + originalFailure.testName + "\" - " + note);
                return new Result(originalFailure, Outcome.MAX_RETRIES_EXCEEDED, healedAndKept, note);
            }

            // Every heal() call below consumes exactly one unit of the shared maxRetries budget -
            // the increment happens here, before the outcome is known, so the attempt number
            // recorded below always matches retriesUsed at the moment the attempt was spent.
            int attemptNumber = retriesUsed.incrementAndGet();

            LocatorHealer.HealResult healResult;
            try {
                healResult = locatorHealer.heal(currentFailure);
            } catch (Exception e) {
                LOGGER.severe("[HEAL_ERROR] \"" + originalFailure.testName + "\" - "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                attempts.add(new HealAttempt(attemptNumber, false, "HEAL_ERROR",
                        "\"" + currentFailure.testName + "\": " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                return new Result(originalFailure, Outcome.HEAL_ERROR, healedAndKept, e.getMessage());
            }

            if (healResult.filePath == null) {
                String note = "LocatorHealer could not resolve a patchable file/line for this failure";
                LOGGER.warning("[PATCH_REFUSED] \"" + originalFailure.testName + "\" - " + note);
                attempts.add(new HealAttempt(attemptNumber, false, "PATCH_REFUSED",
                        "\"" + healResult.brokenLocator + "\": " + note));
                return new Result(originalFailure, Outcome.PATCH_REFUSED, healedAndKept, note);
            }

            String originalContent;
            try {
                originalContent = Files.readString(healResult.filePath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.severe("[PATCH_REFUSED] \"" + originalFailure.testName + "\" - could not read "
                        + healResult.filePath + ": " + e.getMessage());
                attempts.add(new HealAttempt(attemptNumber, false, "PATCH_REFUSED",
                        healResult.filePath + ": " + e.getMessage()));
                return new Result(originalFailure, Outcome.PATCH_REFUSED, healedAndKept, e.getMessage());
            }

            PageObjectPatcher.PatchResult patchResult =
                    patcher.patch(healResult.filePath, healResult.lineNumber, healResult.newSelector);

            TestFailure freshFailure;
            try {
                freshFailure = rerunner.rerun(currentFailure.testName);
            } catch (Exception e) {
                LOGGER.severe("[HEAL_ERROR] \"" + originalFailure.testName + "\" - failed to re-run: " + e.getMessage());
                if (patchResult.applied) {
                    revert(healResult.filePath, originalContent, currentFailure.testName);
                }
                attempts.add(new HealAttempt(attemptNumber, false, "HEAL_ERROR",
                        describeForSummary(healResult) + " (re-run failed to even run: " + e.getMessage() + ")"));
                return new Result(originalFailure, Outcome.HEAL_ERROR, healedAndKept, e.getMessage());
            }

            if (freshFailure == null) {
                // Passed.
                if (patchResult.applied) {
                    healedAndKept.add(describePatch(healResult));
                    LOGGER.info("[HEALED] \"" + originalFailure.testName + "\" - " + describePatch(healResult)
                            + "; re-run passed. Left as an uncommitted change for review.");
                    attempts.add(new HealAttempt(attemptNumber, true, "HEALED", describeForSummary(healResult)));
                    return new Result(originalFailure, Outcome.HEALED, healedAndKept, "");
                }
                LOGGER.info("[ALREADY_PASSING] \"" + originalFailure.testName + "\" - " + patchResult.reason
                        + "; re-run already passes without this patch (likely fixed by an earlier "
                        + "failure in this same batch).");
                attempts.add(new HealAttempt(attemptNumber, true, "ALREADY_PASSING",
                        "\"" + currentFailure.testName + "\": " + patchResult.reason));
                return new Result(originalFailure, Outcome.ALREADY_PASSING, healedAndKept, patchResult.reason);
            }

            if (!patchResult.applied) {
                // Refused, and still failing - genuinely nothing this attempt could do.
                LOGGER.warning("[PATCH_REFUSED] \"" + originalFailure.testName + "\" - " + patchResult.reason);
                attempts.add(new HealAttempt(attemptNumber, false, "PATCH_REFUSED",
                        "\"" + currentFailure.testName + "\": " + patchResult.reason));
                return new Result(originalFailure, Outcome.PATCH_REFUSED, healedAndKept, patchResult.reason);
            }

            String freshLocator = LocatorHealer.tryExtractBrokenLocator(freshFailure);
            boolean sameLocator = healResult.newSelector.equals(freshLocator);

            if (sameLocator) {
                revert(healResult.filePath, originalContent, currentFailure.testName);
                String note = "applied \"" + healResult.newSelector + "\" but the scenario still fails at that "
                        + "exact same locator - the suggested fix did not resolve it. Reverted.";
                LOGGER.warning("[HEAL_FAILED] \"" + originalFailure.testName + "\" - " + note);
                attempts.add(new HealAttempt(attemptNumber, false, "HEAL_FAILED", describeForSummary(healResult) + " (reverted)"));
                return new Result(originalFailure, Outcome.HEAL_FAILED, healedAndKept, note);
            }

            // Different locator (or a fresh failure that isn't locator-shaped at all) - genuine
            // progress. Keep this patch and chase the newly-unmasked failure in turn.
            healedAndKept.add(describePatch(healResult));
            LOGGER.info("[PROGRESS] \"" + originalFailure.testName + "\" - " + describePatch(healResult)
                    + " kept; the scenario's failure has " + describeCurrentFailure(freshFailure) + " - continuing.");
            attempts.add(new HealAttempt(attemptNumber, true, "HEALED", describeForSummary(healResult)));
            currentFailure = freshFailure;
        }
    }

    private static String describePatch(LocatorHealer.HealResult healResult) {
        String location = healResult.filePath != null
                ? healResult.filePath.getFileName() + ":" + healResult.lineNumber
                : "?";
        return location + " \"" + healResult.brokenLocator + "\" -> \"" + healResult.newSelector + "\"";
    }

    // Same information as describePatch, but "ClassName.fieldName" instead of "ClassName.java:N"
    // for the human-readable run summary specifically - falls back to describePatch's file:line
    // shape when the field name couldn't be resolved (e.g. the broken locator's declaration
    // wasn't found and PageObjectPatcher is patching the call-site line instead).
    private static String describeForSummary(LocatorHealer.HealResult healResult) {
        if (healResult.filePath == null) {
            return "\"" + healResult.brokenLocator + "\" -> \"" + healResult.newSelector + "\"";
        }
        String location = healResult.fieldName != null
                ? simpleClassName(healResult.filePath) + "." + healResult.fieldName
                : healResult.filePath.getFileName() + ":" + healResult.lineNumber;
        return location + ": \"" + healResult.brokenLocator + "\" -> \"" + healResult.newSelector + "\"";
    }

    private static String simpleClassName(Path filePath) {
        String fileName = filePath.getFileName().toString();
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private static String describeCurrentFailure(TestFailure failure) {
        String locator = LocatorHealer.tryExtractBrokenLocator(failure);
        return locator != null
                ? "changed to a different locator (" + locator + ")"
                : "changed to a different, non-locator problem (" + failure.failureType + ")";
    }

    private static String lastHealed(List<String> healedAndKept) {
        return healedAndKept.isEmpty() ? "the previous locator" : healedAndKept.get(healedAndKept.size() - 1);
    }

    private void revert(Path filePath, String originalContent, String testName) {
        try {
            Files.writeString(filePath, originalContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.severe("[REVERT FAILED] \"" + testName + "\" - could not restore " + filePath + ": " + e.getMessage());
        }
    }

    // Re-runs exactly one Cucumber scenario via a fresh `mvn -pl playwright-tests test` subprocess,
    // filtered to that scenario's exact name (anchored regex, so one scenario name being a
    // substring of another can't accidentally run both). Passes -Dhealer.skipArtifactCleanup=true
    // so this subprocess's own Hooks doesn't wipe target/dom-snapshots/ - that would destroy the
    // DOM snapshots other not-yet-processed failures in this batch still need. Inherits this
    // process's stdio so the real Maven/Playwright output streams through live, same as running it
    // by hand. Waits up to a generous multiple of playwright.timeout (a safety net against a truly
    // hung subprocess, e.g. a browser that never launches - not a tight budget).
    private static TestFailure runScenarioViaMaven(String scenarioName) throws IOException, InterruptedException {
        Path repoRoot = RepoRoot.resolve(HealOrchestrator.class);
        String nameRegex = "^\\Q" + scenarioName + "\\E$";
        long waitTimeoutMs = Math.max(60_000L, HealerConfig.playwrightTimeoutMs() * 8L);

        int exitCode = MavenRunner.run(repoRoot, List.of(
                "-pl", "playwright-tests", "test",
                "-Dcucumber.filter.name=" + nameRegex,
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dhealer.skipArtifactCleanup=true"),
                waitTimeoutMs);

        if (exitCode == 0) {
            return null;
        }

        SurefireReportReader freshReader = new SurefireReportReader(
                repoRoot.resolve("playwright-tests/target/surefire-reports"),
                repoRoot.resolve("playwright-tests/target/dom-snapshots"));
        for (TestFailure failure : freshReader.readFailures()) {
            if (failure.testName.equals(scenarioName)) {
                return failure;
            }
        }
        TestFailure unknown = new TestFailure();
        unknown.testName = scenarioName;
        unknown.failureType = "unknown";
        unknown.failureMessage = "Re-run exited non-zero, but no matching failure was found in the fresh Surefire report.";
        return unknown;
    }

    // Mirrors ai-reviewer's GitHubContext.isPresent() exactly, duplicated rather than depended on:
    // ai-healer is documented as having no dependency on either other module, and reusing a single
    // boolean check isn't worth pulling in a whole module for - same call already made for
    // HealerOllamaClient vs. ai-reviewer's OllamaConfig. If a real shared layer ever gets built,
    // this is one of the things that would move into it.
    private static boolean isPipelineContext() {
        return notBlank(System.getenv("GITHUB_REPOSITORY"))
                && (notBlank(System.getenv("GITHUB_PR_NUMBER")) || notBlank(System.getenv("CHANGE_ID")))
                && notBlank(System.getenv("GITHUB_TOKEN"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // Builds the end-of-run, human-readable summary block - separate from the detailed per-
    // attempt LOGGER lines main() prints above it, which stay exactly as they were. Printed via
    // System.out (not the logger) specifically so it renders as a clean box, with no per-line
    // timestamp/class-name prefix. Package-private (not private) so tests can assert on the exact
    // rendered text.
    static String buildSummary(RunSummary summary) {
        String bar = "=".repeat(42);
        StringBuilder sb = new StringBuilder();
        sb.append(bar).append('\n');
        sb.append("HEALER RUN SUMMARY").append('\n');
        sb.append(bar).append('\n');
        sb.append("Model: ").append(HealerOllamaClient.model()).append('\n');

        List<HealAttempt> attempts = summary.attempts();
        if (attempts.isEmpty()) {
            sb.append(noAttemptsExplanation(summary.results())).append('\n');
            sb.append(bar).append('\n');
            return sb.toString();
        }

        int succeeded = 0;
        for (HealAttempt attempt : attempts) {
            sb.append("Attempt ").append(attempt.attemptNumber()).append('/').append(summary.maxRetries()).append(":\n");
            String tag = "[" + attempt.label() + "]";
            sb.append("  ").append(tag);
            for (int i = tag.length(); i < 15; i++) {
                sb.append(' ');
            }
            sb.append(' ').append(attempt.description()).append('\n');
            if (attempt.succeeded()) {
                succeeded++;
            }
        }
        sb.append("-".repeat(42)).append('\n');

        int total = attempts.size();
        // The retry budget is the reason a MAX_RETRIES_EXCEEDED result exists at all - see
        // healWithRetryChain's budget check above, which returns that outcome specifically when
        // retriesUsed has hit maxRetries before a scenario fully resolved. Any other still-broken
        // outcome (NOT_FIXABLE, HEAL_FAILED, PATCH_REFUSED, HEAL_ERROR) needs a human, not a
        // re-run - re-running the exact same command wouldn't change anything about those.
        boolean budgetReached = summary.results().stream().anyMatch(r -> r.outcome == Outcome.MAX_RETRIES_EXCEEDED);
        long needsHuman = summary.results().stream()
                .filter(r -> r.outcome == Outcome.NOT_FIXABLE || r.outcome == Outcome.HEAL_FAILED
                        || r.outcome == Outcome.PATCH_REFUSED || r.outcome == Outcome.HEAL_ERROR)
                .count();

        sb.append("RESULT: ").append(succeeded).append(" of ").append(total).append(" attempt")
                .append(total == 1 ? "" : "s").append(" succeeded");
        if (budgetReached) {
            sb.append(", retry budget (maxRetries=").append(summary.maxRetries()).append(") reached.\n");
            sb.append("If failures remain, re-run this command again to continue healing further.\n");
        } else {
            sb.append(".\n");
        }
        if (needsHuman > 0) {
            sb.append(needsHuman).append(" failure(s) need human attention (not fixable by re-running) - "
                    + "see detailed logs above.\n");
        }

        List<String> filesChanged = changedFileNames(summary.results());
        sb.append("Files changed (uncommitted, please review): ")
                .append(filesChanged.isEmpty() ? "none" : String.join(", ", filesChanged)).append('\n');
        sb.append(bar).append('\n');
        return sb.toString();
    }

    private static String noAttemptsExplanation(List<Result> results) {
        boolean allSuggestions = !results.isEmpty()
                && results.stream().allMatch(r -> r.outcome == Outcome.SUGGESTION_LOGGED);
        if (allSuggestions) {
            return "No files changed - pipeline context: suggestion(s) only logged, see detailed logs above.";
        }
        return "No heal attempts were made - every failure was NOT_FIXABLE (needs human attention; "
                + "see detailed logs above).";
    }

    // Every kept patch's describePatch() entry in Result.healedAndKept starts with
    // "fileName.java:lineNumber " - pulling the part before that first colon out of every result
    // gives the distinct set of files this run actually touched, in the order they were first
    // touched, without needing a separate accumulator threaded through healWithRetryChain.
    private static List<String> changedFileNames(List<Result> results) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (Result result : results) {
            for (String healed : result.healedAndKept) {
                int colonIdx = healed.indexOf(':');
                if (colonIdx > 0) {
                    files.add(healed.substring(0, colonIdx));
                }
            }
        }
        return new ArrayList<>(files);
    }

    public static void main(String[] args) throws IOException {
        RunSummary summary = new HealOrchestrator().runWithSummary();
        List<Result> results = summary.results();
        if (results.isEmpty()) {
            // SurefireReportReader already logged exactly why (missing/empty/stale report, or a
            // genuinely passing test suite).
            LOGGER.info("Nothing to heal.");
            return;
        }
        LOGGER.info("HealOrchestrator finished: " + results.size() + " failure(s) processed.");
        for (Result result : results) {
            LOGGER.info("  \"" + result.originalFailure.testName + "\" -> " + result.outcome);
            for (String healed : result.healedAndKept) {
                LOGGER.info("      healed+kept: " + healed);
            }
            if (result.note != null && !result.note.isBlank()) {
                LOGGER.info("      note: " + result.note);
            }
        }

        System.out.println();
        System.out.print(buildSummary(summary));
    }
}
