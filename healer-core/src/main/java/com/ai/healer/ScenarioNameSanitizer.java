package com.ai.healer;

/**
 * The single shared implementation of the filename-safe transformation applied to a Cucumber
 * scenario name. Hooks (playwright-tests) uses this to name DOM snapshot files, and
 * SurefireReportReader uses it to derive the DOM snapshot filename it expects for a given
 * Surefire testcase name. Both sides must use this same method so they can never drift apart.
 */
public final class ScenarioNameSanitizer {

    private ScenarioNameSanitizer() {
    }

    public static String sanitize(String rawName) {
        return rawName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
