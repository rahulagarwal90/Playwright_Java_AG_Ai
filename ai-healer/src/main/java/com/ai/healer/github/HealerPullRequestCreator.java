package com.ai.healer.github;

import com.ai.healer.RepoRoot;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Opens a GitHub PR via the REST API from the branch {@link HealerGitClient} just pushed,
 * targeting {@code main} - mirrors ai-reviewer's {@code GitHubCommentPoster}'s HTTP call style
 * (same header/auth shape, same "throw on a non-2xx response" convention). Creation only - never
 * merges, approves, or closes a PR.
 */
public class HealerPullRequestCreator {

    private static final Path PLAYWRIGHT_TEST_RESOURCES_RELATIVE_PATH =
            Path.of("playwright-tests", "src", "test", "resources");
    private static final String BASE_BRANCH = "main";

    private final HttpClient httpClient;

    public HealerPullRequestCreator(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public record PullRequest(int number, String htmlUrl) {
    }

    // Title includes the feature file's path relative to playwright-tests' resources root (e.g.
    // "features/saucedemo/cart/cart.feature"), so a reviewer can tell at a glance which scenario
    // this PR is healing. Body is exactly the same human-readable summary text
    // HealOrchestrator.buildSummary() already produces for this group - no separate PR-body format.
    public PullRequest createPullRequest(String branchName, Path featureFilePath, String bodySummary)
            throws IOException, InterruptedException {
        String repo = HealerGitHubConfig.repository();
        String apiBase = HealerGitHubConfig.apiBase();
        String token = HealerGitHubConfig.token();

        String title = "AI Healer: fixes for " + relativeFeaturePath(featureFilePath);

        JsonObject payload = new JsonObject();
        payload.addProperty("title", title);
        payload.addProperty("head", branchName);
        payload.addProperty("base", BASE_BRANCH);
        payload.addProperty("body", bodySummary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls", apiBase, repo)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IOException("GitHub PR creation failed: " + response.statusCode() + " " + response.body());
        }

        JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);
        return new PullRequest(json.get("number").getAsInt(), json.get("html_url").getAsString());
    }

    private static String relativeFeaturePath(Path featureFilePath) {
        Path resourcesRoot = RepoRoot.resolve(HealerPullRequestCreator.class).resolve(PLAYWRIGHT_TEST_RESOURCES_RELATIVE_PATH);
        try {
            return resourcesRoot.relativize(featureFilePath).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return featureFilePath.toString();
        }
    }
}
