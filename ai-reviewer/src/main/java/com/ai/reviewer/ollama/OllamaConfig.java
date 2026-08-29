package com.ai.reviewer.ollama;

import com.ai.reviewer.ModuleRoot;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * This class only knows how to resolve ai-reviewer's tunable settings — which Ollama model to
 * call, its base URL and context window size, and the reviewer bot's GitHub username. Each
 * setting is checked in the same order: the developer's local ai-reviewer/config.properties
 * first, then the matching environment variable (what Jenkins/CI sets, since there's no file to
 * edit there), then a hardcoded default. Every class that needs one of these settings asks this
 * one place instead of resolving it themselves.
 */
public class OllamaConfig {

    private static final String DEFAULT_MODEL = "qwen2.5-coder:14b";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final int DEFAULT_NUM_CTX = 16384;

    // Resolves the Ollama model to use: config.properties, then OLLAMA_MODEL, then the default.
    public static String model() {
        return resolve("ollama.model", "OLLAMA_MODEL", DEFAULT_MODEL);
    }

    // Resolves the Ollama base URL: config.properties, then OLLAMA_BASE_URL, then the default.
    public static String baseUrl() {
        return resolve("ollama.baseUrl", "OLLAMA_BASE_URL", DEFAULT_BASE_URL);
    }

    // Resolves the model's context window size: config.properties, then OLLAMA_NUM_CTX, then the default.
    public static int numCtx() {
        String value = resolve("ollama.numCtx", "OLLAMA_NUM_CTX", null);
        if (value == null) {
            return DEFAULT_NUM_CTX;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "ollama.numCtx / OLLAMA_NUM_CTX must be a whole number, got: \"" + value + "\"");
        }
    }

    // Resolves the reviewer bot's own GitHub login: config.properties, then GITHUB_BOT_USERNAME.
    // No default — the learning loop's self-filter needs this explicitly set to work correctly.
    public static String githubBotUsername() {
        return resolve("github.botUsername", "GITHUB_BOT_USERNAME", null);
    }

    // Checks config.properties, then the environment variable, then falls back to defaultValue.
    private static String resolve(String propertyKey, String envVarName, String defaultValue) {
        String fromConfigFile = readFromConfigFile(propertyKey);
        if (fromConfigFile != null) {
            return fromConfigFile;
        }
        String fromEnv = System.getenv(envVarName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return defaultValue;
    }

    // Reads a single key out of ai-reviewer/config.properties, or returns null if the file
    // doesn't exist or doesn't set that key.
    private static String readFromConfigFile(String propertyKey) {
        Path configPath = ModuleRoot.resolve(OllamaConfig.class).resolve("config.properties");
        if (!Files.exists(configPath)) {
            return null;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config.properties at " + configPath, e);
        }

        String value = properties.getProperty(propertyKey);
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }
}
