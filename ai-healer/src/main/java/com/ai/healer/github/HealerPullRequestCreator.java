package com.ai.healer.github;

import com.ai.healer.RepoRoot;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Opens a GitHub PR via the REST API from the branch {@link HealerGitClient} just pushed,
 * targeting {@code main} - mirrors ai-reviewer's {@code GitHubCommentPoster}'s HTTP call style
 * (same header/auth shape, same "throw on a non-2xx response" convention). Also labels every PR it
 * creates with {@value #HEAL_LABEL_NAME} via the "Add labels to an issue" endpoint (labels work
 * identically for issues and PRs), creating the label on the repo first if it doesn't already
 * exist (GitHub's add-labels endpoint 422s on a label name that was never created). This label is
 * what {@code Jenkinsfile.ai-reviewer}'s post-action checks - via a live API call at review time,
 * not something baked into the branch at creation time - to decide whether to skip triggering the
 * regression suite for a heal PR, so the decision can never go stale on a branch that forked
 * before a fix to that Jenkinsfile landed on main. Creation only - never merges, approves, or
 * closes a PR.
 *
 * <p>PR creation and labeling are deliberately NOT all-or-nothing: the PR itself
 * ({@link #openPullRequest}) is the thing that matters, and a labeling failure afterward (rate
 * limit, permissions, a transient GitHub error) must never make a real, successfully-created PR
 * look like it doesn't exist. {@link #createPullRequest} therefore only lets an
 * {@link #openPullRequest} failure propagate as an exception; a labeling failure is caught, logged
 * as a WARNing, and reported back via {@link PullRequest#labelApplied()} instead.</p>
 */
public class HealerPullRequestCreator {

    private static final Logger LOGGER = Logger.getLogger(HealerPullRequestCreator.class.getName());

    private static final Path PLAYWRIGHT_TEST_RESOURCES_RELATIVE_PATH =
            Path.of("playwright-tests", "src", "test", "resources");
    private static final String BASE_BRANCH = "main";

    static final String HEAL_LABEL_NAME = "ai-healer-generated";
    private static final String HEAL_LABEL_COLOR = "5319e7";
    private static final String HEAL_LABEL_DESCRIPTION =
            "Opened automatically by ai-healer. Jenkinsfile.ai-reviewer skips triggering the "
                    + "regression suite for PRs carrying this label.";

    private final HttpClient httpClient;

    public HealerPullRequestCreator(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // labelApplied is false on the value openPullRequest() itself returns (labeling hasn't been
    // attempted yet at that point) - createPullRequest() below returns a corrected copy once it
    // knows whether labeling actually succeeded.
    public record PullRequest(int number, String htmlUrl, boolean labelApplied) {
    }

    // Title includes the feature file's path relative to playwright-tests' resources root (e.g.
    // "features/saucedemo/cart/cart.feature"), so a reviewer can tell at a glance which scenario
    // this PR is healing. Body is exactly the same human-readable summary text
    // HealOrchestrator.buildSummary() already produces for this group - no separate PR-body format.
    //
    // Only openPullRequest's own failure can make this method throw - see the class javadoc for
    // why a labeling failure is handled separately instead.
    public PullRequest createPullRequest(String branchName, Path featureFilePath, String bodySummary)
            throws IOException, InterruptedException {
        return createPullRequest(HealerGitHubConfig.repository(), HealerGitHubConfig.apiBase(),
                HealerGitHubConfig.token(), branchName, featureFilePath, bodySummary);
    }

    // Package-private overload with an explicit repo/apiBase/token, same testability rationale as
    // openPullRequest/ensureHealLabelExists/addHealLabel - lets a test exercise the "PR created but
    // labeling failed" behavior without depending on real GitHub env vars.
    PullRequest createPullRequest(String repo, String apiBase, String token, String branchName,
            Path featureFilePath, String bodySummary) throws IOException, InterruptedException {
        PullRequest opened = openPullRequest(repo, apiBase, token, branchName, featureFilePath, bodySummary);

        boolean labelApplied;
        try {
            ensureHealLabelExists(repo, apiBase, token);
            addHealLabel(repo, apiBase, token, opened.number());
            labelApplied = true;
        } catch (IOException e) {
            labelApplied = false;
            LOGGER.warning("[HEAL_LABEL_FAILED] PR #" + opened.number() + " (" + opened.htmlUrl() + ") was "
                    + "created successfully but applying the \"" + HEAL_LABEL_NAME + "\" label failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            labelApplied = false;
            LOGGER.warning("[HEAL_LABEL_FAILED] PR #" + opened.number() + " (" + opened.htmlUrl() + ") was "
                    + "created successfully but applying the \"" + HEAL_LABEL_NAME + "\" label was interrupted: "
                    + e.getMessage());
        }

        return new PullRequest(opened.number(), opened.htmlUrl(), labelApplied);
    }

    // Package-private (rather than folded into createPullRequest) so a test can call it directly
    // with an explicit repo/apiBase/token instead of going through HealerGitHubConfig's real
    // System.getenv() reads - same testability rationale as HealerGitHubConfig.resolveToken(String).
    PullRequest openPullRequest(String repo, String apiBase, String token, String branchName,
            Path featureFilePath, String bodySummary) throws IOException, InterruptedException {
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
        return new PullRequest(json.get("number").getAsInt(), json.get("html_url").getAsString(), false);
    }

    // Idempotent: a GET on /labels/{name} 200s if the label already exists on the repo (the
    // common case after the first heal PR ever created it) and only then does the label get
    // created. Package-private for the same testability reason as openPullRequest above.
    void ensureHealLabelExists(String repo, String apiBase, String token) throws IOException, InterruptedException {
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/labels/%s", apiBase, repo, HEAL_LABEL_NAME)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> getResponse =
                httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (getResponse.statusCode() == 200) {
            return;
        }
        if (getResponse.statusCode() != 404) {
            throw new IOException("GitHub label lookup failed: " + getResponse.statusCode() + " " + getResponse.body());
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("name", HEAL_LABEL_NAME);
        payload.addProperty("color", HEAL_LABEL_COLOR);
        payload.addProperty("description", HEAL_LABEL_DESCRIPTION);

        HttpRequest createRequest = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/labels", apiBase, repo)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> createResponse =
                httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (createResponse.statusCode() < 200 || createResponse.statusCode() > 299) {
            throw new IOException("GitHub label creation failed: " + createResponse.statusCode() + " " + createResponse.body());
        }
    }

    // Package-private for the same testability reason as openPullRequest/ensureHealLabelExists.
    void addHealLabel(String repo, String apiBase, String token, int pullRequestNumber)
            throws IOException, InterruptedException {
        JsonArray labels = new JsonArray();
        labels.add(HEAL_LABEL_NAME);
        JsonObject payload = new JsonObject();
        payload.add("labels", labels);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/issues/%d/labels", apiBase, repo, pullRequestNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IOException("GitHub label assignment failed: " + response.statusCode() + " " + response.body());
        }
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
