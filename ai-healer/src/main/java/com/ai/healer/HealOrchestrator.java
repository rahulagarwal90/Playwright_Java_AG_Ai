package com.ai.healer;

import com.ai.healer.classify.FailureClassifier;
import com.ai.healer.exec.MavenRunner;
import com.ai.healer.github.HealerGitClient;
import com.ai.healer.github.HealerPullRequestCreator;
import com.ai.healer.github.NotFixablePrCommenter;
import com.ai.healer.ollama.HealerOllamaClient;
import com.ai.healer.ollama.LocatorHealer;
import com.ai.healer.output.HealerRunReport;
import com.ai.healer.output.HealerRunReportHtml;
import com.ai.healer.patch.PageObjectPatcher;
import com.ai.healer.report.FeatureFileResolver;
import com.ai.healer.report.ScenarioGroup;
import com.ai.healer.report.SurefireReportReader;
import com.ai.healer.report.TestFailure;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the whole Healer chain end to end: reads failures (SurefireReportReader), groups them by
 * feature file (FeatureFileResolver/ScenarioGroup), classifies each (FailureClassifier), and for
 * every LOCATOR_FAILURE asks LocatorHealer for a fix, applies it (PageObjectPatcher), and re-runs
 * just that one scenario to verify - reverting only if the re-run still fails at the exact same
 * locator. This now happens the same way in BOTH a local checkout and a GitHub PR pipeline
 * context: what differs between them is what happens to a healed group's changes AFTERWARD. In a
 * local checkout, a kept patch is simply left as an uncommitted change for a human to review and
 * commit themselves - HealOrchestrator never touches git there. In a GitHub PR pipeline context,
 * every ScenarioGroup that healed at least one failure gets its own branch + PR
 * (HealerGitClient/HealerPullRequestCreator), with any NOT_FIXABLE failures in that same group
 * posted as PR comments (NotFixablePrCommenter) instead of sitting only in the run report. Either
 * way, a HealerRunReport JSON file is written at the very end summarizing the whole run.
 *
 * A single scenario can have more than one broken locator, and Cucumber only ever reports the
 * first one it hits - fixing it can unmask a second failure that was previously hidden behind it.
 * Rather than discard a correct fix just because the scenario still fails afterward (on a
 * *different* locator), HealOrchestrator compares the fresh post-re-run failure's locator against
 * the one it just patched: same locator means the fix didn't work and gets reverted; a different
 * locator (or a failure that isn't locator-shaped at all) means the fix was fine and gets kept,
 * and the newly-unmasked failure is chased in turn - up to `ai.healer.maxRetriesPerScenario` heal
 * attempts for THAT scenario (a budget per scenario - see HealerConfig.maxRetriesPerScenario() -
 * not a single pool shared across every failure in the run: each original Surefire failure gets
 * its own fresh budget, whether or not it shares a feature file/ScenarioGroup with others). Once a
 * scenario's own budget is exhausted, that scenario's result records which locators were healed
 * and kept plus a plain-English note on whatever's left, and processing moves on to the next
 * scenario/group - it does not stop the whole run. (An earlier version reverted on *any*
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
        // This scenario's own heal-attempt budget ran out before it fully passed - may still have
        // made partial progress (see Result.healedAndKept).
        MAX_RETRIES_EXCEEDED,
        // FailureClassifier said this isn't a locator problem.
        NOT_FIXABLE,
        // Something threw while healing (Ollama unreachable, no DOM snapshot, re-run couldn't
        // even start, ...).
        HEAL_ERROR
    }

    // One locator fix that got applied and kept - the human-readable "file:line \"old\" ->
    // \"new\"" text plus the LocatorHealer.HealResult detail (confidence, and whether
    // matchedElement was flagged [NOT UNIQUE]) needed to flag a heal worth a closer look, in both
    // the run summary and HealerRunReport's JSON. Carried alongside description rather than
    // requiring a caller to re-derive it from a HealResult that's otherwise discarded once a
    // patch is applied and kept.
    public record HealedLocatorEntry(String description, String confidence, boolean ambiguousMatch) {
    }

    public static class Result {
        public final TestFailure originalFailure;
        public final Outcome outcome;
        // One entry per locator that genuinely got fixed while processing this failure, in the
        // order they were applied and kept.
        public final List<HealedLocatorEntry> healedAndKept;
        // The real source file paths behind healedAndKept, same order, deduplicated - what
        // HealerGitClient stages and HealerRunReport/buildSummary's file list are both built from.
        public final List<Path> changedFiles;
        // Plain-English detail: why a failure remains, why a patch was reverted, or context for
        // an already-passing result. Empty string if there's nothing to add.
        public final String note;

        Result(TestFailure originalFailure, Outcome outcome, List<HealedLocatorEntry> healedAndKept,
                List<Path> changedFiles, String note) {
            this.originalFailure = originalFailure;
            this.outcome = outcome;
            this.healedAndKept = healedAndKept;
            this.changedFiles = changedFiles;
            this.note = note;
        }
    }

    // One recorded heal attempt for the end-of-run human-readable summary. Every "unit" of a
    // scenario's own maxRetriesPerScenario budget that gets consumed - i.e. every call to
    // locatorHealer.heal() inside healWithRetryChain, whether it ultimately succeeds or not -
    // becomes exactly one of these, in the order attempts actually happened. Deliberately flat
    // and separate from Result (which groups outcomes by *original* Surefire failure, not by
    // individual attempt): a scenario with a masked chain of three locators produces one Result
    // but three HealAttempts, and that's exactly the distinction the summary needs to show
    // "Attempt N/maxRetriesPerScenario" correctly.
    public record HealAttempt(int attemptNumber, boolean succeeded, String label, String description) {
    }

    // One .feature file's ScenarioGroup together with the Results/HealAttempts produced while
    // processing every scenario failure inside it - the unit the pipeline-context git/PR wiring
    // and HealerRunReport both key off of.
    public record GroupOutcome(ScenarioGroup group, List<Result> results, List<HealAttempt> attempts) {
    }

    // One Surefire classname (a Cucumber feature name) that FeatureFileResolver could not match
    // to any real .feature file. Carries every failure that would have belonged to this group,
    // plus FeatureFileResolver's own diagnostic detail, so a caller (HealerRunReport) can report
    // the miss without re-scanning anything itself. These failures are never processed - see
    // FeatureFileResolver's javadoc and runWithSummary() below.
    public record UnresolvedFeature(String className, int featureFilesScanned, List<Path> allFeatureFilePaths,
            List<TestFailure> failures) {
    }

    // Pipeline-context-only: the branch/PR HealerGitClient and HealerPullRequestCreator created
    // for one ScenarioGroup's healed changes. Absent (not present in the map runWithSummary()
    // builds) for any group that healed nothing, or that isn't being processed in pipeline
    // context at all (a local checkout never creates one). labelApplied is false when the PR
    // itself was created successfully but HealerPullRequestCreator's labeling step afterward
    // failed - see its own javadoc; a PrOutcome being present at all already means the PR exists.
    public record PrOutcome(String branchName, int pullRequestNumber, String pullRequestUrl, boolean labelApplied) {
    }

    // Bundles what run() already returns (List<Result>) together with the flat attempt log the
    // human-readable summary is built from, the per-group breakdown, and any unresolved-feature
    // diagnostics - plus the maxRetriesPerScenario value the summary reports against. run() itself
    // still returns just List<Result> (unchanged, so existing callers and tests don't need to
    // know this exists) - runWithSummary() is the richer entry point TestRunAndHeal/main() use to
    // print the summary and drive the pipeline-context git/PR wiring.
    public record RunSummary(List<Result> results, List<HealAttempt> attempts, int maxRetries,
            List<GroupOutcome> groupOutcomes, List<UnresolvedFeature> unresolvedFeatures) {
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
    private final FeatureFileResolver featureFileResolver;
    private final HealerGitClient gitClient;
    private final HealerPullRequestCreator pullRequestCreator;
    private final NotFixablePrCommenter notFixablePrCommenter;

    public HealOrchestrator() {
        this(new SurefireReportReader(),
                new LocatorHealer(new HealerOllamaClient(HttpClient.newHttpClient())),
                new PageObjectPatcher(),
                HealOrchestrator::runScenarioViaMaven,
                isPipelineContext(),
                HealerConfig.maxRetriesPerScenario(),
                new FeatureFileResolver(),
                new HealerGitClient(),
                new HealerPullRequestCreator(HttpClient.newHttpClient()),
                new NotFixablePrCommenter(HttpClient.newHttpClient()));
    }

    // Package-private, fully injectable constructor so tests can exercise the branching logic
    // (NOT_FIXABLE handling, revert-vs-keep, per-scenario retry budget) without a real Ollama
    // call, a real file, a real Maven subprocess, or depending on the real config file to test
    // the retry-budget boundary. Defaults to a FeatureFileResolver over the real repo's feature
    // files (this module's tests already run inside the real checkout, and the fixture scenario
    // name used throughout HealOrchestratorTest is a real scenario in a real feature file - see
    // that test's locatorFailure() helper) and to no git/PR collaborators, since none of the
    // existing local-context (pipelineContext=false) tests ever reach that code path.
    HealOrchestrator(SurefireReportReader reportReader, LocatorHealer locatorHealer, PageObjectPatcher patcher,
            ScenarioRerunner rerunner, boolean pipelineContext, int maxRetries) {
        this(reportReader, locatorHealer, patcher, rerunner, pipelineContext, maxRetries,
                new FeatureFileResolver(), null, null, null);
    }

    // Full constructor - additionally takes the feature-file resolver and the pipeline-context
    // git/PR collaborators, for tests that exercise the grouping/wiring logic directly.
    HealOrchestrator(SurefireReportReader reportReader, LocatorHealer locatorHealer, PageObjectPatcher patcher,
            ScenarioRerunner rerunner, boolean pipelineContext, int maxRetries,
            FeatureFileResolver featureFileResolver, HealerGitClient gitClient,
            HealerPullRequestCreator pullRequestCreator, NotFixablePrCommenter notFixablePrCommenter) {
        this.reportReader = reportReader;
        this.locatorHealer = locatorHealer;
        this.patcher = patcher;
        this.rerunner = rerunner;
        this.pipelineContext = pipelineContext;
        this.maxRetries = maxRetries;
        this.featureFileResolver = featureFileResolver;
        this.gitClient = gitClient;
        this.pullRequestCreator = pullRequestCreator;
        this.notFixablePrCommenter = notFixablePrCommenter;
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
            RunSummary empty = new RunSummary(List.of(), List.of(), maxRetries, List.of(), List.of());
            writeRunReport(empty, Map.of());
            return empty;
        }

        // Group by Surefire classname (a Cucumber feature's "Feature:" line) so every scenario
        // failure in the same .feature file can be branched/PR'd together in pipeline context -
        // see FeatureFileResolver/ScenarioGroup. Each scenario inside still gets its OWN retry
        // budget below; grouping only changes what happens to the resulting changes afterward.
        Map<String, List<TestFailure>> failuresByClassName = new LinkedHashMap<>();
        for (TestFailure failure : failures) {
            failuresByClassName.computeIfAbsent(failure.className, key -> new ArrayList<>()).add(failure);
        }

        List<Result> allResults = new ArrayList<>();
        List<HealAttempt> allAttempts = new ArrayList<>();
        List<GroupOutcome> groupOutcomes = new ArrayList<>();
        List<UnresolvedFeature> unresolvedFeatures = new ArrayList<>();

        for (Map.Entry<String, List<TestFailure>> entry : failuresByClassName.entrySet()) {
            String className = entry.getKey();
            List<TestFailure> groupFailures = entry.getValue();

            FeatureFileResolver.Resolution resolution = featureFileResolver.resolve(className);
            if (!resolution.resolved()) {
                unresolvedFeatures.add(new UnresolvedFeature(className, resolution.featureFilesScanned(),
                        resolution.allFeatureFilePaths(), groupFailures));
                LOGGER.warning("[UNRESOLVED_FEATURE] \"" + className + "\" - could not match to a .feature file ("
                        + resolution.featureFilesScanned() + " scanned) - " + groupFailures.size()
                        + " failure(s) not processed.");
                continue;
            }

            ScenarioGroup group = new ScenarioGroup(resolution.filePath(), groupFailures);
            List<Result> groupResults = new ArrayList<>();
            List<HealAttempt> groupAttempts = new ArrayList<>();
            for (TestFailure failure : groupFailures) {
                // Fresh per-scenario budget - see HealerConfig.maxRetriesPerScenario(). Not
                // shared with any other scenario, even one in the same ScenarioGroup.
                AtomicInteger retriesUsed = new AtomicInteger(0);
                groupResults.add(processFailure(failure, retriesUsed, groupAttempts));
            }

            allResults.addAll(groupResults);
            allAttempts.addAll(groupAttempts);
            groupOutcomes.add(new GroupOutcome(group, groupResults, groupAttempts));
        }

        RunSummary summary = new RunSummary(allResults, allAttempts, maxRetries, groupOutcomes, unresolvedFeatures);

        Map<Path, PrOutcome> prOutcomes = pipelineContext ? wireGitAndPullRequests(groupOutcomes) : Map.of();
        writeRunReport(summary, prOutcomes);
        return summary;
    }

    private Result processFailure(TestFailure failure, AtomicInteger retriesUsed, List<HealAttempt> attempts) {
        FailureClassifier.Classification classification = FailureClassifier.classify(failure);
        if (classification == FailureClassifier.Classification.NOT_FIXABLE) {
            LOGGER.warning("[NOT_FIXABLE] \"" + failure.testName + "\" (" + failure.failureType
                    + ") - needs human attention");
            return new Result(failure, Outcome.NOT_FIXABLE, List.of(), List.of(), failure.failureType);
        }

        return healWithRetryChain(failure, retriesUsed, attempts);
    }

    // The core loop: heal the current known failure, patch, re-run, and decide whether to keep
    // or revert based on whether the fresh failure (if any) is at the same locator or a different
    // one. On "different locator," the loop continues with that fresh failure instead of stopping
    // - chasing a chain of previously-masked breaks - until it passes, hits a non-locator failure,
    // or this scenario's own retry budget runs out. Runs identically whether pipelineContext is
    // true or false - what differs between the two contexts is entirely in runWithSummary()'s
    // post-processing (git/PR wiring), not in how a single scenario gets healed.
    private Result healWithRetryChain(TestFailure originalFailure, AtomicInteger retriesUsed, List<HealAttempt> attempts) {
        List<HealedLocatorEntry> healedAndKept = new ArrayList<>();
        List<Path> changedFiles = new ArrayList<>();
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
                    return new Result(originalFailure, Outcome.NOT_FIXABLE, healedAndKept, changedFiles, note);
                }
            }

            if (retriesUsed.get() >= maxRetries) {
                String note = healedAndKept.isEmpty()
                        ? "retry budget (" + maxRetries + ") for this scenario was already exhausted before it "
                            + "could be attempted."
                        : "test still fails after healing " + lastHealed(healedAndKept) + " - the failure has "
                            + describeCurrentFailure(currentFailure) + ", which may indicate the original fix "
                            + "was correct but the test has more than one problem. Ran out of retries (max "
                            + maxRetries + ") before resolving it.";
                LOGGER.warning("[MAX_RETRIES_EXCEEDED] \"" + originalFailure.testName + "\" - " + note);
                return new Result(originalFailure, Outcome.MAX_RETRIES_EXCEEDED, healedAndKept, changedFiles, note);
            }

            // Every heal() call below consumes exactly one unit of this scenario's own
            // maxRetriesPerScenario budget - the increment happens here, before the outcome is
            // known, so the attempt number recorded below always matches retriesUsed at the
            // moment the attempt was spent.
            int attemptNumber = retriesUsed.incrementAndGet();

            LocatorHealer.HealResult healResult;
            try {
                healResult = locatorHealer.heal(currentFailure);
            } catch (Exception e) {
                LOGGER.severe("[HEAL_ERROR] \"" + originalFailure.testName + "\" - "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                attempts.add(new HealAttempt(attemptNumber, false, "HEAL_ERROR",
                        "\"" + currentFailure.testName + "\": " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                return new Result(originalFailure, Outcome.HEAL_ERROR, healedAndKept, changedFiles, e.getMessage());
            }

            if (healResult.filePath == null) {
                String note = "LocatorHealer could not resolve a patchable file/line for this failure";
                LOGGER.warning("[PATCH_REFUSED] \"" + originalFailure.testName + "\" - " + note);
                attempts.add(new HealAttempt(attemptNumber, false, "PATCH_REFUSED",
                        "\"" + healResult.brokenLocator + "\": " + note));
                return new Result(originalFailure, Outcome.PATCH_REFUSED, healedAndKept, changedFiles, note);
            }

            String originalContent;
            try {
                originalContent = Files.readString(healResult.filePath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.severe("[PATCH_REFUSED] \"" + originalFailure.testName + "\" - could not read "
                        + healResult.filePath + ": " + e.getMessage());
                attempts.add(new HealAttempt(attemptNumber, false, "PATCH_REFUSED",
                        healResult.filePath + ": " + e.getMessage()));
                return new Result(originalFailure, Outcome.PATCH_REFUSED, healedAndKept, changedFiles, e.getMessage());
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
                return new Result(originalFailure, Outcome.HEAL_ERROR, healedAndKept, changedFiles, e.getMessage());
            }

            if (freshFailure == null) {
                // Passed.
                if (patchResult.applied) {
                    healedAndKept.add(toHealedEntry(healResult));
                    changedFiles.add(healResult.filePath);
                    LOGGER.info("[HEALED] \"" + originalFailure.testName + "\" - " + describePatch(healResult)
                            + "; re-run passed. Left as an uncommitted change for review.");
                    attempts.add(new HealAttempt(attemptNumber, true, "HEALED",
                            describeForSummary(healResult) + confidenceSuffix(healResult)));
                    return new Result(originalFailure, Outcome.HEALED, healedAndKept, changedFiles, "");
                }
                LOGGER.info("[ALREADY_PASSING] \"" + originalFailure.testName + "\" - " + patchResult.reason
                        + "; re-run already passes without this patch (likely fixed by an earlier "
                        + "failure in this same batch).");
                attempts.add(new HealAttempt(attemptNumber, true, "ALREADY_PASSING",
                        "\"" + currentFailure.testName + "\": " + patchResult.reason));
                return new Result(originalFailure, Outcome.ALREADY_PASSING, healedAndKept, changedFiles, patchResult.reason);
            }

            if (!patchResult.applied) {
                // Refused, and still failing - genuinely nothing this attempt could do.
                // Unless the re-run now fails at a DIFFERENT locator: an earlier scenario in this
                // same run already fixed currentFailure's locator, so chase the new one instead.
                String freshLocator = LocatorHealer.tryExtractBrokenLocator(freshFailure);
                String currentLocator = LocatorHealer.tryExtractBrokenLocator(currentFailure);
                if (freshLocator != null && currentLocator != null && !freshLocator.equals(currentLocator)) {
                    LOGGER.warning("[PATCH_REFUSED] \"" + originalFailure.testName + "\" - " + patchResult.reason
                            + "; but the scenario now fails at a different locator (\"" + currentLocator
                            + "\" -> \"" + freshLocator + "\") - continuing.");
                    attempts.add(new HealAttempt(attemptNumber, false, "PATCH_REFUSED",
                            "\"" + currentFailure.testName + "\": " + patchResult.reason
                                    + "; scenario progressed to new locator \"" + freshLocator + "\""));
                    currentFailure = freshFailure;
                    continue;
                }
                LOGGER.warning("[PATCH_REFUSED] \"" + originalFailure.testName + "\" - " + patchResult.reason);
                attempts.add(new HealAttempt(attemptNumber, false, "PATCH_REFUSED",
                        "\"" + currentFailure.testName + "\": " + patchResult.reason));
                return new Result(originalFailure, Outcome.PATCH_REFUSED, healedAndKept, changedFiles, patchResult.reason);
            }

            String freshLocator = LocatorHealer.tryExtractBrokenLocator(freshFailure);
            boolean sameLocator = healResult.newSelector.equals(freshLocator);

            if (sameLocator) {
                revert(healResult.filePath, originalContent, currentFailure.testName);
                String note = "applied \"" + healResult.newSelector + "\" but the scenario still fails at that "
                        + "exact same locator - the suggested fix did not resolve it. Reverted.";
                LOGGER.warning("[HEAL_FAILED] \"" + originalFailure.testName + "\" - " + note);
                attempts.add(new HealAttempt(attemptNumber, false, "HEAL_FAILED", describeForSummary(healResult) + " (reverted)"));
                return new Result(originalFailure, Outcome.HEAL_FAILED, healedAndKept, changedFiles, note);
            }

            // Different locator (or a fresh failure that isn't locator-shaped at all) - genuine
            // progress. Keep this patch and chase the newly-unmasked failure in turn.
            healedAndKept.add(toHealedEntry(healResult));
            changedFiles.add(healResult.filePath);
            LOGGER.info("[PROGRESS] \"" + originalFailure.testName + "\" - " + describePatch(healResult)
                    + " kept; the scenario's failure has " + describeCurrentFailure(freshFailure) + " - continuing.");
            attempts.add(new HealAttempt(attemptNumber, true, "HEALED",
                    describeForSummary(healResult) + confidenceSuffix(healResult)));
            currentFailure = freshFailure;
        }
    }

    // GitHub-pipeline-context only: for every ScenarioGroup that healed at least one failure
    // (has at least one genuinely changed file), pushes a branch (HealerGitClient), opens a PR
    // against main (HealerPullRequestCreator), and comments on that PR for every NOT_FIXABLE
    // failure encountered while processing the group (NotFixablePrCommenter) - in that order. A
    // group that healed nothing is left alone entirely: nothing to branch or PR, and its
    // NOT_FIXABLE entries (if any) fall through to HealerRunReport instead. A group's own git/PR
    // failure is logged and skipped rather than aborting the rest of the run.
    private Map<Path, PrOutcome> wireGitAndPullRequests(List<GroupOutcome> groupOutcomes) {
        Map<Path, PrOutcome> prOutcomes = new LinkedHashMap<>();
        for (GroupOutcome groupOutcome : groupOutcomes) {
            List<Path> changedFiles = distinctChangedFiles(groupOutcome.results());
            if (changedFiles.isEmpty()) {
                continue;
            }

            ScenarioGroup group = groupOutcome.group();
            String summaryText = buildSummary(
                    new RunSummary(groupOutcome.results(), groupOutcome.attempts(), maxRetries, List.of(), List.of()));

            try {
                HealerGitClient.BranchResult branch =
                        gitClient.commitAndPushHealedGroup(group, changedFiles, summaryText);
                HealerPullRequestCreator.PullRequest pullRequest =
                        pullRequestCreator.createPullRequest(branch.branchName(), group.featureFilePath(), summaryText);

                List<Result> notFixable = groupOutcome.results().stream()
                        .filter(result -> result.outcome == Outcome.NOT_FIXABLE)
                        .toList();
                if (!notFixable.isEmpty()) {
                    notFixablePrCommenter.postNotFixableComments(pullRequest.number(), notFixable);
                }

                prOutcomes.put(group.featureFilePath(),
                        new PrOutcome(branch.branchName(), pullRequest.number(), pullRequest.htmlUrl(),
                                pullRequest.labelApplied()));
                LOGGER.info("[PR_CREATED] " + group.featureFilePath() + " -> " + pullRequest.htmlUrl());
            } catch (Exception e) {
                LOGGER.severe("[GIT_PR_ERROR] " + group.featureFilePath() + " - "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return prOutcomes;
    }

    private void writeRunReport(RunSummary summary, Map<Path, PrOutcome> prOutcomes) {
        try {
            Path written = HealerRunReport.write(summary, prOutcomes);
            LOGGER.info("Healer run report written to " + written);
        } catch (IOException e) {
            LOGGER.severe("Failed to write healer run report: " + e.getMessage());
        }
        // Separate try so an HTML write failure can never affect the JSON report above.
        try {
            Path writtenHtml = HealerRunReportHtml.write(summary, prOutcomes);
            LOGGER.info("Healer HTML run report written to " + writtenHtml);
        } catch (IOException e) {
            LOGGER.severe("Failed to write healer HTML run report: " + e.getMessage());
        }
    }

    private static List<Path> distinctChangedFiles(List<Result> results) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (Result result : results) {
            files.addAll(result.changedFiles);
        }
        return new ArrayList<>(files);
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

    private static String lastHealed(List<HealedLocatorEntry> healedAndKept) {
        return healedAndKept.isEmpty()
                ? "the previous locator" : healedAndKept.get(healedAndKept.size() - 1).description();
    }

    private static HealedLocatorEntry toHealedEntry(LocatorHealer.HealResult healResult) {
        return new HealedLocatorEntry(describePatch(healResult), healResult.confidence, isAmbiguousMatch(healResult));
    }

    // LocatorHealer flags a candidate property (id/data-test/data-testid/text/role/aria) shared by
    // more than one element in the DOM snapshot with a literal "[NOT UNIQUE]" marker inside
    // matchedElement - see its buildUserPrompt/formatCandidates. matchedElement can describe more
    // than one property of the matched candidate at once (the model is told to "note the ambiguity
    // explicitly... whenever it relies on or deliberately avoids a NOT UNIQUE property"), so a bare
    // "does the string contain [NOT UNIQUE] anywhere" check flags a heal as ambiguous even when the
    // marker belongs to a property the fix was never built from - e.g. a fix correctly built from a
    // [VERIFIED UNIQUE] data-test value, where matchedElement also happens to mention the SAME
    // element's shared product text as [NOT UNIQUE] for context. What actually matters is whether
    // the specific value newSelector was built from carries that marker, not whether the word
    // appears anywhere in the sentence.
    private static boolean isAmbiguousMatch(LocatorHealer.HealResult healResult) {
        String matchedElement = healResult.matchedElement;
        if (matchedElement == null) {
            return false;
        }
        String usedValue = extractUsedValue(healResult.newSelector);
        if (usedValue != null) {
            Matcher marker = uniquenessMarkerFor(usedValue).matcher(matchedElement);
            if (marker.find()) {
                // The marker immediately following the exact value newSelector was built from is
                // authoritative - trust it over any other [NOT UNIQUE]/[VERIFIED UNIQUE] mention
                // elsewhere in the string, in either direction.
                return marker.group(1).equals("NOT UNIQUE");
            }
        }
        // Couldn't identify newSelector's own value inside matchedElement (an unrecognized
        // selector shape, e.g. a compound/class selector, or matchedElement phrased it in terms
        // that don't literally echo the value) - fall back to the old blunt check rather than
        // silently treating it as unambiguous.
        return matchedElement.contains("[NOT UNIQUE]");
    }

    // Attribute-selector shapes LocatorHealer's prompt asks Ollama to build newSelector from:
    // [data-test='x'], [data-testid='x'], [aria-label='x'], [role='x'] (quotes optional/either
    // kind).
    private static final Pattern ATTRIBUTE_SELECTOR =
            Pattern.compile("^\\[(?:data-test|data-testid|aria-label|role)=(['\"]?)([^'\"\\]]*)\\1\\]$");
    // A bare id selector, e.g. "#login-button" - deliberately anchored to the whole string so a
    // compound/descendant selector (out of scope here) doesn't get misread as an id-only one.
    private static final Pattern ID_SELECTOR = Pattern.compile("^#([A-Za-z0-9_-]+)$");
    // Playwright's text engine: text=Some Text or text='Some Text' or text="Some Text".
    private static final Pattern TEXT_SELECTOR = Pattern.compile("^text=(['\"]?)(.*)\\1$");
    // Playwright's role engine, e.g. role=button[name="Login"] - only the role value itself
    // (before any [name=...] qualifier) maps to a candidate's role property.
    private static final Pattern ROLE_SELECTOR = Pattern.compile("^role=([A-Za-z0-9_-]+)");

    // Extracts the literal candidate value newSelector was constructed from, so it can be looked
    // up in matchedElement's own text - e.g. "[data-test='checkout']" -> "checkout",
    // "#login-button" -> "login-button", "text='Add to cart'" -> "Add to cart". Returns null for
    // any selector shape not covered by LocatorHealer's documented forms (e.g. ".some-class" or a
    // compound selector) - the caller falls back to the old whole-string check in that case.
    private static String extractUsedValue(String newSelector) {
        if (newSelector == null) {
            return null;
        }
        Matcher attribute = ATTRIBUTE_SELECTOR.matcher(newSelector);
        if (attribute.matches()) {
            return attribute.group(2);
        }
        Matcher id = ID_SELECTOR.matcher(newSelector);
        if (id.matches()) {
            return id.group(1);
        }
        Matcher text = TEXT_SELECTOR.matcher(newSelector);
        if (text.matches()) {
            return text.group(2);
        }
        Matcher role = ROLE_SELECTOR.matcher(newSelector);
        if (role.find()) {
            return role.group(1);
        }
        return null;
    }

    // Matches the given value immediately followed (allowing an optional closing quote, mirroring
    // LocatorHealer.formatCandidates' own "value"/value[ ] shape) by its uniqueness marker, e.g.
    // "inventory-item-name [VERIFIED UNIQUE]" or "\"Add to cart\" [NOT UNIQUE]". Group 1 is the
    // marker word ("NOT UNIQUE" or "VERIFIED UNIQUE").
    private static Pattern uniquenessMarkerFor(String value) {
        return Pattern.compile(Pattern.quote(value) + "['\"]?\\s*\\[(NOT UNIQUE|VERIFIED UNIQUE)\\]");
    }

    // Extra detail appended to a HEALED attempt's summary line only when there's something worth
    // flagging - a non-"high" confidence, an ambiguous ([NOT UNIQUE]) match, or both - so a normal
    // clean heal's line stays exactly as it always has. Deliberately not applied to every
    // describeForSummary() call site (e.g. a reverted HEAL_FAILED attempt): only a kept HEALED
    // result is a "fix a human might want to double-check," per the task this was added for.
    private static String confidenceSuffix(LocatorHealer.HealResult healResult) {
        List<String> details = new ArrayList<>();
        String confidence = healResult.confidence;
        if (confidence != null && !confidence.isBlank() && !confidence.equalsIgnoreCase("high")) {
            details.add("confidence: " + confidence);
        }
        if (isAmbiguousMatch(healResult)) {
            details.add("ambiguous match");
        }
        return details.isEmpty() ? "" : " (" + String.join(", ", details) + ")";
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
        long waitTimeoutMs = Math.max(60_000L, HealerConfig.playwrightTimeoutMs() * 8L);

        int exitCode = MavenRunner.run(repoRoot, buildRerunMavenArgs(scenarioName), waitTimeoutMs);

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

    // Builds the `mvn -pl playwright-tests test` args for runScenarioViaMaven's re-run subprocess
    // - broken out from that method so a test can assert on the exact argument list without
    // spawning a real `mvn`/browser subprocess. Explicitly passes -Dbrowser.headless=<resolved
    // value> (HealerConfig.browserHeadless(), mirroring FrameworkConfig's own
    // system-property/env-var/config-file precedence) rather than leaving it unset: this re-run
    // is a separate `mvn` invocation/JVM from the one that ran the original failing test, so an
    // explicit -Dbrowser.headless passed to THAT original `mvn ... test` command never reaches
    // this process on its own - without this, the re-run always fell back to playwright-tests'
    // own config.properties default (browser.headless=false) regardless of what the original run
    // actually used, launching a visible browser during an otherwise-headless pipeline run.
    static List<String> buildRerunMavenArgs(String scenarioName) {
        String nameRegex = "^\\Q" + scenarioName + "\\E$";
        return List.of(
                "-pl", "playwright-tests", "test",
                "-Dcucumber.filter.name=" + nameRegex,
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dhealer.skipArtifactCleanup=true",
                "-Dbrowser.headless=" + HealerConfig.browserHeadless());
    }

    // Mirrors ai-reviewer's GitHubContext.isPresent() exactly, duplicated rather than depended on:
    // ai-healer is documented as having no dependency on either other module, and reusing a single
    // boolean check isn't worth pulling in a whole module for - same call already made for
    // HealerOllamaClient vs. ai-reviewer's OllamaConfig, and for HealerGitHubConfig's
    // repository()/apiBase() vs. GitHubContext. If a real shared layer ever gets built, this is
    // one of the things that would move into it.
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
    // System.out (not the logger) specifically so it renders cleanly, with no per-line
    // timestamp/class-name prefix. Kept deliberately compact (no decorative separator bars, one
    // line per attempt) since this same text is also used as a PR description - see below.
    // Package-private (not private) so tests can assert on the exact rendered text. Also reused,
    // unmodified, as the exact text of a pipeline-context git commit message and PR body for a
    // single ScenarioGroup (see wireGitAndPullRequests above) - callers
    // there simply pass a RunSummary scoped to just that group's own results/attempts instead of
    // the whole run's.
    static String buildSummary(RunSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("AI Healer Summary (model: ").append(HealerOllamaClient.model()).append(")\n");

        List<HealAttempt> attempts = summary.attempts();
        if (attempts.isEmpty()) {
            sb.append(noAttemptsExplanation(summary.results())).append('\n');
            return sb.toString();
        }

        int succeeded = 0;
        for (HealAttempt attempt : attempts) {
            sb.append(attempt.attemptNumber()).append('/').append(summary.maxRetries())
                    .append(" [").append(attempt.label()).append("] ")
                    .append(attempt.description()).append('\n');
            if (attempt.succeeded()) {
                succeeded++;
            }
        }

        int total = attempts.size();
        // The retry budget is the reason a MAX_RETRIES_EXCEEDED result exists at all - see
        // healWithRetryChain's budget check above, which returns that outcome specifically when
        // retriesUsed has hit this scenario's own maxRetriesPerScenario before it fully resolved.
        // Any other still-broken outcome (NOT_FIXABLE, HEAL_FAILED, PATCH_REFUSED, HEAL_ERROR)
        // needs a human, not a re-run - re-running the exact same command wouldn't change
        // anything about those.
        boolean budgetReached = summary.results().stream().anyMatch(r -> r.outcome == Outcome.MAX_RETRIES_EXCEEDED);
        long needsHuman = summary.results().stream()
                .filter(r -> r.outcome == Outcome.NOT_FIXABLE || r.outcome == Outcome.HEAL_FAILED
                        || r.outcome == Outcome.PATCH_REFUSED || r.outcome == Outcome.HEAL_ERROR)
                .count();

        sb.append("Result: ").append(succeeded).append('/').append(total).append(" attempts succeeded");
        if (budgetReached) {
            sb.append(", retry budget reached - re-run to continue");
        }
        sb.append('.');
        if (needsHuman > 0) {
            sb.append(' ').append(needsHuman).append(" failure(s) need human attention.");
        }
        sb.append('\n');

        List<String> filesChanged = changedFileNames(summary.results());
        sb.append("Files changed: ").append(filesChanged.isEmpty() ? "none" : String.join(", ", filesChanged)).append('\n');
        return sb.toString();
    }

    private static String noAttemptsExplanation(List<Result> results) {
        return "No heal attempts were made - every failure was NOT_FIXABLE (needs human attention; "
                + "see detailed logs above).";
    }

    // Every kept patch's Result.changedFiles entry is the real source Path it was applied to -
    // pulling the distinct set of those out of every result, in the order they were first
    // touched, gives the file list this run actually touched.
    private static List<String> changedFileNames(List<Result> results) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (Result result : results) {
            for (Path path : result.changedFiles) {
                files.add(path.getFileName().toString());
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
            for (HealedLocatorEntry healed : result.healedAndKept) {
                LOGGER.info("      healed+kept: " + healed.description());
            }
            if (result.note != null && !result.note.isBlank()) {
                LOGGER.info("      note: " + result.note);
            }
        }
        for (UnresolvedFeature unresolved : summary.unresolvedFeatures()) {
            LOGGER.warning("  [UNRESOLVED_FEATURE] \"" + unresolved.className() + "\" - "
                    + unresolved.failures().size() + " failure(s) not processed (" + unresolved.featureFilesScanned()
                    + " .feature file(s) scanned, none matched).");
        }

        System.out.println();
        System.out.print(buildSummary(summary));
    }
}
