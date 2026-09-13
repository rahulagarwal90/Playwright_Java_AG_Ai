package com.ai.healer.github;

import com.ai.healer.HealOrchestrator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * For a {@code ScenarioGroup} that resulted in a PR being created, posts one PR comment per
 * {@code NOT_FIXABLE} failure encountered while processing that group - so a human reviewing the
 * PR sees every failure in that feature file the Healer couldn't touch, not just the ones it
 * fixed. Only {@code HealOrchestrator.Result} carries anything today for a {@code NOT_FIXABLE}
 * outcome (it's classified NOT_FIXABLE before LocatorHealer ever runs, so there is no AI-suggested
 * fix text to include - {@code result.note} is the most specific detail actually available, and
 * this deliberately doesn't invent a new field to hold something that was never produced). A
 * group that produced no PR posts no comments here at all - see {@code HealerRunReport} instead.
 */
public class NotFixablePrCommenter {

    private final HttpClient httpClient;

    public NotFixablePrCommenter(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void postNotFixableComments(int pullRequestNumber, List<HealOrchestrator.Result> notFixableResults)
            throws IOException, InterruptedException {
        String repo = HealerGitHubConfig.repository();
        String apiBase = HealerGitHubConfig.apiBase();
        String token = HealerGitHubConfig.token();

        for (HealOrchestrator.Result result : notFixableResults) {
            postIssueComment(repo, apiBase, token, pullRequestNumber, buildCommentBody(result));
        }
    }

    private void postIssueComment(String repo, String apiBase, String token, int pullRequestNumber, String body)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("body", body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/issues/%d/comments", apiBase, repo, pullRequestNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IOException("GitHub PR comment creation failed: " + response.statusCode() + " " + response.body());
        }
    }

    private static String buildCommentBody(HealOrchestrator.Result result) {
        return String.format("[AI HEALER] NOT_FIXABLE\nScenario: %s\nFailure type: %s\nDetail: %s",
                result.originalFailure.testName,
                result.originalFailure.failureType,
                result.note == null || result.note.isBlank() ? "(no further detail available)" : result.note);
    }
}
