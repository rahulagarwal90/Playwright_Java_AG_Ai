package com.ai.healer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For a TestFailure already classified LOCATOR_FAILURE with a DOM snapshot on disk, asks Ollama
 * to suggest a replacement bare selector built only from elements that actually appear in that
 * snapshot. Orchestration only (extracting the broken locator, loading the snapshot, building the
 * prompt, parsing the answer) - the HTTP call itself is HealerOllamaClient's job, matching how
 * ai-reviewer splits OllamaReviewClient (HTTP) from LocalCodeReviewer (orchestration).
 */
public class LocatorHealer {

    private static final Logger LOGGER = Logger.getLogger(LocatorHealer.class.getName());

    public static class HealResult {
        public String newSelector;
        public String matchedElement;
        public String confidence;
        // The original broken locator value this replaces, so a caller (HealOrchestrator) can
        // tell "the re-run still fails at this exact same locator" (the fix didn't work) apart
        // from "the re-run now fails at a different locator" (this fix was fine, something else
        // is broken too) without re-deriving the extraction itself.
        public String brokenLocator;
        // Where the broken locator field actually lives, so PageObjectPatcher has something to
        // act on. Null/0 when no page object stack frame could be identified (see
        // extractPageObjectLocation) - the caller then has no patchable location, only a
        // suggestion.
        public Path filePath;
        public int lineNumber;
    }

    // A resolved (source file, line number) pointing at a page object's locator field.
    // Package-private (not private) so tests can exercise the resolution logic directly.
    record PageObjectLocation(Path filePath, int lineNumber) {
    }

    // Group 1 is the wrapping quote character Playwright used (every real call log we've
    // captured wraps with "..."; the [\"'] keeps '...' recognized too, matching this project's
    // existing test fixtures). Group 2 is greedy and backreferences group 1 for its closing
    // delimiter rather than stopping at the first quote character - a real broken locator can
    // itself contain a quote of the OTHER kind, e.g. CartPage's real
    // "[datatest='checkout']" (single-quoted attribute value) inside Playwright's
    // waiting for locator("[datatest='checkout']") (double-quoted wrapper). Greedy backtracking
    // naturally resolves to the *rightmost* occurrence of the wrapper's own quote immediately
    // before ")" - i.e. the true closing delimiter - rather than the first quote encountered,
    // which is exactly what a non-greedy [^"']+ character class got wrong. Confirmed against
    // that real CartPage failure: this now captures the full "[datatest='checkout']", not just
    // "[datatest=" truncated at the embedded '.
    private static final Pattern LOCATOR_IN_CALL_LOG =
            Pattern.compile("waiting for locator\\(([\"'])(.+)\\1\\)");
    // Every page object's locator calls go through BasePage's wrapper methods (click/type/...),
    // so that's always the first com.framework frame in the trace and never the actually useful
    // one - it's the same line for nearly every locator failure in the suite. The concrete page
    // object frame right after it is where the locator field and its usage actually live. Group 1
    // captures the fully qualified frame (package + class + method) so the source file's path can
    // be derived from it, not just its bare filename.
    private static final Pattern PAGE_OBJECT_STACK_FRAME =
            Pattern.compile("at (com\\.framework\\.pages\\.(?!BasePage\\.)[\\w.$]+)\\((\\w+\\.java):(\\d+)\\)");
    // Fallback for a failure that never goes through a page object at all - used only for the
    // human-readable prompt context, since a non-page-object frame doesn't reliably map to a
    // src/main/java path the way a page object frame does.
    private static final Pattern PROJECT_STACK_FRAME =
            Pattern.compile("at com\\.framework\\.[\\w.$]+\\((\\w+\\.java):(\\d+)\\)");

    private static final String SYSTEM_PROMPT = """
            You are helping repair a broken Playwright Java locator after a browser automation \
            test failed with a timeout waiting for it. You will be given the broken locator \
            string, optionally the file and line where it was used, and a list of real DOM \
            elements captured from the page at the moment of failure (CANDIDATE ELEMENTS).

            Suggest ONE replacement bare selector value - not a Java statement, just the raw \
            selector text a page object would store in a field and later pass to \
            page.click(selector)/page.locator(selector) (e.g. "#id", "[data-test='x']", \
            ".some-class", "text=Some Text") - that targets one of the CANDIDATE ELEMENTS.

            Rules:
            - You MUST base the replacement only on an id, data-test/data-testid attribute value, \
            role, aria label, or text value that appears EXACTLY in the candidate list below. \
            Never invent, guess, or slightly modify a value that isn't shown there.
            - A candidate's data-test/data-testid value came from a real HTML attribute named \
            EITHER data-test OR data-testid - never an attribute literally named "testId". If you \
            build an attribute selector from it, use the real attribute name, e.g. \
            [data-test='value'], NOT [testId='value'] (which does not exist on the real page and \
            would match nothing).
            - matchedElement must name which candidate element (by its listed id/data-test-or-\
            data-testid/role/text) the suggestion is based on.
            - Multiple elements may share the same property - the candidate list marks a value \
            [NOT UNIQUE] whenever more than one candidate shares it. A locator built from a \
            [NOT UNIQUE] text, role, or aria value could match more than one element on the real \
            page, not just the one you intend. When that's the case, prefer a candidate's id or \
            data-test/data-testid value instead, since those identify one specific element; note \
            the ambiguity explicitly in matchedElement whenever you rely on or deliberately avoid \
            a [NOT UNIQUE] property.
            - If the only candidates matching the broken locator's intent share a [NOT UNIQUE] \
            property and none of them has a unique id or data-test/data-testid value, set \
            confidence to "low" and say so explicitly in matchedElement, rather than arbitrarily \
            picking one of them.
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

        HealResult result = parseResult(responseJson);
        result.brokenLocator = brokenLocator;
        PageObjectLocation location = extractPageObjectLocation(failure.stackTrace, brokenLocator);
        if (location != null) {
            result.filePath = location.filePath();
            result.lineNumber = location.lineNumber();
        }
        return result;
    }

    private static String extractBrokenLocator(TestFailure failure) {
        String locator = tryExtractBrokenLocator(failure);
        if (locator == null) {
            throw new IllegalStateException(
                    "Could not extract a locator from the failure message for: " + failure.testName);
        }
        return locator;
    }

    // Package-private, null-safe variant HealOrchestrator uses to compare a fresh re-run failure's
    // locator against a just-applied patch - unlike extractBrokenLocator, doesn't require the
    // failure to actually be locator-shaped (a fresh failure might not be, if the patch fixed the
    // locator problem but something else now fails).
    static String tryExtractBrokenLocator(TestFailure failure) {
        String message = failure.failureMessage == null ? "" : failure.failureMessage;
        Matcher matcher = LOCATOR_IN_CALL_LOG.matcher(message);
        return matcher.find() ? matcher.group(2) : null;
    }

    private static String extractFileLineContext(String stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        Matcher pageObjectMatcher = PAGE_OBJECT_STACK_FRAME.matcher(stackTrace);
        if (pageObjectMatcher.find()) {
            return pageObjectMatcher.group(2) + ":" + pageObjectMatcher.group(3);
        }
        Matcher matcher = PROJECT_STACK_FRAME.matcher(stackTrace);
        return matcher.find() ? matcher.group(1) + ":" + matcher.group(2) : null;
    }

    // Matches a page object's locator field declaration, capturing the string literal's exact
    // content so it can be compared against the broken locator value.
    private static final Pattern LOCATOR_FIELD_DECLARATION =
            Pattern.compile("^\\s*private\\s+final\\s+String\\s+\\w+\\s*=\\s*\"([^\"]*)\"\\s*;\\s*$");

    // Resolves the concrete page object frame in a stack trace into an actual source file path
    // and line number, so PageObjectPatcher has a real location to act on. Deliberately narrower
    // than extractFileLineContext (used only for the prompt): a frame outside com.framework.pages
    // could live under src/test/java instead of src/main/java (steps, hooks, ...), so there's no
    // reliable way to turn it into a resolvable path - null is the honest answer there.
    //
    // The stack frame only points at the *call site* (e.g. "click(loginButton);"), not the field
    // *declaration* - those are different lines. Once the file is located, this searches it for
    // the one "private final String X = "...";" line whose literal value is exactly the broken
    // locator string, and reports that line instead. Falling back to the call-site line if no
    // such declaration is found is a safe failure mode: PageObjectPatcher will simply refuse to
    // patch a line that isn't a locator declaration, exactly as it should.
    private static PageObjectLocation extractPageObjectLocation(String stackTrace, String brokenLocator) {
        return extractPageObjectLocation(stackTrace, brokenLocator, RepoRoot.resolve(LocatorHealer.class));
    }

    // Package-private overload so tests can point resolution at a temp directory instead of the
    // real repo root, without depending on whatever this repo's real page objects currently hold.
    static PageObjectLocation extractPageObjectLocation(String stackTrace, String brokenLocator, Path repoRoot) {
        if (stackTrace == null) {
            return null;
        }
        Matcher matcher = PAGE_OBJECT_STACK_FRAME.matcher(stackTrace);
        if (!matcher.find()) {
            return null;
        }

        String qualifiedFrame = matcher.group(1); // e.g. com.framework.pages.saucedemo.LoginPage.loginWithConfigCredentials
        String fileName = matcher.group(2); // e.g. LoginPage.java
        int callSiteLine = Integer.parseInt(matcher.group(3));

        // Drop the method name and the simple class name, keep the package.
        String[] parts = qualifiedFrame.split("\\.");
        String packagePath = String.join("/", Arrays.copyOf(parts, parts.length - 2));

        Path filePath = repoRoot.resolve("playwright-tests/src/main/java").resolve(packagePath).resolve(fileName);

        int declarationLine = findLocatorDeclarationLine(filePath, brokenLocator);
        return new PageObjectLocation(filePath, declarationLine > 0 ? declarationLine : callSiteLine);
    }

    private static int findLocatorDeclarationLine(Path filePath, String brokenLocator) {
        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher fieldMatcher = LOCATOR_FIELD_DECLARATION.matcher(lines.get(i));
                if (fieldMatcher.matches() && fieldMatcher.group(1).equals(brokenLocator)) {
                    return i + 1;
                }
            }
        } catch (IOException e) {
            // Fall through - the caller falls back to the call-site line.
        }
        return -1;
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

        // id/data-test(id) are treated as reliably unique per-element identifiers by convention in
        // this codebase, so only the free-text-ish properties (text/role/aria) are checked here -
        // two buttons can easily share the same visible text ("Add to cart") while having distinct
        // data-test values, and that's exactly the case a locator built from text alone would get
        // wrong.
        Set<String> duplicateTexts = findDuplicateValues(candidates, element -> element.text);
        Set<String> duplicateRoles = findDuplicateValues(candidates, element -> element.role);
        Set<String> duplicateArias = findDuplicateValues(candidates, element -> element.aria);
        boolean hasAmbiguousCandidates =
                !duplicateTexts.isEmpty() || !duplicateRoles.isEmpty() || !duplicateArias.isEmpty();

        if (hasAmbiguousCandidates) {
            prompt.append("\nNOTE: Some candidate elements below share the same text, role, or ")
                    .append("aria value - marked [NOT UNIQUE]. A locator built from a [NOT UNIQUE] ")
                    .append("value could match more than one element on the real page. Prefer a ")
                    .append("candidate's id or data-test/data-testid value in that case; if only ")
                    .append("[NOT UNIQUE] properties match and no candidate has a unique ")
                    .append("id/data-test/data-testid, set confidence to \"low\" and say so in ")
                    .append("matchedElement.\n");
        }

        prompt.append("\nCANDIDATE ELEMENTS:\n")
                .append(formatCandidates(candidates, duplicateTexts, duplicateRoles, duplicateArias));
        return prompt.toString();
    }

    // Returns every non-blank value that accessor pulls out of more than one candidate - e.g. two
    // "Add to cart" buttons for different products would both surface "Add to cart" here.
    private static Set<String> findDuplicateValues(List<DomElement> candidates, Function<DomElement, String> accessor) {
        Map<String, Integer> counts = new HashMap<>();
        for (DomElement element : candidates) {
            String value = accessor.apply(element);
            if (notBlank(value)) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        Set<String> duplicates = new HashSet<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                duplicates.add(entry.getKey());
            }
        }
        return duplicates;
    }

    private static String formatCandidates(List<DomElement> candidates, Set<String> duplicateTexts,
            Set<String> duplicateRoles, Set<String> duplicateArias) {
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
                // DomElement.testId (see Hooks.captureDomSnapshot) is populated from an element's
                // real data-test attribute, falling back to data-testid - never a "testId"
                // attribute, which doesn't exist on any real page. Label it as what it actually
                // is so the model builds a selector against a real attribute, not an invented one.
                parts.add("data-test/data-testid=" + element.testId);
            }
            if (notBlank(element.role)) {
                parts.add("role=" + element.role + (duplicateRoles.contains(element.role) ? " [NOT UNIQUE]" : ""));
            }
            if (notBlank(element.aria)) {
                parts.add("aria=" + element.aria + (duplicateArias.contains(element.aria) ? " [NOT UNIQUE]" : ""));
            }
            if (notBlank(element.text)) {
                parts.add("text=\"" + element.text + "\"" + (duplicateTexts.contains(element.text) ? " [NOT UNIQUE]" : ""));
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
        result.newSelector = jsonFieldAsString(obj, "newSelector");
        result.matchedElement = jsonFieldAsString(obj, "matchedElement");
        result.confidence = jsonFieldAsString(obj, "confidence");
        return result;
    }

    private static String jsonFieldAsString(JsonObject obj, String fieldName) {
        return (obj != null && obj.has(fieldName) && obj.get(fieldName).isJsonPrimitive())
                ? obj.get(fieldName).getAsString() : "";
    }
}
