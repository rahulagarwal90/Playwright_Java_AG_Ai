package com.ai.healer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * The one class in ai-healer that talks to Ollama. Deliberately self-contained rather than
 * reusing ai-reviewer's OllamaConfig/OllamaReviewClient — ai-healer has no dependency on the
 * ai-reviewer module, and whether to share a config layer across modules is parked for later.
 * Model and base URL resolve the same way ai-reviewer's OllamaConfig does (env var, falling back
 * to a hardcoded default), just without the config.properties file layer.
 */
public class HealerOllamaClient {

    private static final String DEFAULT_MODEL = "qwen2.5-coder:14b";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    // Structured-output schema constraining Ollama's answer to exactly what LocatorHealer needs:
    // the replacement locator statement, which candidate element it's based on, and how
    // confident the model is. See https://docs.ollama.com/capabilities/structured-outputs.
    private static final String LOCATOR_RESPONSE_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "newLocatorCode": {"type": "string"},
                "matchedElement": {"type": "string"},
                "confidence": {"type": "string", "enum": ["high", "low"]}
              },
              "required": ["newLocatorCode", "matchedElement", "confidence"]
            }
            """;

    private final HttpClient httpClient;

    public HealerOllamaClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // Resolves the Ollama model to use: OLLAMA_MODEL env var, then the default.
    public static String model() {
        String fromEnv = System.getenv("OLLAMA_MODEL");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : DEFAULT_MODEL;
    }

    // Resolves the Ollama base URL: OLLAMA_BASE_URL env var, then the default.
    public static String baseUrl() {
        String fromEnv = System.getenv("OLLAMA_BASE_URL");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : DEFAULT_BASE_URL;
    }

    // Sends the system/user prompt pair to Ollama's /api/chat endpoint and returns the raw
    // structured-output JSON text it responds with.
    public String suggestLocator(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.0);

        Gson gson = new Gson();
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model());
        payload.addProperty("stream", false);
        payload.add("messages", messages);
        payload.add("format", gson.fromJson(LOCATOR_RESPONSE_SCHEMA_JSON, JsonObject.class));
        payload.add("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Ollama returned non-200 status code: " + response.statusCode() + " - " + response.body());
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        return jsonResponse.getAsJsonObject("message").get("content").getAsString();
    }
}
