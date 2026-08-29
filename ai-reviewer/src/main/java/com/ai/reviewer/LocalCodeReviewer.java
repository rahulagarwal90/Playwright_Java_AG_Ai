package com.ai.reviewer;

import com.ai.reviewer.diff.DiffFetcher;
import com.ai.reviewer.github.GitHubCommentPoster;
import com.ai.reviewer.github.GitHubContext;
import com.ai.reviewer.learning.LearningLoop;
import com.ai.reviewer.ollama.FindingParser;
import com.ai.reviewer.ollama.OllamaReviewClient;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * This is the entry point and the conductor for the whole tool. It doesn't know HOW to fetch a
 * diff, HOW to talk to Ollama, HOW to parse JSON, or HOW to post to GitHub — each of those jobs
 * lives in its own class (DiffFetcher, OllamaReviewClient, FindingParser, GitHubCommentPoster,
 * LearningLoop). This class just calls them in the right order: get the diff, send it to
 * Ollama, parse the findings, decide pass or fail, then either post PR comments or print to
 * the terminal.
 */
public class LocalCodeReviewer {

    private static final Logger LOGGER = Logger.getLogger(LocalCodeReviewer.class.getName());
    private final HttpClient httpClient;
    private final DiffFetcher diffFetcher;
    private final OllamaReviewClient ollamaReviewClient;

    // Wires up real network-backed dependencies for normal command-line use.
    public LocalCodeReviewer() {
        this(HttpClient.newHttpClient());
    }

    // Wires up dependencies from a given HttpClient, so tests can inject a mock instead of the network.
    public LocalCodeReviewer(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.diffFetcher = new DiffFetcher(httpClient);
        this.ollamaReviewClient = new OllamaReviewClient(httpClient);
    }

    // Runs the reviewer from the command line: either "learn" mode, or a normal review.
    public static void main(String[] args) {
        LocalCodeReviewer reviewer = new LocalCodeReviewer();
        int exitCode = 0;
        try {
            if (args.length > 0 && "learn".equalsIgnoreCase(args[0])) {
                new LearningLoop(reviewer.httpClient).run();
            } else {
                String reviewJson = reviewer.runReview().join();
                List<ReviewFinding> findings = FindingParser.parse(reviewJson);
                List<ReviewFinding> failedFindings = findings.stream()
                        .filter(finding -> "FAILED".equalsIgnoreCase(finding.status))
                        .toList();
                if (!failedFindings.isEmpty()) {
                    LOGGER.severe("[ERROR] AI Code Quality Gate detected failures.");
                    // False unless GITHUB_REPOSITORY, a PR number, and GITHUB_TOKEN are all set.
                    if (GitHubContext.isPresent()) {
                        LOGGER.info("[INFO] Posting line-level PR review comments to GitHub.");
                        new GitHubCommentPoster(reviewer.httpClient).postComments(failedFindings);
                    } else {
                        LOGGER.info("[INFO] No PR context detected — findings printed to terminal only, GitHub posting skipped.");
                    }
                    exitCode = 1;
                } else {
                    LOGGER.info("[INFO] AI Code Quality Gate passed. No failed categories detected.");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("[ERROR] Exception during execution: " + e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    // Fetches the diff, sends it to Ollama, and returns the raw JSON findings text.
    public CompletableFuture<String> runReview() {
        try {
            LOGGER.info(">>> Isolating local changes via 'git diff HEAD'...");
            String diffText = getGitDiff();
            String filteredDiff = DiffFetcher.filterDiff(diffText);
            if (filteredDiff.trim().isEmpty()) {
                LOGGER.info("[INFO] No git changes detected.");
                LOGGER.info(">>> Tip: Edit or stage files in git before running the code reviewer.");
                return CompletableFuture.completedFuture("No changes");
            }
            LOGGER.info(">>> Sending changes to local Ollama (model: qwen2.5-coder:14b)...");
            return ollamaReviewClient.sendReview(filteredDiff)
                    .thenApply(reviewJson -> {
                        logFindingsToTerminal(reviewJson);
                        return reviewJson;
                    });
        } catch (Exception e) {
            LOGGER.severe("[ERROR] Failed to execute git diff: " + e.getMessage());
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    // Gets the current diff — kept as its own method (delegating to DiffFetcher) so tests can stub it, same as before.
    public String getGitDiff() throws Exception {
        return diffFetcher.getDiff();
    }

    // Prints the parsed findings back out in the same readable block the terminal has always shown.
    private static void logFindingsToTerminal(String reviewJson) {
        List<ReviewFinding> findings = FindingParser.parse(reviewJson);
        LOGGER.info("\n==================================================");
        LOGGER.info("                AI CODE REVIEW FEEDBACK           ");
        LOGGER.info("==================================================");
        LOGGER.info(FindingParser.formatForDisplay(findings));
        LOGGER.info("==================================================");
    }
}
