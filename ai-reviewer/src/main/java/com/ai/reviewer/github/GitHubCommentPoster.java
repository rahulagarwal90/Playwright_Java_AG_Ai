package com.ai.reviewer.github;

import com.ai.reviewer.ReviewFinding;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * This class only handles posting review findings back to GitHub as inline PR comments. It
 * asks CommitShaResolver which commit to anchor to and ChangedFilePathResolver which real file
 * path a finding belongs to, then calls the GitHub API to create each comment. It doesn't know
 * anything about Ollama or how findings were parsed — it just takes a list of ReviewFinding
 * objects and posts the FAILED ones.
 */
public class GitHubCommentPoster {

    private static final Logger LOGGER = Logger.getLogger(GitHubCommentPoster.class.getName());
    private final HttpClient httpClient;
    private final CommitShaResolver commitShaResolver;
    private final ChangedFilePathResolver filePathResolver;

    // Wires up the smaller helpers this class delegates to, sharing one HttpClient.
    public GitHubCommentPoster(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.commitShaResolver = new CommitShaResolver(httpClient);
        this.filePathResolver = new ChangedFilePathResolver(httpClient);
    }

    // Posts every FAILED finding as a GitHub inline comment, continuing past individual failures.
    public void postComments(List<ReviewFinding> findings) throws Exception {
        String repo = GitHubContext.repository();
        String prNumber = GitHubContext.pullRequestNumber();
        String apiBase = GitHubContext.apiBase();
        String commitSha = commitShaResolver.resolve();
        List<String> changedFiles = filePathResolver.fetchChangedFiles(repo, prNumber, apiBase);

        int postedCount = 0;
        int failedCount = 0;

        for (ReviewFinding finding : findings) {
            Optional<String> resolvedPath = filePathResolver.resolve(finding.file, changedFiles);
            if (finding.line <= 0 || resolvedPath.isEmpty()) {
                LOGGER.warning(() -> "Skipping invalid review finding for GitHub comment: file="
                        + finding.file + ", line=" + finding.line + ", category=" + finding.category);
                failedCount++;
                continue;
            }

            finding.file = resolvedPath.get();

            try {
                createGitHubPullRequestComment(repo, prNumber, apiBase, commitSha, finding);
                postedCount++;
            } catch (Exception ex) {
                LOGGER.warning(() -> "Failed to post GitHub comment for file="
                        + finding.file + ", line=" + finding.line + ": " + ex.getMessage());
                failedCount++;
            }
        }

        LOGGER.info("GitHub inline comments posting summary: posted=" + postedCount + ", failed=" + failedCount);

        if (postedCount == 0 && !findings.isEmpty()) {
            throw new RuntimeException("Failed to post any GitHub inline comments for FAILED findings.");
        }
    }

    // Calls the GitHub API to create one inline PR comment, anchored to a specific file and line.
    private void createGitHubPullRequestComment(String repo,
                                                String prNumber,
                                                String apiBase,
                                                String commitSha,
                                                ReviewFinding finding) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("body", buildCommentBody(finding));
        payload.addProperty("path", finding.file);
        payload.addProperty("line", finding.line);
        payload.addProperty("side", "RIGHT");
        payload.addProperty("commit_id", commitSha);
        String jsonBody = new Gson().toJson(payload);

        LOGGER.fine(() -> "Posting PR comment to: " + repo + " PR:" + prNumber + " file:" + finding.file + " line:" + finding.line);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s/comments", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + GitHubContext.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        LOGGER.fine(() -> "GitHub response: " + response.statusCode() + " body: " + response.body());

        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new RuntimeException("GitHub PR comment creation failed: " + response.statusCode() + " " + response.body());
        }
    }

    // Builds the comment text GitHub will show on the PR diff for one failed finding.
    private String buildCommentBody(ReviewFinding finding) {
        return String.format("[AI CODE QUALITY GATE] %s FAILURE\nProblem: %s\nSuggested fix:\n%s",
                finding.category,
                finding.problem,
                finding.suggestedFix);
    }
}
