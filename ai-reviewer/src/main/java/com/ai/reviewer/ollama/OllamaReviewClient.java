package com.ai.reviewer.ollama;

import com.ai.reviewer.diff.DiffLineAnnotator;
import com.ai.reviewer.learning.RuleStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * This class only handles talking to Ollama for the main code review. It builds the prompt
 * (the fixed review instructions from system-prompt.md, plus any learned rules, plus the
 * diff), sends it to Ollama's /api/chat endpoint with a JSON schema that constrains the
 * answer to a findings list, and hands back the raw JSON text it got back. It doesn't know
 * anything about GitHub, and it doesn't turn that JSON into ReviewFinding objects — that's
 * FindingParser's job.
 */
public class OllamaReviewClient {

    private static final Logger LOGGER = Logger.getLogger(OllamaReviewClient.class.getName());

    // JSON schema for the structured Ollama response: {"findings": [{category, status, file,
    // line, problem, suggestedFix}, ...]}, one entry per category assessed. Passed via the
    // "format" field on /api/chat so Ollama constrains decoding to this exact shape instead of
    // relying on the model to follow a free-text template — see
    // https://docs.ollama.com/capabilities/structured-outputs.
    private static final String FINDINGS_RESPONSE_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "findings": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "category": {
                        "type": "string",
                        "enum": ["Playwright Web Assertions", "Locator Robustness", "Hardcoded Configurations", "Logging", "Naming Conventions", "Code Style"]
                      },
                      "status": {"type": "string", "enum": ["FAILED", "PASSED"]},
                      "file": {"type": "string"},
                      "line": {"type": "integer"},
                      "problem": {"type": "string"},
                      "suggestedFix": {"type": "string"}
                    },
                    "required": ["category", "status", "file", "line", "problem", "suggestedFix"]
                  }
                }
              },
              "required": ["findings"]
            }
            """;

    private final HttpClient httpClient;

    // Stores the HttpClient used to call the local Ollama service.
    public OllamaReviewClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // Sends the diff to Ollama and returns the raw JSON findings text it responds with.
    public CompletableFuture<String> sendReview(String diffText) {
        String annotatedDiff = DiffLineAnnotator.annotate(diffText);
        String systemPrompt = buildSystemPrompt();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", annotatedDiff);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.0);
        options.addProperty("top_p", 0.1);
        options.addProperty("num_ctx", 16384);

        Gson gson = new Gson();
        JsonObject payload = new JsonObject();
        payload.addProperty("model", OllamaConfig.model());
        payload.addProperty("stream", false);
        payload.add("messages", messages);
        payload.add("format", gson.fromJson(FINDINGS_RESPONSE_SCHEMA_JSON, JsonObject.class));
        payload.add("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.severe("\n[ERROR] Failed to connect to local Ollama service.");
                        LOGGER.info(
                                "[HELP] Please ensure Ollama is installed and running via: ollama run " + OllamaConfig.model());
                        LOGGER.info("[HELP] Ensure the Ollama port is accessible at: http://localhost:11434");
                        throw new RuntimeException("Ollama connection failed", throwable);
                    }
                    if (response.statusCode() != 200) {
                        LOGGER.severe("\n[ERROR] Ollama returned non-200 status code: " + response.statusCode());
                        LOGGER.severe("Response body: " + response.body());
                        throw new RuntimeException("Ollama non-200 status code: " + response.statusCode());
                    }
                    JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                    return jsonResponse.getAsJsonObject("message").get("content").getAsString();
                });
    }

    // Loads the fixed review instructions and splices in any learned rules before returning them.
    private String buildSystemPrompt() {
        String systemPrompt = loadSystemPromptTemplate();

        // Loads any rules committed to ai-reviewer/learned-rules.json so far (see RuleStore).
        List<RuleStore.LearnedRule> learnedRules = new RuleStore().load();
        if (!learnedRules.isEmpty()) {
            // Insert learned rules right before "OUTPUT FORMAT:" so they read as extra review
            // criteria rather than output-formatting instructions.
            StringBuilder learnedRulesSection = new StringBuilder("LEARNED RULES:\n");
            for (RuleStore.LearnedRule learnedRule : learnedRules) {
                learnedRulesSection.append("- ").append(learnedRule.rule).append("\n");
            }
            learnedRulesSection.append("\n");
            systemPrompt = systemPrompt.replace("OUTPUT FORMAT:", learnedRulesSection + "OUTPUT FORMAT:");
        }
        return systemPrompt;
    }

    // Reads the review instructions template out of the packaged system-prompt.md resource file.
    private static String loadSystemPromptTemplate() {
        try (InputStream in = OllamaReviewClient.class.getResourceAsStream("/system-prompt.md")) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: /system-prompt.md");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load system prompt template", e);
        }
    }
}
