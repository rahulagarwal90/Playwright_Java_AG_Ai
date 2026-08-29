package com.ai.reviewer.learning;

import com.ai.reviewer.github.GitHubContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * This class only handles the "@ai-learn" learning loop: fetching a PR's comments, keeping
 * only the ones a human tagged with @ai-learn, asking Ollama to compress each into a single
 * rule, and saving the results via RuleStore. It doesn't know anything about the main review
 * flow, findings, or posting review comments.
 */
public class LearningLoop {

    private static final Logger LOGGER = Logger.getLogger(LearningLoop.class.getName());

    // JSON schema for the structured Ollama response: a single {"rule": "<one imperative
    // sentence>"} object — the same structured-output approach OllamaReviewClient uses for the
    // main review call.
    private static final String RULE_RESPONSE_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "rule": {"type": "string"}
              },
              "required": ["rule"]
            }
            """;

    private final HttpClient httpClient;

    // Stores the HttpClient used to call GitHub (for comments) and Ollama (for extraction).
    public LearningLoop(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // Runs the whole learning loop: fetch PR comments, filter to @ai-learn ones, extract rules, save them.
    public void run() throws Exception {
        if (!GitHubContext.isPresent()) {
            return;
        }

        String repo = GitHubContext.repository();
        String prNumber = GitHubContext.pullRequestNumber();
        String apiBase = GitHubContext.apiBase();
        String token = GitHubContext.token();
        String botUsername = System.getenv("GITHUB_BOT_USERNAME");

        JsonArray comments = fetchPullRequestComments(repo, prNumber, apiBase, token);

        RuleStore ruleStore = new RuleStore();
        List<RuleStore.LearnedRule> rules = new ArrayList<>(ruleStore.load());

        int matchedCommentCount = 0;
        int learnedCount = 0;
        int botSkippedCount = 0;
        int failedExtractionCount = 0;
        if (comments != null) {
            for (int i = 0; i < comments.size(); i++) {
                JsonObject comment = comments.get(i).getAsJsonObject();
                String body = comment.has("body") ? comment.get("body").getAsString() : "";

                // Only comments explicitly prefixed with @ai-learn become candidate rules.
                if (body == null || !body.startsWith("@ai-learn")) {
                    continue;
                }
                matchedCommentCount++;

                // Never learn from the bot's own comments — would reinforce whatever mistakes produced them.
                String authorLogin = (comment.has("user") && comment.get("user").isJsonObject())
                        ? comment.getAsJsonObject("user").get("login").getAsString()
                        : "";
                if (botUsername != null && !botUsername.isBlank() && botUsername.equals(authorLogin)) {
                    botSkippedCount++;
                    LOGGER.info("[LEARN] Skipped comment from bot account: " + authorLogin);
                    continue;
                }

                // Isolated per-comment: one failed extraction (e.g. a transient Ollama error)
                // must not discard rules already extracted from earlier comments in this loop.
                String rule;
                try {
                    rule = extractRuleFromComment(body);
                } catch (Exception ex) {
                    failedExtractionCount++;
                    LOGGER.info("[LEARN] Comment: " + body);
                    LOGGER.info("[LEARN] Skipped — rule extraction failed: " + ex.getMessage());
                    continue;
                }
                LOGGER.info("[LEARN] Comment: " + body);
                LOGGER.info("[LEARN] Extracted rule: " + rule);
                learnedCount++;

                RuleStore.LearnedRule learnedRule = new RuleStore.LearnedRule();
                learnedRule.rule = rule;
                learnedRule.sourceComment = body;
                learnedRule.learnedAt = Instant.now().toString();
                rules.add(learnedRule);
            }
        }

        LOGGER.info("[LEARN] Processed " + matchedCommentCount + " @ai-learn comment(s): "
                + learnedCount + " learned, " + (botSkippedCount + failedExtractionCount) + " skipped.");

        // Committed to git — Jenkins only sees a learned rule once this file is committed and merged.
        ruleStore.save(rules);
    }

    // Downloads every comment on the PR — GitHub has no server-side filter for comment text,
    // so everything is fetched here and the @ai-learn filtering happens back in run().
    private JsonArray fetchPullRequestComments(String repo, String prNumber, String apiBase, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s/comments", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch PR comments from GitHub: " + response.statusCode() + " " + response.body());
        }
        return new Gson().fromJson(response.body(), JsonArray.class);
    }

    // Calls Ollama to compress one human comment down into a single imperative rule.
    private String extractRuleFromComment(String commentBody) throws Exception {
        Gson gson = new Gson();

        // Separate, minimal prompt — compression only, not code-quality judgment.
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Convert this code review comment into a single imperative rule, one sentence.");

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", commentBody);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        // Temperature 0 for reproducible extraction; de-duplication itself happens in RuleStore.
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.0);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "qwen2.5-coder:14b");
        payload.addProperty("stream", false);
        payload.add("messages", messages);
        payload.add("format", gson.fromJson(RULE_RESPONSE_SCHEMA_JSON, JsonObject.class));
        payload.add("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama non-200 status code for learn request: " + response.statusCode() + " " + response.body());
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        String content = jsonResponse.getAsJsonObject("message").get("content").getAsString();
        JsonObject ruleResponse = gson.fromJson(content, JsonObject.class);
        return ruleResponse.get("rule").getAsString().trim();
    }
}
