package com.ai.reviewer.ollama;

/**
 * This class only knows which Ollama model name to use. It reads the OLLAMA_MODEL environment
 * variable, falling back to a sensible default when it isn't set, so every class that needs the
 * model name (OllamaReviewClient, LearningLoop, LocalCodeReviewer) asks this one place instead
 * of hardcoding it themselves.
 */
public class OllamaConfig {

    private static final String DEFAULT_MODEL = "qwen2.5-coder:14b";

    // Returns the Ollama model to use: OLLAMA_MODEL if set, otherwise the default.
    public static String model() {
        String model = System.getenv("OLLAMA_MODEL");
        return (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
    }
}
