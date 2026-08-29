package com.ai.reviewer.learning;

import com.ai.reviewer.ModuleRoot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists rules learned from human PR review comments (see "learn" mode in
 * {@link LocalCodeReviewer}) to ai-reviewer/learned-rules.json so they can be
 * folded into future review prompts.
 *
 * This file is meant to be committed to git like any other source file — it is not a
 * local-only cache. Jenkins runs the reviewer against a fresh checkout on every build, so a
 * learned rule only takes effect there once this file has been committed and merged; anything
 * saved locally but not pushed is invisible to every other checkout, including CI's.
 */
public class RuleStore {

    private final Path storePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public RuleStore() {
        this(resolveDefaultStorePath());
    }

    public RuleStore(Path storePath) {
        this.storePath = storePath;
    }

    /**
     * Resolves the store path so it always lands at <module-root>/learned-rules.json,
     * regardless of the JVM's working directory — see ModuleRoot for how the module root
     * itself is found.
     */
    private static Path resolveDefaultStorePath() {
        return ModuleRoot.resolve(RuleStore.class).resolve("learned-rules.json");
    }

    /**
     * Loads the currently stored learned rules, or an empty list if the store does not exist yet.
     */
    public List<LearnedRule> load() {
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<LearnedRule>>() {
            }.getType();
            List<LearnedRule> rules = gson.fromJson(reader, listType);
            return rules != null ? rules : new ArrayList<>();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read rule store at " + storePath, e);
        }
    }

    /**
     * Persists the given rules, pretty-printed and de-duplicated by sourceComment so
     * re-running learn mode on the same PR comments does not create duplicate entries.
     */
    public void save(List<LearnedRule> rules) {
        Map<String, LearnedRule> deduped = new LinkedHashMap<>();
        for (LearnedRule rule : rules) {
            deduped.putIfAbsent(rule.sourceComment, rule);
        }
        try {
            if (storePath.getParent() != null) {
                Files.createDirectories(storePath.getParent());
            }
            // This write is the actual persistence point of the learning loop: whatever is in
            // `deduped` here is exactly what the next `git status` will show as a change to
            // learned-rules.json, and exactly what a future OllamaReviewClient.sendReview() call
            // will read back.
            try (Writer writer = Files.newBufferedWriter(storePath, StandardCharsets.UTF_8)) {
                gson.toJson(new ArrayList<>(deduped.values()), writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write rule store at " + storePath, e);
        }
    }

    /**
     * A single rule learned from a human PR review comment.
     */
    public static class LearnedRule {
        public String rule;
        public String sourceComment;
        public String learnedAt;
    }
}
