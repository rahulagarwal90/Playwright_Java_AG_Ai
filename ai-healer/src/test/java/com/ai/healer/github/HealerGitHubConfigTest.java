package com.ai.healer.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class HealerGitHubConfigTest {

    // token() itself just calls resolveToken(System.getenv(...)) - tested against
    // resolveToken(String) directly (rather than mutating the real AI_HEALER_GITHUB_TOKEN process
    // environment variable) since this test suite has no env-var-mocking library, and
    // System.getenv()'s backing map can't be reflectively patched without a JVM flag this
    // module's pom doesn't set. This exercises exactly the same missing/blank-vs-present decision
    // token() makes, without depending on - or risking mutating - whatever is actually set in the
    // real environment this test happens to run in.

    @Test
    void resolveTokenReturnsTheGivenValueWhenPresentAndNotBlank() {
        assertEquals("ghs_realtoken123", HealerGitHubConfig.resolveToken("ghs_realtoken123"));
    }

    @Test
    void resolveTokenThrowsAnEnvVarOnlyMessageWhenMissing() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> HealerGitHubConfig.resolveToken(null));

        assertTrue(exception.getMessage().contains("AI_HEALER_GITHUB_TOKEN"),
                "message should point at the env var: " + exception.getMessage());
        assertFalse(exception.getMessage().toLowerCase().contains("config"),
                "message must not mention a config-file fallback - that tier was removed: "
                        + exception.getMessage());
    }

    @Test
    void resolveTokenThrowsAnEnvVarOnlyMessageWhenBlank() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> HealerGitHubConfig.resolveToken("   "));

        assertTrue(exception.getMessage().contains("AI_HEALER_GITHUB_TOKEN"));
    }
}
