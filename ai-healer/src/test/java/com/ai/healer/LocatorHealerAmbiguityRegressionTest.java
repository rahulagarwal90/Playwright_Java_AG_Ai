package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ai.healer.ollama.HealerOllamaClient;
import com.ai.healer.ollama.LocatorHealer;
import com.ai.healer.report.TestFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real (not mocked) Ollama calls against whichever model ai-healer/config.properties (or
 * OLLAMA_MODEL, or the hardcoded default) currently resolves to - requires a local `ollama serve`
 * exactly like this module's other real-Ollama entry points (LocatorHealer.heal() in production).
 * Unlike every other LocatorHealerTest case, which mocks HealerOllamaClient to test
 * LocatorHealer's own prompt-building/uniqueness-marking logic in isolation, these three exist to
 * verify actual MODEL BEHAVIOR against that already-correct prompt: given the exact ambiguity
 * shape a real SauceDemo heal produced (CartPage.cartItemName, dataTest="inventory-item-name",
 * sharing its visible text "Sauce Labs Backpack" with a sibling title-link element that has no
 * unique attribute at all), does the model actually follow the prompt's instruction to prefer the
 * unique id/data-test over the ambiguous [NOT UNIQUE] text - plus a genuinely-ambiguous
 * case (no unique attribute anywhere) and a clean fully-unique control, so a regression in either
 * direction (the model ignoring the instruction, or a future prompt change breaking the easy
 * case) would be caught by a real call, not just by asserting on the prompt text sent to a mock.
 */
public class LocatorHealerAmbiguityRegressionTest {

    // Short enough that a hung/unreachable Ollama doesn't stall the whole build waiting to find
    // out - this is just a reachability probe, not the real request.
    private static final Duration REACHABILITY_TIMEOUT = Duration.ofSeconds(2);

    // Skips every test in this class (SKIPPED, not FAILED) when Ollama isn't reachable at
    // whatever HealerOllamaClient.baseUrl() already resolves to - reusing that resolution instead
    // of hardcoding localhost:11434 separately, so this stays correct if that resolution ever
    // changes. Without this, a checkout/CI run with no local Ollama would see these 3 tests fail
    // hard with a connection error instead of a clear "not set up for this" skip.
    @BeforeEach
    void ollamaMustBeReachable() {
        Assumptions.assumeTrue(isOllamaReachable(),
                "Ollama is not reachable at " + HealerOllamaClient.baseUrl()
                        + " - skipping live-model ambiguity regression tests (start it with `ollama serve`).");
    }

    private static boolean isOllamaReachable() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(REACHABILITY_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(HealerOllamaClient.baseUrl()))
                    .timeout(REACHABILITY_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static LocatorHealer newRealHealer() {
        return new LocatorHealer(new HealerOllamaClient(HttpClient.newHttpClient()));
    }

    private static TestFailure failureWithSnapshot(Path snapshotPath, String brokenLocator) {
        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"" + brokenLocator + "\")\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;
        return failure;
    }

    @Test
    void healSelectsUniqueAttributeOverAmbiguousDuplicateText(@TempDir Path tempDir) throws Exception {
        // The exact real shape found on SauceDemo's cart page: a title <a> and the item-name <div>
        // both show "Sauce Labs Backpack", but only the div carries a unique data-test
        // (inventory-item-name). The link has no id/data-test at all, so the shared text is the
        // ONLY thing distinguishing it from the real target - exactly the trap a text-based guess
        // would fall into.
        String snapshotJson = "["
                + "{\"tag\":\"A\",\"id\":null,\"dataTest\":null,\"role\":null,\"aria\":null,\"text\":\"Sauce Labs Backpack\"},"
                + "{\"tag\":\"DIV\",\"id\":null,\"dataTest\":\"inventory-item-name\",\"role\":null,\"aria\":null,\"text\":\"Sauce Labs Backpack\"}"
                + "]";
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, snapshotJson);

        TestFailure failure = failureWithSnapshot(snapshotPath, ".inventory_item_nam");

        LocatorHealer.HealResult result = newRealHealer().heal(failure);

        System.out.println("[TEST] uniqueAttributeOverDuplicateText -> newSelector=" + result.newSelector
                + ", matchedElement=" + result.matchedElement + ", confidence=" + result.confidence);

        // The selector choice must be deterministic-enough to assert on: it has to be built from
        // the unique data-test value, not from the ambiguous shared text.
        assertTrue(result.newSelector.contains("inventory-item-name"),
                "expected newSelector to be built from the unique data-test value; was: " + result.newSelector);
        assertTrue(!result.newSelector.equals("text=Sauce Labs Backpack")
                        && !result.newSelector.equalsIgnoreCase("Sauce Labs Backpack"),
                "newSelector should not be a bare text selector built from the ambiguous shared text; was: "
                        + result.newSelector);
        // Confidence is recorded, not strictly asserted on - it's a real, somewhat subjective model
        // output. Still must be one of the two schema-enforced values.
        assertTrue("high".equals(result.confidence) || "low".equals(result.confidence),
                "confidence must be one of the schema-enforced values; was: " + result.confidence);
    }

    @Test
    void healReportsLowConfidenceWhenNoUniqueAttributeExists(@TempDir Path tempDir) throws Exception {
        // Two "Remove" buttons for two different cart items, neither with an id or data-test at
        // all - genuinely indistinguishable from the DOM snapshot alone. There is no correct
        // confident answer here; the model should say so rather than guess.
        String snapshotJson = "["
                + "{\"tag\":\"BUTTON\",\"id\":null,\"dataTest\":null,\"role\":\"button\",\"aria\":null,\"text\":\"Remove\"},"
                + "{\"tag\":\"BUTTON\",\"id\":null,\"dataTest\":null,\"role\":\"button\",\"aria\":null,\"text\":\"Remove\"}"
                + "]";
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, snapshotJson);

        TestFailure failure = failureWithSnapshot(snapshotPath, "button.remove-item-typo");

        LocatorHealer.HealResult result = newRealHealer().heal(failure);

        System.out.println("[TEST] noUniqueAttribute -> newSelector=" + result.newSelector
                + ", matchedElement=" + result.matchedElement + ", confidence=" + result.confidence);

        assertEquals("low", result.confidence,
                "with no unique attribute anywhere, the model must not claim false certainty; matchedElement was: "
                        + result.matchedElement);
    }

    @Test
    void healRemainsHighConfidenceForFullyUniqueCandidate(@TempDir Path tempDir) throws Exception {
        // Baseline/control: one candidate has a unique data-test, and the only other element in
        // the snapshot shares none of its properties (different tag, different text, no
        // overlapping id/dataTest/role/aria). Included so a future regression in the OTHER
        // direction - the model (or a prompt change) losing confidence on an unambiguous case -
        // would also be caught, not just the ambiguous-case regressions above.
        String snapshotJson = "["
                + "{\"tag\":\"BUTTON\",\"id\":null,\"dataTest\":\"add-to-cart-sauce-labs-backpack\",\"role\":null,\"aria\":null,\"text\":\"Add to cart\"},"
                + "{\"tag\":\"A\",\"id\":null,\"dataTest\":null,\"role\":null,\"aria\":null,\"text\":\"Sauce Labs Backpack\"}"
                + "]";
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, snapshotJson);

        TestFailure failure = failureWithSnapshot(snapshotPath, "[data-test='add-to-cart-sauce-labs-backpack-typo']");

        LocatorHealer.HealResult result = newRealHealer().heal(failure);

        System.out.println("[TEST] cleanUniqueCase -> newSelector=" + result.newSelector
                + ", matchedElement=" + result.matchedElement + ", confidence=" + result.confidence);

        assertTrue(result.newSelector.contains("add-to-cart-sauce-labs-backpack"),
                "expected the correct unique selector; was: " + result.newSelector);
        assertEquals("high", result.confidence,
                "an unambiguous, fully-unique candidate should produce high confidence; matchedElement was: "
                        + result.matchedElement);
    }
}
