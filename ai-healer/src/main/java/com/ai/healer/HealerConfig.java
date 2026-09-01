package com.ai.healer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Resolves the two settings HealOrchestrator needs from playwright-tests' config.properties: the
 * max number of heal-attempt cycles per invocation (ai.healer.maxRetries), and the Playwright
 * action timeout (playwright.timeout, used to derive how long to wait for a re-run subprocess
 * before giving up on it as hung). Reads the file directly rather than depending on
 * playwright-tests' FrameworkConfig (an Owner-library interface compiled into a different module)
 * - ai-healer has no dependency on either sibling module, same call already made for
 * RepoRoot/HealerOllamaClient.
 */
final class HealerConfig {

    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final int DEFAULT_PLAYWRIGHT_TIMEOUT_MS = 30000;
    private static final Path CONFIG_RELATIVE_PATH =
            Path.of("playwright-tests", "src", "test", "resources", "config.properties");

    private HealerConfig() {
    }

    // The maximum number of heal-attempt cycles (LocatorHealer.heal() calls) a single
    // HealOrchestrator.run() will spend across all failures it processes.
    static int maxRetries() {
        return readInt("ai.healer.maxRetries", DEFAULT_MAX_RETRIES);
    }

    // Playwright's own default action/navigation timeout, in milliseconds - see
    // PlaywrightFactory, which applies this same value to every browser action a scenario
    // performs. HealOrchestrator derives its re-run subprocess watchdog from it rather than
    // hardcoding a separate number, so raising one raises the other automatically.
    static int playwrightTimeoutMs() {
        return readInt("playwright.timeout", DEFAULT_PLAYWRIGHT_TIMEOUT_MS);
    }

    private static int readInt(String key, int defaultValue) {
        String value = readProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String readProperty(String key) {
        Path configPath = RepoRoot.resolve(HealerConfig.class).resolve(CONFIG_RELATIVE_PATH);
        if (!Files.exists(configPath)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return null;
        }
        String value = properties.getProperty(key);
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }
}
