package com.ai.reviewer.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This class only knows how to read GitHub pull-request identity out of environment
 * variables (repo name, PR number, API token, API base URL). It doesn't talk to GitHub,
 * Ollama, or git itself — it just answers "are we running against a real PR, and if so,
 * which one?" so every other class that needs GitHub details can ask this one instead of
 * reading environment variables itself.
 */
public class GitHubContext {

    // Says whether a repo, a PR number, and a token are all present in the environment.
    public static boolean isPresent() {
        return Optional.ofNullable(System.getenv("GITHUB_REPOSITORY")).filter(s -> !s.isBlank()).isPresent()
                && (Optional.ofNullable(System.getenv("GITHUB_PR_NUMBER")).filter(s -> !s.isBlank()).isPresent()
                || Optional.ofNullable(System.getenv("CHANGE_ID")).filter(s -> !s.isBlank()).isPresent())
                && Optional.ofNullable(System.getenv("GITHUB_TOKEN")).filter(s -> !s.isBlank()).isPresent();
    }

    // Resolves "owner/repo" from GITHUB_REPOSITORY, or parses it out of Jenkins' CHANGE_URL.
    public static String repository() {
        String repository = System.getenv("GITHUB_REPOSITORY");
        if (repository != null && !repository.isBlank()) {
            return repository;
        }
        String changeUrl = System.getenv("CHANGE_URL");
        if (changeUrl != null && !changeUrl.isBlank()) {
            try {
                URI uri = URI.create(changeUrl);
                String path = uri.getPath(); // e.g. /owner/repo/pull/123
                String[] segments = path.split("/");
                List<String> cleaned = new ArrayList<>();
                for (String s : segments) {
                    if (s != null && !s.isBlank()) cleaned.add(s);
                }
                if (cleaned.size() >= 2) {
                    // owner = cleaned[0], repo = cleaned[1]
                    return cleaned.get(0) + "/" + cleaned.get(1);
                }
            } catch (Exception e) {
                // fall through to error below
            }
        }
        throw new IllegalStateException("Missing required GitHub repository context: GITHUB_REPOSITORY or CHANGE_URL");
    }

    // Resolves the PR number from GITHUB_PR_NUMBER, or falls back to Jenkins' CHANGE_ID.
    public static String pullRequestNumber() {
        String prNumber = System.getenv("GITHUB_PR_NUMBER");
        if (prNumber != null && !prNumber.isBlank()) {
            return prNumber;
        }
        prNumber = System.getenv("CHANGE_ID");
        if (prNumber != null && !prNumber.isBlank()) {
            return prNumber;
        }
        throw new IllegalStateException("Missing required GitHub PR number context: GITHUB_PR_NUMBER or CHANGE_ID");
    }

    // Returns the GitHub API token used to authenticate every GitHub call.
    public static String token() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing required GitHub token: GITHUB_TOKEN");
        }
        return token;
    }

    // Returns the GitHub API base URL, defaulting to the public github.com API.
    public static String apiBase() {
        return Optional.ofNullable(System.getenv("GITHUB_API_URL")).filter(s -> !s.isBlank()).orElse("https://api.github.com");
    }
}
