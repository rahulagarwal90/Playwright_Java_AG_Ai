package com.ai.healer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For a TestFailure already classified LOCATOR_FAILURE with a DOM snapshot on disk, asks Ollama
 * to suggest a replacement locator built only from elements that actually appear in that
 * snapshot. Orchestration only (extracting the broken locator, loading the snapshot, building the
 * prompt, parsing the answer) - the HTTP call itself is HealerOllamaClient's job, matching how
 * ai-reviewer splits OllamaReviewClient (HTTP) from LocalCodeReviewer (orchestration).
 */
public class LocatorHealer {

    private static final Logger LOGGER = Logger.getLogger(LocatorHealer.class.getName());

    public static class HealResult {
        public String newLocatorCode;
        public String matchedElement;
        public String confidence;
    }

    private static final Pattern LOCATOR_IN_CALL_LOG =
            Pattern.compile("waiting for locator\\([\"']([^\"']+)[\"']\\)");
    // Every page object's locator calls go through BasePage's wrapper methods (click/type/...),
    // so that's always the first com.framework frame in the trace and never the actually useful
    // one - it's the same line for nearly every locator failure in the suite. The concrete page
    // object frame right after it is where the locator field and its usage actually live.
    private static final Pattern PAGE_OBJECT_STACK_FRAME =
            Pattern.compile("at com\\.framework\\.pages\\.(?!BasePage\\.)[\\w.$]+\\((\\w+\\.java):(\\d+)\\)");
    // Fallback for a failure that never goes through a page object at all.
    private static final Pattern PROJECT_STACK_FRAME =
            Pattern.compile("at com\\.framework\\.[\\w.$]+\\((\\w+\\.java):(\\d+)\\)");

    private static final String SYSTEM_PROMPT = """
            You are helping repair a broken Playwright Java locator after a browser automation \
            test failed with a timeout waiting for it. You will be given the broken locator \
            string, optionally the file and line where it was used, and a list of real DOM \
            elements captured from the page at the moment of failure (CANDIDATE ELEMENTS).

            Suggest ONE replacement Playwright Java locator statement (e.g. \
            page.locator("#id"), page.getByTestId("..."), page.getByRole(...), \
            page.getByText("...")) that targets one of the CANDIDATE ELEMENTS.

            Rules:
            - You MUST base the replacement only on an id, testId, role, aria label, or text \
            value that appears EXACTLY in the candidate list below. Never invent, guess, or \
            slightly modify a value that isn't shown there.
            - matchedElement must name which candidate element (by its listed id/testId/role/text) \
            the suggestion is based on.
            - If no candidate looks like a plausible replacement for the broken locator, still \
            return your best guess built only from listed values, and set confidence to "low". \
            Set confidence to "high" only when a candidate clearly corresponds to the broken \
            locator's intent.
            """;

    private final HealerOllamaClient ollamaClient;
    private final Gson gson = new Gson();

    public LocatorHealer(HealerOllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public HealResult heal(TestFailure failure) throws IOException, InterruptedException {
        if (!failure.domSnapshotFound) {
            throw new IllegalStateException("No DOM snapshot found for: " + failure.testName);
        }

        String brokenLocator = extractBrokenLocator(failure);
        List<DomElement> candidates = loadDomSnapshot(failure);
        String fileLineContext = extractFileLineContext(failure.stackTrace);

        String userPrompt = buildUserPrompt(brokenLocator, fileLineContext, candidates);

        LOGGER.info("LocatorHealer system prompt:\n" + SYSTEM_PROMPT);
        LOGGER.info("LocatorHealer user prompt:\n" + userPrompt);

        String responseJson = ollamaClient.suggestLocator(SYSTEM_PROMPT, userPrompt);
        LOGGER.info("LocatorHealer raw Ollama response:\n" + responseJson);

        return parseResult(responseJson);
    }

    private static String extractBrokenLocator(TestFailure failure) {
        String message = failure.failureMessage == null ? "" : failure.failureMessage;
        Matcher matcher = LOCATOR_IN_CALL_LOG.matcher(message);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Could not extract a locator from the failure message for: " + failure.testName);
        }
        return matcher.group(1);
    }

    private static String extractFileLineContext(String stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        Matcher pageObjectMatcher = PAGE_OBJECT_STACK_FRAME.matcher(stackTrace);
        if (pageObjectMatcher.find()) {
            return pageObjectMatcher.group(1) + ":" + pageObjectMatcher.group(2);
        }
        Matcher matcher = PROJECT_STACK_FRAME.matcher(stackTrace);
        return matcher.find() ? matcher.group(1) + ":" + matcher.group(2) : null;
    }

    private List<DomElement> loadDomSnapshot(TestFailure failure) throws IOException {
        String json = Files.readString(failure.domSnapshotPath, StandardCharsets.UTF_8);
        Type listType = new TypeToken<List<DomElement>>() { }.getType();
        List<DomElement> elements = gson.fromJson(json, listType);
        return elements == null ? List.of() : elements;
    }

    private static String buildUserPrompt(String brokenLocator, String fileLineContext, List<DomElement> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("BROKEN LOCATOR: ").append(brokenLocator).append("\n");
        if (fileLineContext != null) {
            prompt.append("USED AT: ").append(fileLineContext).append("\n");
        }
        prompt.append("\nCANDIDATE ELEMENTS:\n").append(formatCandidates(candidates));
        return prompt.toString();
    }

    private static String formatCandidates(List<DomElement> candidates) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (DomElement element : candidates) {
            List<String> parts = new ArrayList<>();
            if (notBlank(element.tag)) {
                parts.add("tag=" + element.tag);
            }
            if (notBlank(element.id)) {
                parts.add("id=" + element.id);
            }
            if (notBlank(element.testId)) {
                parts.add("testId=" + element.testId);
            }
            if (notBlank(element.role)) {
                parts.add("role=" + element.role);
            }
            if (notBlank(element.aria)) {
                parts.add("aria=" + element.aria);
            }
            if (notBlank(element.text)) {
                parts.add("text=\"" + element.text + "\"");
            }
            sb.append(index++).append(". ").append(String.join(" ", parts)).append("\n");
        }
        return sb.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // Reads Ollama's structured-output JSON field by field, matching ai-reviewer's FindingParser
    // convention (defaults over letting a missing field blow up the whole result).
    private HealResult parseResult(String json) {
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        HealResult result = new HealResult();
        result.newLocatorCode = jsonFieldAsString(obj, "newLocatorCode");
        result.matchedElement = jsonFieldAsString(obj, "matchedElement");
        result.confidence = jsonFieldAsString(obj, "confidence");
        return result;
    }

    private static String jsonFieldAsString(JsonObject obj, String fieldName) {
        return (obj != null && obj.has(fieldName) && obj.get(fieldName).isJsonPrimitive())
                ? obj.get(fieldName).getAsString() : "";
    }
}
