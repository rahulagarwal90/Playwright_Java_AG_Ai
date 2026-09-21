package com.ai.healer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Resolves the two settings HealOrchestrator needs, each from its own file since one is an
 * ai-healer setting and the other is a genuine Playwright setting: the max number of heal-attempt
 * cycles per SCENARIO (ai.healer.maxRetriesPerScenario, ai-healer/config.properties - it was never a
 * Playwright test-execution setting, so it doesn't belong in playwright-tests' own config, and
 * resolves through the same three-tier order as HealerOllamaClient.model() - config file, then an
 * env var, then a hardcoded default), and the Playwright action timeout (playwright.timeout,
 * playwright-tests/src/test/resources/config.properties, used to derive how long to wait for a
 * re-run subprocess before giving up on it as hung - config file then hardcoded default only, no
 * env var, since nothing has needed to override it that way). Reads both files directly rather
 * than depending on playwright-tests' FrameworkConfig (an Owner-library interface compiled into a
 * different module) - ai-healer has no dependency on either sibling module, same call already
 * made for RepoRoot/HealerOllamaClient.
 */
final class HealerConfig {

    private static final int DEFAULT_MAX_RETRIES_PER_SCENARIO = 2;
    private static final int DEFAULT_PLAYWRIGHT_TIMEOUT_MS = 30000;
    private static final boolean DEFAULT_BROWSER_HEADLESS = false;
    private static final String MAX_RETRIES_PER_SCENARIO_ENV_VAR = "AI_HEALER_MAX_RETRIES_PER_SCENARIO";
    private static final String BROWSER_HEADLESS_KEY = "browser.headless";
    private static final Path HEALER_CONFIG_RELATIVE_PATH = Path.of("ai-healer", "config.properties");
    private static final Path PLAYWRIGHT_CONFIG_RELATIVE_PATH =
            Path.of("playwright-tests", "src", "test", "resources", "config.properties");

    private HealerConfig() {
    }

    // The maximum number of heal-attempt cycles (LocatorHealer.heal() calls) HealOrchestrator will
    // spend on any ONE scenario's retry chain before giving up on it and moving to the next - a
    // budget per scenario, not a pool shared across every failure in the run (see
    // HealOrchestrator.runWithSummary()). Resolved the same three-tier way as
    // HealerOllamaClient.model(): ai-healer/config.properties, then
    // AI_HEALER_MAX_RETRIES_PER_SCENARIO, then the hardcoded default.
    static int maxRetriesPerScenario() {
        String fromConfigFile = readProperty(HEALER_CONFIG_RELATIVE_PATH, "ai.healer.maxRetriesPerScenario");
        if (fromConfigFile != null) {
            return parseIntOrDefault(fromConfigFile, DEFAULT_MAX_RETRIES_PER_SCENARIO);
        }
        String fromEnv = System.getenv(MAX_RETRIES_PER_SCENARIO_ENV_VAR);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return parseIntOrDefault(fromEnv, DEFAULT_MAX_RETRIES_PER_SCENARIO);
        }
        return DEFAULT_MAX_RETRIES_PER_SCENARIO;
    }

    // Playwright's own default action/navigation timeout, in milliseconds - see
    // PlaywrightFactory, which applies this same value to every browser action a scenario
    // performs. HealOrchestrator derives its re-run subprocess watchdog from it rather than
    // hardcoding a separate number, so raising one raises the other automatically.
    static int playwrightTimeoutMs() {
        String value = readProperty(PLAYWRIGHT_CONFIG_RELATIVE_PATH, "playwright.timeout");
        return parseIntOrDefault(value, DEFAULT_PLAYWRIGHT_TIMEOUT_MS);
    }

    // The browser.headless value the ORIGINAL test run resolved, so HealOrchestrator's
    // verification re-run subprocess (a separate `mvn` invocation/JVM in its own right) can be
    // told the same value explicitly instead of silently falling back to playwright-tests' own
    // config-file default regardless of what the original run actually used. Mirrors
    // playwright-tests' FrameworkConfig.browserHeadless() resolution order exactly - system
    // property, then env var, then playwright-tests/src/test/resources/config.properties, then
    // false - since that's the same precedence Owner's system:properties/system:env/file sources
    // apply for FrameworkConfig, just read directly here (no dependency on playwright-tests).
    static boolean browserHeadless() {
        String fromSystemProperty = System.getProperty(BROWSER_HEADLESS_KEY);
        if (fromSystemProperty != null && !fromSystemProperty.isBlank()) {
            return Boolean.parseBoolean(fromSystemProperty.trim());
        }
        String fromEnv = System.getenv(BROWSER_HEADLESS_KEY);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Boolean.parseBoolean(fromEnv.trim());
        }
        String fromConfigFile = readProperty(PLAYWRIGHT_CONFIG_RELATIVE_PATH, BROWSER_HEADLESS_KEY);
        if (fromConfigFile != null) {
            return Boolean.parseBoolean(fromConfigFile);
        }
        return DEFAULT_BROWSER_HEADLESS;
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String readProperty(Path configRelativePath, String key) {
        Path configPath = RepoRoot.resolve(HealerConfig.class).resolve(configRelativePath);
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
