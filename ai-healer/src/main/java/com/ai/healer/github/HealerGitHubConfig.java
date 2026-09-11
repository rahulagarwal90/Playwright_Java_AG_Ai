package com.ai.healer.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The GitHub identity/auth settings {@link HealerPullRequestCreator} and
 * {@link NotFixablePrCommenter} both need. Repository and API base resolve exactly the way
 * ai-reviewer's {@code GitHubContext} does (duplicated rather than depended on - ai-healer has no
 * dependency on ai-reviewer, same "parked" sharing decision already made for
 * {@code HealOrchestrator.isPipelineContext()} and {@code HealerOllamaClient} vs. ai-reviewer's
 * {@code OllamaConfig}). The token is deliberately a NEW, separate setting
 * ({@code AI_HEALER_GITHUB_TOKEN}) rather than reusing ai-reviewer's {@code GITHUB_TOKEN}.
 * <b>Env-var only, no config-file tier</b> - a real token leaking into a log during manual testing
 * traced back to a value that had been left in {@code ai-healer/config.properties} for local
 * convenience; Jenkins' {@code credentials()} binding injects a token via environment variable
 * only, so removing the config-file fallback here removes the one place a developer could
 * accidentally leave a real token sitting in a file on disk.
 */
final class HealerGitHubConfig {

    private static final String TOKEN_ENV_VAR = "AI_HEALER_GITHUB_TOKEN";

    private HealerGitHubConfig() {
    }

    static String token() {
        return resolveToken(System.getenv(TOKEN_ENV_VAR));
    }

    // Package-private and pure (no System.getenv of its own) specifically so the missing/blank-
    // vs-present decision can be tested directly without mutating real process environment
    // variables - this test suite has no env-var-mocking library, and System.getenv()'s own map
    // can't be reflectively patched without a JVM flag this module's pom doesn't set.
    static String resolveToken(String envValue) {
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        throw new IllegalStateException(
                "Missing required GitHub token for ai-healer's pipeline git/PR flow: set the "
                + TOKEN_ENV_VAR + " env var.");
    }

    // Resolves "owner/repo" from GITHUB_REPOSITORY, or parses it out of Jenkins' CHANGE_URL -
    // mirrors ai-reviewer's GitHubContext.repository() exactly.
    static String repository() {
        String repository = System.getenv("GITHUB_REPOSITORY");
        if (repository != null && !repository.isBlank()) {
            return repository;
        }
        String changeUrl = System.getenv("CHANGE_URL");
        if (changeUrl != null && !changeUrl.isBlank()) {
            try {
                URI uri = URI.create(changeUrl);
                String[] segments = uri.getPath().split("/");
                List<String> cleaned = new ArrayList<>();
                for (String segment : segments) {
                    if (segment != null && !segment.isBlank()) {
                        cleaned.add(segment);
                    }
                }
                if (cleaned.size() >= 2) {
                    return cleaned.get(0) + "/" + cleaned.get(1);
                }
            } catch (Exception e) {
                // Fall through to the error below.
            }
        }
        throw new IllegalStateException("Missing required GitHub repository context: GITHUB_REPOSITORY or CHANGE_URL");
    }

    // Mirrors ai-reviewer's GitHubContext.apiBase() exactly.
    static String apiBase() {
        String fromEnv = System.getenv("GITHUB_API_URL");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : "https://api.github.com";
    }
}
