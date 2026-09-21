package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.healer.ollama.HealerOllamaClient;
import com.ai.healer.ollama.LocatorHealer;
import com.ai.healer.report.TestFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

public class LocatorHealerTest {

    private static final String SNAPSHOT_JSON = "["
            + "{\"tag\":\"INPUT\",\"id\":\"login-button\",\"dataTest\":\"login-button\",\"role\":null,\"aria\":null,\"text\":\"\"},"
            + "{\"tag\":\"INPUT\",\"id\":\"user-name\",\"dataTest\":\"username\",\"role\":null,\"aria\":null,\"text\":\"\"}"
            + "]";

    @Test
    void extractsLocatorAndBuildsPromptFromRealCandidates(@TempDir Path tempDir) throws Exception {
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, SNAPSHOT_JSON);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"#login-button-BROKEN-TEMP\")\n";
        // Real shape: BasePage's wrapper is always the first com.framework frame in the trace,
        // but it's the same generic click() line for every locator failure in the suite - the
        // concrete page object frame right after it is the actually useful one.
        failure.stackTrace = "\tat com.framework.pages.BasePage.click(BasePage.java:34)\n"
                + "\tat com.framework.pages.saucedemo.LoginPage.loginWithConfigCredentials(LoginPage.java:29)\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"#login-button\","
                        + "\"matchedElement\":\"id=login-button testId=login-button\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        LocatorHealer.HealResult result = healer.heal(failure);

        assertEquals("#login-button", result.newSelector);
        assertEquals("id=login-button testId=login-button", result.matchedElement);
        assertEquals("high", result.confidence);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(any(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();

        // The broken locator and the real candidate elements must both be in the prompt sent to
        // Ollama, and the file/line context extracted from the stack trace too.
        assertTrue(userPrompt.contains("#login-button-BROKEN-TEMP"));
        assertTrue(userPrompt.contains("id=login-button"));
        assertTrue(userPrompt.contains("data-test=username"));
        assertTrue(userPrompt.contains("LoginPage.java:29"));
        assertTrue(!userPrompt.contains("BasePage.java"), "should skip BasePage's generic wrapper frame in favor of the concrete page object frame");

        // The result also needs a resolvable path for PageObjectPatcher to act on. This exercises
        // the real repo's LoginPage.java, which (assuming it isn't mid-edit by another test right
        // now) doesn't actually declare a field valued "#login-button-BROKEN-TEMP" - so this
        // covers the fallback-to-call-site-line path; extractPageObjectLocationFindsTheActual
        // FieldDeclarationLine below covers the declaration-found path in isolation.
        assertEquals(29, result.lineNumber);
        assertTrue(result.filePath.toString().endsWith(
                        Path.of("playwright-tests", "src", "main", "java", "com", "framework", "pages",
                                "saucedemo", "LoginPage.java").toString()),
                "expected filePath to resolve to LoginPage.java, was: " + result.filePath);
    }

    @Test
    void flagsNonUniquePropertiesButLeavesUniqueOnesUnmarked(@TempDir Path tempDir) throws Exception {
        // Two SauceDemo-style "Add to cart" buttons sharing the same visible text but each with
        // its own dataTest - the exact shape of ambiguity a real inventory page produces.
        String snapshotJson = "["
                + "{\"tag\":\"BUTTON\",\"id\":null,\"dataTest\":\"add-to-cart-sauce-labs-backpack\",\"role\":null,\"aria\":null,\"text\":\"Add to cart\"},"
                + "{\"tag\":\"BUTTON\",\"id\":null,\"dataTest\":\"add-to-cart-sauce-labs-bike-light\",\"role\":null,\"aria\":null,\"text\":\"Add to cart\"}"
                + "]";
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, snapshotJson);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"#add-to-cart-sauce-labs-backpack-BROKEN\")\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"[data-test='add-to-cart-sauce-labs-backpack']\","
                        + "\"matchedElement\":\"testId=add-to-cart-sauce-labs-backpack\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        healer.heal(failure);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(any(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();

        // Both dataTests are unique, so they must NOT be flagged, but the shared text must be. The
        // label itself must describe the real HTML attribute (data-test), not the Java field name
        // "testId"/"dataTest" - that's not a real attribute and would produce a dead selector.
        assertTrue(userPrompt.contains("data-test=add-to-cart-sauce-labs-backpack"));
        assertTrue(userPrompt.contains("data-test=add-to-cart-sauce-labs-bike-light"));
        assertTrue(!userPrompt.contains("data-test=add-to-cart-sauce-labs-backpack [NOT UNIQUE]"));
        assertTrue(!userPrompt.contains("data-test=add-to-cart-sauce-labs-bike-light [NOT UNIQUE]"));
        assertTrue(!userPrompt.contains("testId="), "the raw Java field name \"testId\" must never appear as a prompt label");
        assertTrue(!userPrompt.contains("dataTest="), "the raw Java field name \"dataTest\" must never appear as a prompt label");
        assertTrue(userPrompt.contains("text=\"Add to cart\" [NOT UNIQUE]"),
                "shared text should be flagged [NOT UNIQUE]; prompt was:\n" + userPrompt);
        assertTrue(userPrompt.contains("NOTE:"), "an ambiguity note should be added when candidates share a property");
    }

    @Test
    void doesNotFlagAnythingWhenNoCandidatesShareAProperty(@TempDir Path tempDir) throws Exception {
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, SNAPSHOT_JSON);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"#login-button-BROKEN-TEMP\")\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"#login-button\","
                        + "\"matchedElement\":\"id=login-button\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        healer.heal(failure);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(any(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();

        assertTrue(!userPrompt.contains("[NOT UNIQUE]"));
        assertTrue(!userPrompt.contains("NOTE:"));
    }

    @Test
    void marksGenuinelyUniqueIdAndDataTestValuesAsVerifiedUnique(@TempDir Path tempDir) throws Exception {
        // SNAPSHOT_JSON's two elements have distinct id AND dataTest values - real proof of
        // uniqueness (the code counted exactly one occurrence of each), not merely "id/data-test
        // was never checked so assume it's fine" the way this prompt used to be built.
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, SNAPSHOT_JSON);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"#login-button-BROKEN-TEMP\")\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"#login-button\","
                        + "\"matchedElement\":\"id=login-button\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        healer.heal(failure);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(any(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();

        assertTrue(userPrompt.contains("id=login-button [VERIFIED UNIQUE]"),
                "a genuinely unique id should be marked [VERIFIED UNIQUE], not just left unmarked; prompt was:\n" + userPrompt);
        assertTrue(userPrompt.contains("data-test=login-button [VERIFIED UNIQUE]"),
                "a genuinely unique data-test should be marked [VERIFIED UNIQUE]; prompt was:\n" + userPrompt);
    }

    @Test
    void flagsGenuinelyDuplicateIdAndDataTestValuesAsNotUnique(@TempDir Path tempDir) throws Exception {
        // The rare real case Task 1 calls out explicitly: two elements that DO share the same
        // data-test value (e.g. a markup bug, or a template rendered twice). Before this change
        // id/data-test/data-testid were never checked at all and would have been treated as
        // unique by convention regardless - this must now be caught exactly like duplicate text.
        String snapshotJson = "["
                + "{\"tag\":\"BUTTON\",\"id\":null,\"dataTest\":\"duplicate-test-id\",\"role\":null,\"aria\":null,\"text\":\"Remove\"},"
                + "{\"tag\":\"BUTTON\",\"id\":null,\"dataTest\":\"duplicate-test-id\",\"role\":null,\"aria\":null,\"text\":\"Remove\"}"
                + "]";
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, snapshotJson);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"button.remove-item-typo\")\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"[data-test='duplicate-test-id']\","
                        + "\"matchedElement\":\"data-test=duplicate-test-id [NOT UNIQUE]\",\"confidence\":\"low\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        LocatorHealer.HealResult result = healer.heal(failure);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(any(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();

        assertTrue(userPrompt.contains("data-test=duplicate-test-id [NOT UNIQUE]"),
                "a genuinely duplicate data-test must be flagged [NOT UNIQUE], never assumed unique; prompt was:\n"
                        + userPrompt);
        // The NOTE explanation text itself legitimately mentions the [VERIFIED UNIQUE] marker (to
        // explain what it means), so check the CANDIDATE ELEMENTS listing specifically - neither
        // candidate here has anything genuinely unique to be marked with.
        String candidateListing = userPrompt.substring(userPrompt.indexOf("CANDIDATE ELEMENTS:"));
        assertTrue(!candidateListing.contains("[VERIFIED UNIQUE]"),
                "no property is actually unique here, so no candidate line should be marked [VERIFIED UNIQUE]; "
                        + "candidate listing was:\n" + candidateListing);
        assertTrue(userPrompt.contains("NOTE:"), "an ambiguity note should be added when candidates share a property");
        // The model's own confidence choice ("low", since nothing here is genuinely unique) is
        // just passed through unchanged - LocatorHealer doesn't second-guess the model's answer,
        // it only guarantees the prompt reflects reality.
        assertEquals("low", result.confidence);
    }

    @Test
    void extractPageObjectLocationFindsTheActualFieldDeclarationLine(@TempDir Path tempDir) throws IOException {
        // Isolated from the real repo: builds a fake playwright-tests/src/main/java tree under a
        // temp "repo root" so this doesn't depend on what this repo's real page objects contain.
        Path pageFile = tempDir.resolve("playwright-tests/src/main/java/com/framework/pages/saucedemo/LoginPage.java");
        Files.createDirectories(pageFile.getParent());
        Files.writeString(pageFile,
                "package com.framework.pages.saucedemo;\n"
                + "public class LoginPage extends BasePage {\n"
                + "    private final String usernameInput = \"#user-name\";\n"
                + "    private final String loginButton = \"#login-button-BROKEN-TEMP\";\n"
                + "    public InventoryPage loginWithConfigCredentials() {\n"
                + "        click(loginButton);\n"
                + "    }\n"
                + "}\n");

        // The stack frame's line number (6) is the click() call site, not the field (4).
        String stackTrace = "\tat com.framework.pages.BasePage.click(BasePage.java:34)\n"
                + "\tat com.framework.pages.saucedemo.LoginPage.loginWithConfigCredentials(LoginPage.java:6)\n";

        LocatorHealer.PageObjectLocation location =
                LocatorHealer.extractPageObjectLocation(stackTrace, "#login-button-BROKEN-TEMP", tempDir);

        assertEquals(pageFile, location.filePath());
        assertEquals(4, location.lineNumber(), "should report the field declaration line, not the call site line");
        assertEquals("loginButton", location.fieldName());
    }

    @Test
    void extractsFullLocatorWhenItContainsAnInternalQuote(@TempDir Path tempDir) throws Exception {
        // Real broken locator captured from CartPage.checkoutButton: "[datatest='checkout']" -
        // a single-quoted attribute value sitting inside Playwright's own double-quoted call log
        // wrapper. The old [^"']+ character class stopped at the embedded ', truncating the
        // capture to "[datatest=" and making this un-healable. Confirmed for real: extraction
        // now returns the full, correct broken locator string.
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, SNAPSHOT_JSON);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"[datatest='checkout']\")\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"#login-button\","
                        + "\"matchedElement\":\"id=login-button\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        LocatorHealer.HealResult result = healer.heal(failure);

        assertEquals("[datatest='checkout']", result.brokenLocator);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(any(), userPromptCaptor.capture());
        assertTrue(userPromptCaptor.getValue().contains("BROKEN LOCATOR: [datatest='checkout']"));
    }

    @Test
    void extractsSelectorWithNoLocatorWrapperInCallLog(@TempDir Path tempDir) throws Exception {
        // Real broken locator captured from CheckoutStepOnePage.continueButton:
        // "[data-test=continue']" (unterminated quote) - Playwright's own CSS parser rejects it
        // before it can build a locator descriptor, so the call log has no "locator(...)"
        // wrapper at all, just "waiting for <selector>". The standard LOCATOR_IN_CALL_LOG regex
        // can't match this shape; extraction must fall back to the unwrapped pattern.
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, SNAPSHOT_JSON);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Error {\n"
                + "  message='Unexpected token \"\" while parsing selector \"[data-test=continue']\"\n"
                + "}\n"
                + "Call log:\n"
                + "- waiting for [data-test=continue']\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"#login-button\","
                        + "\"matchedElement\":\"id=login-button\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        LocatorHealer.HealResult result = healer.heal(failure);

        assertEquals("[data-test=continue']", result.brokenLocator);
    }

    @Test
    void systemPromptPrefersDataTestOverIdWhenBothPresent(@TempDir Path tempDir) throws Exception {
        // Fix 1: this codebase's established convention (InventoryPage.addProductToCart()
        // builds a [data-test='...'] selector by hand) should be reflected as an explicit rule
        // in the prompt, not left to the model's free choice - a real prior run produced
        // "#checkout" instead of the preferred "[data-test='checkout']" for a candidate that had
        // both attributes.
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, SNAPSHOT_JSON);

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Call log:\n- waiting for locator(\"#login-button-BROKEN-TEMP\")\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        HealerOllamaClient mockClient = mock(HealerOllamaClient.class);
        when(mockClient.suggestLocator(anyString(), anyString()))
                .thenReturn("{\"newSelector\":\"#login-button\","
                        + "\"matchedElement\":\"id=login-button\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        healer.heal(failure);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(systemPromptCaptor.capture(), any());
        String systemPrompt = systemPromptCaptor.getValue();

        assertTrue(systemPrompt.contains("data-test") && systemPrompt.toLowerCase().contains("prefer"),
                "expected the system prompt to instruct a data-test/data-testid preference over id; was:\n" + systemPrompt);
    }

    @Test
    void throwsWhenNoDomSnapshotFound() {
        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.domSnapshotFound = false;

        LocatorHealer healer = new LocatorHealer(mock(HealerOllamaClient.class));

        assertThrows(IllegalStateException.class, () -> healer.heal(failure));
    }

    @Test
    void throwsWhenFailureMessageHasNoLocatorCallLog(@TempDir Path tempDir) throws IOException {
        Path snapshotPath = tempDir.resolve("Some_Scenario-dom.json");
        Files.writeString(snapshotPath, "[]");

        TestFailure failure = new TestFailure();
        failure.testName = "Some Scenario";
        failure.failureMessage = "Timeout 30000ms exceeded.\nCall log:\n- navigating to \"https://x\"\n";
        failure.domSnapshotPath = snapshotPath;
        failure.domSnapshotFound = true;

        LocatorHealer healer = new LocatorHealer(mock(HealerOllamaClient.class));

        assertThrows(IllegalStateException.class, () -> healer.heal(failure));
    }
}
