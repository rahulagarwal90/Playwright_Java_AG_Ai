package com.ai.reviewer.diff;

import com.ai.reviewer.github.GitHubContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * This class only knows how to get a diff of the changes that need reviewing — either the
 * local git diff on this machine, or a pull request's diff fetched from GitHub. It doesn't
 * know anything about Ollama, parsing findings, or posting comments.
 */
public class DiffFetcher {

    private final HttpClient httpClient;

    // Stores the HttpClient used to fetch a PR diff from GitHub when running in CI.
    public DiffFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // Returns the diff to review: from GitHub if we're running against a PR, otherwise local git.
    public String getDiff() throws Exception {
        if (GitHubContext.isPresent()) {
            return getPullRequestDiffFromGitHub();
        }
        return getLocalGitDiff();
    }

    // Runs `git diff HEAD` locally, skipping the reviewer's own source file and its prompt file.
    private String getLocalGitDiff() throws Exception {
        // Natively targets both unstaged and staged changes in a single raw stream
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD", "--", ".",
                ":!**/LocalCodeReviewer.java", ":!**/system-prompt.md");
        Process process = pb.start();
        String diffText;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            diffText = reader.lines().collect(Collectors.joining("\n"));
        }
        process.waitFor();
        return diffText;
    }

    // Downloads the full diff for the current pull request directly from the GitHub API.
    private String getPullRequestDiffFromGitHub() throws Exception {
        String repo = GitHubContext.repository();
        String prNumber = GitHubContext.pullRequestNumber();
        String apiBase = GitHubContext.apiBase();
        String token = GitHubContext.token();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3.diff")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch PR diff from GitHub: " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    // Drops diff blocks that have no real added/removed lines, keeping only actual changes.
    public static String filterDiff(String rawDiff) {
        if (rawDiff == null || rawDiff.trim().isEmpty()) {
            return "";
        }
        String[] blocks = rawDiff.split("(?=diff --git )");
        StringBuilder filtered = new StringBuilder();
        for (String block : blocks) {
            if (block.trim().isEmpty()) {
                continue;
            }
            boolean hasChanges = false;
            String[] lines = block.split("\n");
            for (String line : lines) {
                // Skip diff file headers (+++ / ---); only real +/- content lines count.
                if ((line.startsWith("+") && !line.startsWith("+++")) ||
                        (line.startsWith("-") && !line.startsWith("---"))) {
                    hasChanges = true;
                    break;
                }
            }
            if (hasChanges) {
                filtered.append(block);
            }
        }
        return filtered.toString();
    }
}
