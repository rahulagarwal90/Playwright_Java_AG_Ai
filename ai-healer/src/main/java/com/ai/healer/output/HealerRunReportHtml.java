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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Writes a single, static, human-readable HTML view of one HealOrchestrator run, alongside (not
 * instead of) {@link HealerRunReport}'s JSON. Same data, same per-group rules as the JSON report -
 * NOT_FIXABLE entries only for a group with no PR, HEAL_ERROR entries always, the same runStatus
 * values - just rendered as plain HTML/CSS with no JS and no external resources, so the file opens
 * as a Jenkins artifact with nothing else alongside it.
 */
public final class HealerRunReportHtml {

    private static final Path DEFAULT_RELATIVE_PATH = Path.of("ai-healer", "target", "healer-run-report.html");

    private static final String STYLE = """
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                   margin: 24px; color: #1f2328; background: #ffffff; }
            h1 { font-size: 1.5em; margin-bottom: 0.4em; }
            h2 { font-size: 1.15em; margin: 0; word-break: break-all; }
            h3 { font-size: 0.95em; margin: 16px 0 6px; }
            dl.summary { display: grid; grid-template-columns: max-content auto; gap: 4px 16px; }
            dl.summary dt { font-weight: 600; }
            dl.summary dd { margin: 0; }
            section { border: 1px solid #d0d7de; border-radius: 6px; padding: 16px; margin: 16px 0; }
            table { border-collapse: collapse; width: 100%; font-size: 0.9em; }
            th, td { border: 1px solid #d0d7de; padding: 6px 8px; text-align: left; vertical-align: top; }
            th { background: #f6f8fa; }
            td { word-break: break-word; }
            .meta { margin: 8px 0 0; color: #59636e; }
            .empty { color: #59636e; font-style: italic; }
            """;

    private HealerRunReportHtml() {
    }

    public static Path defaultOutputPath() {
        return RepoRoot.resolve(HealerRunReportHtml.class).resolve(DEFAULT_RELATIVE_PATH);
    }

    public static Path write(RunSummary summary, Map<Path, PrOutcome> prOutcomesByFeaturePath) throws IOException {
        return write(summary, prOutcomesByFeaturePath, defaultOutputPath());
    }

    // Explicit-output-path overload so tests can point this at a temp file instead of the real
    // module's target/ directory - same as HealerRunReport's.
    public static Path write(RunSummary summary, Map<Path, PrOutcome> prOutcomesByFeaturePath, Path outputPath)
            throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, render(summary, prOutcomesByFeaturePath), StandardCharsets.UTF_8);
        return outputPath;
    }

    private static String render(RunSummary summary, Map<Path, PrOutcome> prOutcomesByFeaturePath) {
        int totalFailuresProcessed = summary.results().size();
        int healErrorCount = (int) summary.results().stream()
                .filter(result -> result.outcome == Outcome.HEAL_ERROR)
                .count();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Healer Run Report</title>\n<style>\n").append(STYLE).append("</style>\n")
                .append("</head>\n<body>\n<h1>Healer Run Report</h1>\n<dl class=\"summary\">\n");
        summaryRow(html, "Generated at", Instant.now().toString());
        summaryRow(html, "Run status", determineRunStatus(summary, healErrorCount));
        summaryRow(html, "Total failures processed", String.valueOf(totalFailuresProcessed));
        summaryRow(html, "Heal errors", String.valueOf(healErrorCount));
        html.append("</dl>\n");

        for (GroupOutcome groupOutcome : summary.groupOutcomes()) {
            appendFeatureGroup(html, groupOutcome, prOutcomesByFeaturePath);
        }

        if (!summary.unresolvedFeatures().isEmpty()) {
            html.append("<section>\n<h2>Unresolved features</h2>\n<table>\n<tr><th>Class name</th>")
                    .append("<th>Feature files scanned</th><th>Unprocessed failures</th></tr>\n");
            for (UnresolvedFeature unresolved : summary.unresolvedFeatures()) {
                row(html, unresolved.className(), String.valueOf(unresolved.featureFilesScanned()),
                        String.valueOf(unresolved.failures().size()));
            }
            html.append("</table>\n</section>\n");
        }

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static void appendFeatureGroup(StringBuilder html, GroupOutcome groupOutcome,
            Map<Path, PrOutcome> prOutcomesByFeaturePath) {
        ScenarioGroup group = groupOutcome.group();
        PrOutcome pr = prOutcomesByFeaturePath.get(group.featureFilePath());

        html.append("<section>\n<h2>").append(escape(group.featureFilePath().toString())).append("</h2>\n");
        html.append("<p class=\"meta\">Pull request: ");
        if (pr != null) {
            String url = escape(pr.pullRequestUrl());
            html.append("<a href=\"").append(url).append("\">").append(url).append("</a>");
        } else {
            html.append("none");
        }
        // N/A (not "No") when there's no PR at all - same null-vs-false distinction as the JSON
        // report's labelApplied.
        html.append(" &middot; Label applied: ").append(pr == null ? "N/A" : pr.labelApplied() ? "Yes" : "No")
                .append("</p>\n");

        html.append("<h3>Healed and kept</h3>\n");
        List<HealOrchestrator.HealedLocatorEntry> healed = groupOutcome.results().stream()
                .flatMap(result -> result.healedAndKept.stream())
                .toList();
        if (healed.isEmpty()) {
            html.append("<p class=\"empty\">None</p>\n");
        } else {
            html.append("<table>\n<tr><th>Description</th><th>Confidence</th><th>Ambiguous match</th></tr>\n");
            for (HealOrchestrator.HealedLocatorEntry entry : healed) {
                row(html, entry.description(), entry.confidence(), entry.ambiguousMatch() ? "Yes" : "No");
            }
            html.append("</table>\n");
        }

        // A group WITH a PR already got its NOT_FIXABLE entries posted as PR comments
        // (NotFixablePrCommenter) - same exclusion as HealerRunReport.
        List<Result> notFixable = pr == null ? withOutcome(groupOutcome.results(), Outcome.NOT_FIXABLE) : List.of();
        if (!notFixable.isEmpty()) {
            html.append("<h3>Not fixable</h3>\n<table>\n<tr><th>Test name</th><th>Failure type</th><th>Note</th></tr>\n");
            for (Result result : notFixable) {
                row(html, result.originalFailure.testName, result.originalFailure.failureType, result.note);
            }
            html.append("</table>\n");
        }

        List<Result> healErrors = withOutcome(groupOutcome.results(), Outcome.HEAL_ERROR);
        if (!healErrors.isEmpty()) {
            html.append("<h3>Heal errors</h3>\n<table>\n<tr><th>Test name</th><th>Error</th></tr>\n");
            for (Result result : healErrors) {
                row(html, result.originalFailure.testName, result.note);
            }
            html.append("</table>\n");
        }

        html.append("</section>\n");
    }

    // Duplicated from HealerRunReport.determineRunStatus() (private there) rather than shared, so
    // the JSON report's code stays untouched - keep the two in sync if either changes.
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

    private static List<Result> withOutcome(List<Result> results, Outcome outcome) {
        return results.stream().filter(result -> result.outcome == outcome).toList();
    }

    private static void summaryRow(StringBuilder html, String label, String value) {
        html.append("<dt>").append(escape(label)).append("</dt><dd>").append(escape(value)).append("</dd>\n");
    }

    private static void row(StringBuilder html, String... cells) {
        html.append("<tr>");
        for (String cell : cells) {
            html.append("<td>").append(escape(cell)).append("</td>");
        }
        html.append("</tr>\n");
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
