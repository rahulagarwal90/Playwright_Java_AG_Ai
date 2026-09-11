package com.ai.healer.ollama;

import com.ai.healer.RepoRoot;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The one class in ai-healer that talks to Ollama. Deliberately self-contained rather than
 * reusing ai-reviewer's OllamaConfig/OllamaReviewClient — ai-healer has no dependency on the
 * ai-reviewer module, and whether to share a config layer across modules is parked for later.
 * Model resolves the same way ai-reviewer's OllamaConfig does: ai-healer/config.properties first,
 * then the OLLAMA_MODEL env var, then a hardcoded default — the resolution logic is duplicated
 * here rather than imported, per that same parked sharing decision. Base URL still resolves via
 * env var then default only; nothing so far has needed to override it per-developer.
 */
public class HealerOllamaClient {

    private static final String DEFAULT_MODEL = "qwen2.5-coder:14b";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final Path CONFIG_RELATIVE_PATH = Path.of("ai-healer", "config.properties");

    // Structured-output schema constraining Ollama's answer to exactly what LocatorHealer needs:
    // the replacement bare selector value (not a Java statement - see LocatorHealer's prompt),
    // which candidate element it's based on, and how confident the model is. See
    // https://docs.ollama.com/capabilities/structured-outputs.
    private static final String LOCATOR_RESPONSE_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "newSelector": {"type": "string"},
                "matchedElement": {"type": "string"},
                "confidence": {"type": "string", "enum": ["high", "low"]}
              },
              "required": ["newSelector", "matchedElement", "confidence"]
            }
            """;

    private final HttpClient httpClient;

    public HealerOllamaClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // The resolved model name alongside which of the three tiers actually supplied it - source is
    // one of "ai-healer/config.properties", "env: OLLAMA_MODEL", or "default", used by
    // LocatorHealer's heal-trace logging so a run's output says *why* a given model was used, not
    // just which one.
    record ResolvedModel(String value, String source) {
    }

    // Resolves the Ollama model to use: ai-healer/config.properties, then OLLAMA_MODEL, then the
    // default. Mirrors ai-reviewer's OllamaConfig.model() resolution order exactly.
    public static String model() {
        return resolveModel().value();
    }

    // Package-private: only LocatorHealer's logging needs the source, not model()'s existing
    // public callers.
    static ResolvedModel resolveModel() {
        String fromConfigFile = readFromConfigFile("ollama.model");
        if (fromConfigFile != null) {
            return new ResolvedModel(fromConfigFile, "ai-healer/config.properties");
        }
        String fromEnv = System.getenv("OLLAMA_MODEL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return new ResolvedModel(fromEnv, "env: OLLAMA_MODEL");
        }
        return new ResolvedModel(DEFAULT_MODEL, "default");
    }

    // Resolves the Ollama base URL: OLLAMA_BASE_URL env var, then the default.
    public static String baseUrl() {
        String fromEnv = System.getenv("OLLAMA_BASE_URL");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : DEFAULT_BASE_URL;
    }

    // Reads a single key out of ai-healer/config.properties, or returns null if the file doesn't
    // exist or doesn't set that key. Same read-only, fail-soft shape as HealerConfig's
    // readProperty, kept separate since that one reads playwright-tests' config.properties, not
    // this module's own.
    private static String readFromConfigFile(String propertyKey) {
        Path configPath = RepoRoot.resolve(HealerOllamaClient.class).resolve(CONFIG_RELATIVE_PATH);
        if (!Files.exists(configPath)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return null;
        }
        String value = properties.getProperty(propertyKey);
        return (value != null && !value.isBlank()) ? value.trim() : null;
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
