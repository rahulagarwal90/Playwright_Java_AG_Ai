package com.ai.reviewer.github;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * This class only figures out one thing: which git commit SHA should a GitHub inline comment
 * be anchored to. It doesn't post comments or resolve file paths — GitHubCommentPoster asks
 * this class for a SHA and uses it.
 */
class CommitShaResolver {

    private static final Logger LOGGER = Logger.getLogger(CommitShaResolver.class.getName());
    private final HttpClient httpClient;

    // Stores the HttpClient used to ask GitHub for the PR's real head commit.
    CommitShaResolver(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // Figures out which commit SHA to anchor comments to, preferring the PR's real head SHA.
    String resolve() throws Exception {
        try {
            return getGitHubPullRequestHeadSha();
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to resolve PR head SHA via GitHub API, falling back to GIT_COMMIT/local HEAD: "
                    + e.getMessage());
        }

        String gitCommit = System.getenv("GIT_COMMIT");
        if (gitCommit != null && !gitCommit.isBlank()) {
            return gitCommit;
        }
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        Process process = pb.start();
        String sha;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            sha = reader.lines().collect(Collectors.joining("\n")).trim();
        }
        process.waitFor();
        if (sha.isBlank()) {
            throw new RuntimeException("Cannot determine current Git commit SHA.");
        }
        return sha;
    }

    // Asks the GitHub API directly for the PR's real head commit SHA.
    private String getGitHubPullRequestHeadSha() throws Exception {
        String repo = GitHubContext.repository();
        String prNumber = GitHubContext.pullRequestNumber();
        String apiBase = GitHubContext.apiBase();
        String token = GitHubContext.token();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch PR details from GitHub: " + response.statusCode() + " " + response.body());
        }

        JsonObject pr = new Gson().fromJson(response.body(), JsonObject.class);
        JsonObject head = (pr != null && pr.has("head")) ? pr.getAsJsonObject("head") : null;
        if (head == null || !head.has("sha")) {
            throw new RuntimeException("GitHub PR response did not include head.sha");
        }
        return head.get("sha").getAsString();
    }
}
