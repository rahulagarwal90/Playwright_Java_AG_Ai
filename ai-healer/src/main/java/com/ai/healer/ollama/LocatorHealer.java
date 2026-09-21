package com.ai.healer.ollama;

import com.ai.healer.RepoRoot;
import com.ai.healer.report.DomElement;
import com.ai.healer.report.TestFailure;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
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
        // The Java field name the broken locator is declared as (e.g. "checkoutButton"), for a
        // human-readable "ClassName.fieldName" summary label - see HealOrchestrator's run
        // summary. Null when lineNumber falls back to the call-site line instead of a resolved
        // field declaration (see extractPageObjectLocation) - there's no field name to report
        // in that case.
        public String fieldName;
    }

    // A resolved (source file, line number, field name) pointing at a page object's locator
    // field. fieldName is null when lineNumber falls back to the call-site line rather than a
    // resolved field declaration. Public (was package-private before the com.ai.healer.ollama
    // package split) so LocatorHealerTest, which stays at top-level com.ai.healer, can exercise
    // the resolution logic directly.
    public record PageObjectLocation(Path filePath, int lineNumber, String fieldName) {
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
    // Fallback for a syntactically invalid selector that Playwright's own CSS parser rejects
    // before it can even build a locator descriptor for it - the call log then has no
    // locator(...) wrapper at all, just "waiting for <raw selector text>" for the rest of the
    // line. Confirmed against a real broken CheckoutStepOnePage.continueButton =
    // "[data-test=continue']" (unterminated quote): the call log line reads
    // "- waiting for [data-test=continue']", not "waiting for locator(...)". The negative
    // lookahead keeps this from ever firing on the standard, already-handled shape above.
    private static final Pattern UNPARSEABLE_LOCATOR_IN_CALL_LOG =
            Pattern.compile("waiting for (?!locator\\()(\\S[^\\r\\n]*)");
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

    // Mirrors ai-reviewer's OllamaReviewClient/system-prompt.md pattern exactly: the instructions
    // sent to Ollama live in a packaged resource file, not inline in code, so LocatorHealer's
    // heal-trace logging can honestly report where the prompt came from (see heal() below).
    private static final String SYSTEM_PROMPT_RESOURCE_PATH = "/system-prompt.md";
    private static final String SYSTEM_PROMPT = loadSystemPromptTemplate();

    // Enables the verbose diagnostic dump (full system/user prompt text, raw Ollama response JSON)
    // that used to print unconditionally on every heal() call, cluttering the terminal. Off by
    // default; opt in with -Dhealer.verbose=true, same system-property-flag convention already
    // used for healer.skipArtifactCleanup/healer.minReportTimestamp.
    private static final boolean VERBOSE = Boolean.getBoolean("healer.verbose");

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
        List<DomElement> allCandidates = loadDomSnapshot(failure);
        List<DomElement> candidates = rankBySimilarityAndCap(brokenLocator, allCandidates);
        String fileLineContext = extractFileLineContext(failure.stackTrace);

        String userPrompt = buildUserPrompt(brokenLocator, fileLineContext, candidates);

        // A clean, narrated trace of what's about to happen and what came back - printed via
        // System.out (not LOGGER) for the same reason HealOrchestrator's run summary is: no
        // per-line timestamp/class-name prefix cluttering the output. The full prompt text and raw
        // response JSON these lines summarize are still available, just gated behind VERBOSE below
        // instead of dumped unconditionally.
        HealerOllamaClient.ResolvedModel model = HealerOllamaClient.resolveModel();
        System.out.println("[HEAL] Model: " + model.value() + " (from " + model.source() + ")");
        System.out.println("[HEAL] System prompt loaded from: classpath:" + SYSTEM_PROMPT_RESOURCE_PATH);
        System.out.println("[HEAL] DOM snapshot loaded from: " + failure.domSnapshotPath
                + " (" + allCandidates.size() + " element(s) captured, " + candidates.size()
                + " sent to Ollama)");
        if (VERBOSE) {
            LOGGER.info("LocatorHealer system prompt:\n" + SYSTEM_PROMPT);
            LOGGER.info("LocatorHealer user prompt:\n" + userPrompt);
        }
        System.out.println("[HEAL] Sending diagnosis request to Ollama...");

        String responseJson = ollamaClient.suggestLocator(SYSTEM_PROMPT, userPrompt);
        if (VERBOSE) {
            LOGGER.info("LocatorHealer raw Ollama response:\n" + responseJson);
        }

        HealResult result = parseResult(responseJson);
        System.out.println("[HEAL] Response: newSelector=\"" + result.newSelector + "\", matchedElement=\""
                + result.matchedElement + "\", confidence=\"" + result.confidence + "\"");

        result.brokenLocator = brokenLocator;
        PageObjectLocation location = extractPageObjectLocation(failure.stackTrace, brokenLocator);
        if (location != null) {
            result.filePath = location.filePath();
            result.lineNumber = location.lineNumber();
            result.fieldName = location.fieldName();
        }
        return result;
    }

    // The 100-element cap Hooks.captureDomSnapshot used to apply itself, in raw DOM order, before
    // it even knew which locator had broken. It's applied here instead, AFTER ranking every
    // captured element by similarity to the broken locator's own text - so on a busy real page
    // where Hooks captures more than this many elements, the ones most likely to actually be the
    // broken locator's target survive, not whatever happened to come first in the DOM. Confirmed
    // for real this matters: a class-heavy DemoQA page produced 211 candidates, with the correct
    // element sitting at raw DOM-order index 199 - a cap applied before ranking would drop it
    // every time, and the healer picked an unrelated ancestor ("#root") instead.
    private static final int MAX_CANDIDATES = 100;

    // One scored candidate, kept together so the ranked-list logging below (which needs both the
    // element and its score) doesn't have to re-derive the score after sorting.
    private record ScoredElement(DomElement element, int score) {
    }

    // Ranks every captured candidate by similarity to the broken locator, then keeps the top
    // MAX_CANDIDATES. A no-op (candidates returned unchanged, in their original order) when
    // there's nothing to cap - the common case on any page under the limit, and exactly the
    // behavior every existing small-snapshot test already depends on. Public (mirrors
    // PageObjectLocation/extractPageObjectLocation's precedent) so LocatorHealerRankingTest can
    // exercise the ranking/capping behavior directly, without needing a real DOM snapshot file.
    public static List<DomElement> rankBySimilarityAndCap(String brokenLocator, List<DomElement> candidates) {
        if (candidates.size() <= MAX_CANDIDATES) {
            return candidates;
        }

        List<ScoredElement> scored = new ArrayList<>(candidates.size());
        for (DomElement element : candidates) {
            scored.add(new ScoredElement(element, similarityScore(brokenLocator, element)));
        }
        // List.sort is a stable sort (TimSort), so candidates that score equally - the large
        // majority on a busy page, having nothing in common with the broken locator - keep their
        // original relative DOM order rather than being reshuffled arbitrarily.
        scored.sort((a, b) -> b.score() - a.score());

        System.out.println("[HEAL] Ranked " + candidates.size() + " captured candidates by similarity to "
                + "the broken locator \"" + brokenLocator + "\"; keeping the top " + MAX_CANDIDATES
                + " (dropping " + (candidates.size() - MAX_CANDIDATES) + ").");
        int shown = Math.min(5, scored.size());
        for (int i = 0; i < shown; i++) {
            ScoredElement top = scored.get(i);
            System.out.println("[HEAL]   #" + (i + 1) + " score=" + top.score() + " " + describeForLog(top.element()));
        }

        List<DomElement> kept = new ArrayList<>(MAX_CANDIDATES);
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            kept.add(scored.get(i).element());
        }
        return kept;
    }

    private static String describeForLog(DomElement element) {
        List<String> parts = new ArrayList<>();
        if (notBlank(element.tag)) {
            parts.add("tag=" + element.tag);
        }
        if (notBlank(element.id)) {
            parts.add("id=" + element.id);
        }
        if (notBlank(element.dataTest)) {
            parts.add("data-test=" + element.dataTest);
        }
        if (notBlank(element.dataTestId)) {
            parts.add("data-testid=" + element.dataTestId);
        }
        if (notBlank(element.className)) {
            parts.add("class=" + element.className);
        }
        if (notBlank(element.text)) {
            parts.add("text=\"" + element.text + "\"");
        }
        return String.join(" ", parts);
    }

    // Scores one candidate against the broken locator's own text: the best (highest-scoring)
    // match across its id/data-test/data-testid/class/text fields. Deliberately simple and
    // explainable, not a general string-similarity library - just meaningfully better than
    // arbitrary DOM order.
    private static int similarityScore(String brokenLocator, DomElement element) {
        List<String> brokenTokens = tokenize(brokenLocator);
        String brokenNormalized = normalize(brokenLocator);

        int best = 0;
        best = Math.max(best, fieldScore(brokenTokens, brokenNormalized, element.id));
        best = Math.max(best, fieldScore(brokenTokens, brokenNormalized, element.dataTest));
        best = Math.max(best, fieldScore(brokenTokens, brokenNormalized, element.dataTestId));
        best = Math.max(best, fieldScore(brokenTokens, brokenNormalized, element.className));
        best = Math.max(best, fieldScore(brokenTokens, brokenNormalized, element.text));
        return best;
    }

    // +4 if the broken locator's text and this field's value fully contain one another once
    // separator/selector punctuation is stripped (catches a near-miss typo like the missing
    // hyphen in "[datatest='checkout']" against the real "data-test" attribute, or a single
    // dropped character like "#nventory_container" against the real "inventory_container"),
    // plus per-token credit for every (brokenToken, fieldToken) pair: +3 exact match, +2 one
    // token contains the other, +1 tokens a single edit apart (catches a typo'd whole word that
    // isn't a simple substring, e.g. "nam" against "name").
    private static int fieldScore(List<String> brokenTokens, String brokenNormalized, String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) {
            return 0;
        }
        int score = 0;
        String fieldNormalized = normalize(fieldValue);
        if (!fieldNormalized.isEmpty() && !brokenNormalized.isEmpty()
                && (brokenNormalized.contains(fieldNormalized) || fieldNormalized.contains(brokenNormalized))) {
            score += 4;
        }

        for (String brokenToken : brokenTokens) {
            for (String fieldToken : tokenize(fieldValue)) {
                score += tokenPairScore(brokenToken, fieldToken);
            }
        }
        return score;
    }

    private static int tokenPairScore(String a, String b) {
        if (a.equals(b) && a.length() >= 2) {
            return 3;
        }
        if (a.length() >= 3 && b.length() >= 3 && (a.contains(b) || b.contains(a))) {
            return 2;
        }
        // Length >= 4 (stricter than the substring check above) so two short, otherwise-unrelated
        // tokens like "nam"/"nav" don't collide just because a 3-letter edit distance of 1 is easy
        // to hit by chance - the substring branch already covers genuine short-token typos like
        // "nam" against "name" ("name".contains("nam")) without needing this bonus too.
        if (a.length() >= 4 && b.length() >= 4 && levenshteinDistance(a, b) <= 1) {
            return 1;
        }
        return 0;
    }

    // Lowercases and strips every non-alphanumeric character (selector punctuation, quotes,
    // whitespace) with no separators left behind - a plain "same letters, same order" comparison,
    // used only for the whole-value containment check in fieldScore.
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    // Splits on non-alphanumeric separators (-, _, whitespace, CSS/selector punctuation) and on
    // camelCase boundaries, e.g. "addToCartButton" -> [add, to, cart, button],
    // "#nventory_container" -> [nventory, container].
    private static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String camelCaseSplit = value.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        String[] rawTokens = camelCaseSplit.toLowerCase().split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    // Classic Levenshtein edit distance (insert/delete/substitute) - used only as a small bonus
    // for single-character typos between otherwise-unrelated tokens, not the primary signal.
    private static int levenshteinDistance(String a, String b) {
        int[][] distances = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            distances[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            distances[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                distances[i][j] = Math.min(Math.min(distances[i - 1][j] + 1, distances[i][j - 1] + 1),
                        distances[i - 1][j - 1] + cost);
            }
        }
        return distances[a.length()][b.length()];
    }

    // Reads the fixed locator-repair instructions out of the packaged system-prompt.md resource
    // file - mirrors ai-reviewer's OllamaReviewClient.loadSystemPromptTemplate() exactly. Loaded
    // once at class-init time: unlike ai-reviewer's version, nothing here splices per-request
    // content into it, so there's no reason to re-read the file on every heal() call.
    private static String loadSystemPromptTemplate() {
        try (InputStream in = LocatorHealer.class.getResourceAsStream(SYSTEM_PROMPT_RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + SYSTEM_PROMPT_RESOURCE_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load LocatorHealer system prompt template", e);
        }
    }

    private static String extractBrokenLocator(TestFailure failure) {
        String locator = tryExtractBrokenLocator(failure);
        if (locator == null) {
            throw new IllegalStateException(
                    "Could not extract a locator from the failure message for: " + failure.testName);
        }
        return locator;
    }

    // Public (was package-private before the com.ai.healer.ollama package split), null-safe
    // variant HealOrchestrator (top-level com.ai.healer) uses to compare a fresh re-run failure's
    // locator against a just-applied patch - unlike extractBrokenLocator, doesn't require the
    // failure to actually be locator-shaped (a fresh failure might not be, if the patch fixed the
    // locator problem but something else now fails).
    public static String tryExtractBrokenLocator(TestFailure failure) {
        String message = failure.failureMessage == null ? "" : failure.failureMessage;
        Matcher matcher = LOCATOR_IN_CALL_LOG.matcher(message);
        if (matcher.find()) {
            return matcher.group(2);
        }
        Matcher fallbackMatcher = UNPARSEABLE_LOCATOR_IN_CALL_LOG.matcher(message);
        return fallbackMatcher.find() ? fallbackMatcher.group(1).trim() : null;
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

    // Matches a page object's locator field declaration, capturing the field name (group 1, for
    // HealOrchestrator's "ClassName.fieldName" summary label) and the string literal's exact
    // content (group 2, compared against the broken locator value).
    private static final Pattern LOCATOR_FIELD_DECLARATION =
            Pattern.compile("^\\s*private\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"\\s*;\\s*$");

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

    // Public (was package-private before the com.ai.healer.ollama package split) overload so
    // LocatorHealerTest can point resolution at a temp directory instead of the real repo root,
    // without depending on whatever this repo's real page objects currently hold.
    public static PageObjectLocation extractPageObjectLocation(String stackTrace, String brokenLocator, Path repoRoot) {
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

        FieldDeclaration declaration = findLocatorDeclaration(filePath, brokenLocator);
        return declaration != null
                ? new PageObjectLocation(filePath, declaration.lineNumber(), declaration.fieldName())
                : new PageObjectLocation(filePath, callSiteLine, null);
    }

    // Line number plus field name for a resolved "private final String X = "...";" declaration.
    private record FieldDeclaration(int lineNumber, String fieldName) {
    }

    private static FieldDeclaration findLocatorDeclaration(Path filePath, String brokenLocator) {
        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher fieldMatcher = LOCATOR_FIELD_DECLARATION.matcher(lines.get(i));
                if (fieldMatcher.matches() && fieldMatcher.group(2).equals(brokenLocator)) {
                    return new FieldDeclaration(i + 1, fieldMatcher.group(1));
                }
            }
        } catch (IOException e) {
            // Fall through - the caller falls back to the call-site line.
        }
        return null;
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

        // id/data-test(id) used to be assumed reliably unique by convention and excluded from
        // duplicate-checking entirely. That assumption is real in practice but was never actually
        // verified in code - two elements CAN share the same id/data-test/data-testid (a markup
        // bug, or a template rendered twice), and a locator built from one in that rare case would
        // be just as ambiguous as one built from shared text. Every candidate property - including
        // id and data-test/data-testid now - is counted the same way, so "unique" always means
        // "the code counted exactly one occurrence," never an assumption.
        Set<String> duplicateIds = findDuplicateValues(candidates, element -> element.id);
        Set<String> duplicateDataTests = findDuplicateValues(candidates, element -> element.dataTest);
        Set<String> duplicateDataTestIds = findDuplicateValues(candidates, element -> element.dataTestId);
        Set<String> duplicateTexts = findDuplicateValues(candidates, element -> element.text);
        Set<String> duplicateRoles = findDuplicateValues(candidates, element -> element.role);
        Set<String> duplicateArias = findDuplicateValues(candidates, element -> element.aria);
        boolean hasAmbiguousCandidates = !duplicateIds.isEmpty() || !duplicateDataTests.isEmpty()
                || !duplicateDataTestIds.isEmpty() || !duplicateTexts.isEmpty() || !duplicateRoles.isEmpty()
                || !duplicateArias.isEmpty();

        if (hasAmbiguousCandidates) {
            prompt.append("\nNOTE: Some candidate elements below share the same id, data-test/data-testid, ")
                    .append("text, role, or aria value - marked [NOT UNIQUE]. A locator built from a ")
                    .append("[NOT UNIQUE] value, of any kind, could match more than one element on the real ")
                    .append("page. An id or data-test/data-testid value marked [VERIFIED UNIQUE] below has ")
                    .append("been confirmed to appear on exactly one candidate element; prefer building the ")
                    .append("locator from one of those. If only [NOT UNIQUE] properties match and no ")
                    .append("candidate has a [VERIFIED UNIQUE] id/data-test/data-testid, set confidence to ")
                    .append("\"low\" and say so in matchedElement.\n");
        }

        prompt.append("\nCANDIDATE ELEMENTS:\n")
                .append(formatCandidates(candidates, duplicateIds, duplicateDataTests, duplicateDataTestIds,
                        duplicateTexts, duplicateRoles, duplicateArias));
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

    private static String formatCandidates(List<DomElement> candidates, Set<String> duplicateIds,
            Set<String> duplicateDataTests, Set<String> duplicateDataTestIds, Set<String> duplicateTexts,
            Set<String> duplicateRoles, Set<String> duplicateArias) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (DomElement element : candidates) {
            List<String> parts = new ArrayList<>();
            if (notBlank(element.tag)) {
                parts.add("tag=" + element.tag);
            }
            if (notBlank(element.id)) {
                parts.add("id=" + element.id + uniquenessSuffix(element.id, duplicateIds));
            }
            if (notBlank(element.dataTest)) {
                // DomElement.dataTest/dataTestId (see Hooks.captureDomSnapshot) are populated from
                // an element's real data-test/data-testid attributes independently - never a
                // "testId" attribute, which doesn't exist on any real page. Label each as the real
                // attribute it came from so the model builds a selector against a real attribute.
                parts.add("data-test=" + element.dataTest + uniquenessSuffix(element.dataTest, duplicateDataTests));
            }
            if (notBlank(element.dataTestId)) {
                parts.add("data-testid=" + element.dataTestId
                        + uniquenessSuffix(element.dataTestId, duplicateDataTestIds));
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

    // Only called for a non-blank id/data-test/data-testid value (see formatCandidates), so
    // exactly one of these two applies: the code counted it more than once (genuinely ambiguous,
    // same as a duplicate text/role/aria value), or it counted exactly one occurrence, in which
    // case this is real proof of uniqueness - not an assumption "id implies unique" the way this
    // codebase used to treat these two properties.
    private static String uniquenessSuffix(String value, Set<String> duplicates) {
        return duplicates.contains(value) ? " [NOT UNIQUE]" : " [VERIFIED UNIQUE]";
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
