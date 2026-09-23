package com.ai.healer.output;

import com.ai.healer.HealOrchestrator;
import com.ai.healer.HealOrchestrator.GroupOutcome;
import com.ai.healer.HealOrchestrator.Outcome;
import com.ai.healer.HealOrchestrator.PrOutcome;
import com.ai.healer.HealOrchestrator.Result;
import com.ai.healer.HealOrchestrator.RunSummary;
import com.ai.healer.HealOrchestrator.UnresolvedFeature;
import com.ai.healer.RepoRoot;
import com.ai.healer.report.ScenarioGroup;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a single, flat, Jenkins-artifact-friendly JSON file summarizing one HealOrchestrator run
 * - JSON over HTML for simplicity, since this is a POC, not a dashboard. Per feature file: what
 * was healed and kept, and whether a PR was opened for it. {@code NOT_FIXABLE} failures for a
 * group that produced no PR (a group WITH a PR gets those posted as PR comments instead - see
 * {@code NotFixablePrCommenter}). Plus {@code FeatureFileResolver}'s own diagnostics for any
 * Surefire classname that couldn't be matched to a real {@code .feature} file at all.
 */
public final class HealerRunReport {

    private static final Path DEFAULT_RELATIVE_PATH = Path.of("ai-healer", "target", "healer-run-report.json");

    private HealerRunReport() {
    }

    public static Path defaultOutputPath() {
        return RepoRoot.resolve(HealerRunReport.class).resolve(DEFAULT_RELATIVE_PATH);
    }

    public static Path write(RunSummary summary, Map<Path, PrOutcome> prOutcomesByFeaturePath) throws IOException {
        return write(summary, prOutcomesByFeaturePath, defaultOutputPath());
    }

    // Explicit-output-path overload so tests can point this at a temp file instead of the real
    // module's target/ directory.
    public static Path write(RunSummary summary, Map<Path, PrOutcome> prOutcomesByFeaturePath, Path outputPath)
            throws IOException {
        ReportDocument document = buildDocument(summary, prOutcomesByFeaturePath);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, gson.toJson(document), StandardCharsets.UTF_8);
        return outputPath;
    }

    private static ReportDocument buildDocument(RunSummary summary, Map<Path, PrOutcome> prOutcomesByFeaturePath) {
        ReportDocument document = new ReportDocument();
        document.generatedAt = Instant.now().toString();
        document.maxRetriesPerScenario = summary.maxRetries();

        for (GroupOutcome groupOutcome : summary.groupOutcomes()) {
            ScenarioGroup group = groupOutcome.group();
            PrOutcome pr = prOutcomesByFeaturePath.get(group.featureFilePath());

            FeatureGroupEntry entry = new FeatureGroupEntry();
            entry.featureFile = group.featureFilePath().toString();
            entry.prCreated = pr != null;
            entry.pullRequestUrl = pr != null ? pr.pullRequestUrl() : null;
            entry.healedAndKept = healedAndKept(groupOutcome.results());
            // A group WITH a PR already got its NOT_FIXABLE entries posted as PR comments
            // (NotFixablePrCommenter) - reporting them again here would be a duplicate.
            entry.notFixable = pr == null ? notFixableEntries(groupOutcome.results()) : List.of();
            // Unlike NOT_FIXABLE, a HEAL_ERROR is never posted anywhere else (NotFixablePrCommenter
            // only handles NOT_FIXABLE) - so this is the only place it's ever surfaced, regardless
            // of whether the group got a PR. See healErrorEntries()'s own javadoc for why this is a
            // separate list rather than folded into notFixable.
            entry.healErrors = healErrorEntries(groupOutcome.results());
            document.featureGroups.add(entry);
        }

        for (UnresolvedFeature unresolved : summary.unresolvedFeatures()) {
            UnresolvedFeatureEntry entry = new UnresolvedFeatureEntry();
            entry.className = unresolved.className();
            entry.featureFilesScanned = unresolved.featureFilesScanned();
            entry.candidateFeatureFiles = unresolved.allFeatureFilePaths().stream().map(Path::toString).toList();
            entry.unprocessedFailureCount = unresolved.failures().size();
            document.unresolvedFeatures.add(entry);
        }

        // Top-level, unmissable signal of what kind of run this was - see determineRunStatus()'s
        // javadoc. Deliberately computed off summary.results() (every original failure across every
        // group, flat) rather than re-derived from document.featureGroups, since unresolved-feature
        // failures never reach a GroupOutcome/heal attempt at all and must not be counted as errors.
        document.totalFailuresProcessed = summary.results().size();
        document.healErrorCount = (int) summary.results().stream()
                .filter(result -> result.outcome == Outcome.HEAL_ERROR)
                .count();
        document.runStatus = determineRunStatus(summary, document.healErrorCount);

        return document;
    }

    // The one field meant to be read first and alone: whether this run needed no healing at all,
    // healed things with no errors, or ran into HEAL_ERROR (an unexpected exception during a heal
    // attempt - e.g. Ollama unreachable - as opposed to a normal, expected outcome like NOT_FIXABLE
    // or HEAL_FAILED). Without this, a run where every single attempt errored out (Ollama down for
    // the whole run) produces a report whose feature groups all show empty healedAndKept/notFixable
    // - identical, at a glance, to a run where the suite simply passed with nothing to heal. This
    // field exists specifically so that confusion is impossible without reading every entry.
    private static String determineRunStatus(RunSummary summary, int healErrorCount) {
        int total = summary.results().size();
        if (total == 0) {
            return summary.unresolvedFeatures().isEmpty() ? "NOTHING_TO_HEAL" : "NO_FAILURES_HEALED_UNRESOLVED_FEATURES_ONLY";
        }
        if (healErrorCount == total) {
            return "ALL_ATTEMPTS_ERRORED";
        }
        if (healErrorCount > 0) {
            return "PARTIAL_ERRORS";
        }
        return "COMPLETED";
    }

    private static List<HealedEntry> healedAndKept(List<Result> results) {
        List<HealedEntry> all = new ArrayList<>();
        for (Result result : results) {
            for (HealOrchestrator.HealedLocatorEntry healed : result.healedAndKept) {
                HealedEntry entry = new HealedEntry();
                entry.description = healed.description();
                entry.confidence = healed.confidence();
                entry.ambiguousMatch = healed.ambiguousMatch();
                all.add(entry);
            }
        }
        return all;
    }

    private static List<NotFixableEntry> notFixableEntries(List<Result> results) {
        List<NotFixableEntry> entries = new ArrayList<>();
        for (Result result : results) {
            if (result.outcome != Outcome.NOT_FIXABLE) {
                continue;
            }
            NotFixableEntry entry = new NotFixableEntry();
            entry.testName = result.originalFailure.testName;
            entry.failureType = result.originalFailure.failureType;
            entry.failureMessage = result.originalFailure.failureMessage;
            // NOT_FIXABLE is decided before LocatorHealer ever runs, so there is no AI-suggested
            // fix text anywhere on TestFailure/Result for this outcome - result.note (the plain-
            // English reason a human needs to look at it) is the most useful detail that
            // genuinely exists today.
            entry.note = result.note;
            entries.add(entry);
        }
        return entries;
    }

    // A HEAL_ERROR is NOT a NOT_FIXABLE classification - NOT_FIXABLE means FailureClassifier looked
    // at the failure and determined it isn't locator-shaped at all (a normal, expected outcome).
    // HEAL_ERROR means something THREW while attempting to heal a failure FailureClassifier had
    // already said WAS locator-shaped (Ollama unreachable, no DOM snapshot, the re-run subprocess
    // itself failing to launch, ...) - an unexpected failure of the healing machinery, not a
    // judgment about the test failure itself. Conflating the two into notFixableEntries() would
    // make "Ollama was down" look identical to "this is a real app defect, not a broken locator" -
    // exactly the ambiguity this method exists to eliminate. See determineRunStatus() for the
    // corresponding top-level signal.
    private static List<HealErrorEntry> healErrorEntries(List<Result> results) {
        List<HealErrorEntry> entries = new ArrayList<>();
        for (Result result : results) {
            if (result.outcome != Outcome.HEAL_ERROR) {
                continue;
            }
            HealErrorEntry entry = new HealErrorEntry();
            entry.testName = result.originalFailure.testName;
            // The original test failure's own message (e.g. the locator timeout text) - which
            // locator/scenario healing was attempting when it errored, distinct from the error
            // below (why the healing attempt itself failed).
            entry.failureMessage = result.originalFailure.failureMessage;
            // The heal attempt's own exception message (e.g. "Connection refused" for Ollama being
            // unreachable) - set by HealOrchestrator's catch block, unchanged by this task.
            entry.error = result.note;
            entries.add(entry);
        }
        return entries;
    }

    // Plain, flat POJOs for Gson - field names double as the JSON keys. No behavior of their own.
    private static final class ReportDocument {
        String generatedAt;
        int maxRetriesPerScenario;
        // The single field meant to answer "what kind of run was this" at a glance - see
        // determineRunStatus(). healErrorCount/totalFailuresProcessed back it up with the raw
        // counts runStatus was computed from, so a reader doesn't have to recount featureGroups
        // entries (which also exclude unresolved-feature failures entirely) to verify it.
        String runStatus;
        int totalFailuresProcessed;
        int healErrorCount;
        List<FeatureGroupEntry> featureGroups = new ArrayList<>();
        List<UnresolvedFeatureEntry> unresolvedFeatures = new ArrayList<>();
    }

    private static final class FeatureGroupEntry {
        String featureFile;
        boolean prCreated;
        String pullRequestUrl;
        List<HealedEntry> healedAndKept;
        List<NotFixableEntry> notFixable;
        List<HealErrorEntry> healErrors;
    }

    // Same "old" -> "new" description LocatorHealer/HealOrchestrator's summary already reports,
    // plus the two HealResult details (confidence, and whether matchedElement was flagged
    // [NOT UNIQUE] - see HealOrchestrator.HealedLocatorEntry) worth surfacing here too, so a human
    // reviewing this report can spot a heal worth a closer look without re-reading the summary
    // text.
    private static final class HealedEntry {
        String description;
        String confidence;
        boolean ambiguousMatch;
    }

    private static final class NotFixableEntry {
        String testName;
        String failureType;
        String failureMessage;
        String note;
    }

    private static final class HealErrorEntry {
        String testName;
        String failureMessage;
        String error;
    }

    private static final class UnresolvedFeatureEntry {
        String className;
        int featureFilesScanned;
        List<String> candidateFeatureFiles;
        int unprocessedFailureCount;
    }
}
