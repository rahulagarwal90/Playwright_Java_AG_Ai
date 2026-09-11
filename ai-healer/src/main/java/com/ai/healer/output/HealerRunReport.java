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

        return document;
    }

    private static List<String> healedAndKept(List<Result> results) {
        List<String> all = new ArrayList<>();
        for (Result result : results) {
            all.addAll(result.healedAndKept);
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

    // Plain, flat POJOs for Gson - field names double as the JSON keys. No behavior of their own.
    private static final class ReportDocument {
        String generatedAt;
        int maxRetriesPerScenario;
        List<FeatureGroupEntry> featureGroups = new ArrayList<>();
        List<UnresolvedFeatureEntry> unresolvedFeatures = new ArrayList<>();
    }

    private static final class FeatureGroupEntry {
        String featureFile;
        boolean prCreated;
        String pullRequestUrl;
        List<String> healedAndKept;
        List<NotFixableEntry> notFixable;
    }

    private static final class NotFixableEntry {
        String testName;
        String failureType;
        String failureMessage;
        String note;
    }

    private static final class UnresolvedFeatureEntry {
        String className;
        int featureFilesScanned;
        List<String> candidateFeatureFiles;
        int unprocessedFailureCount;
    }
}
