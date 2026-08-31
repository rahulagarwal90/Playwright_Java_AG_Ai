package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

public class LocatorHealerTest {

    private static final String SNAPSHOT_JSON = "["
            + "{\"tag\":\"INPUT\",\"id\":\"login-button\",\"testId\":\"login-button\",\"role\":null,\"aria\":null,\"text\":\"\"},"
            + "{\"tag\":\"INPUT\",\"id\":\"user-name\",\"testId\":\"username\",\"role\":null,\"aria\":null,\"text\":\"\"}"
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
        assertTrue(userPrompt.contains("data-test/data-testid=username"));
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
        // its own testId - the exact shape of ambiguity a real inventory page produces.
        String snapshotJson = "["
                + "{\"tag\":\"BUTTON\",\"id\":null,\"testId\":\"add-to-cart-sauce-labs-backpack\",\"role\":null,\"aria\":null,\"text\":\"Add to cart\"},"
                + "{\"tag\":\"BUTTON\",\"id\":null,\"testId\":\"add-to-cart-sauce-labs-bike-light\",\"role\":null,\"aria\":null,\"text\":\"Add to cart\"}"
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

        // Both testIds are unique, so they must NOT be flagged, but the shared text must be. The
        // label itself must describe the real HTML attribute (data-test/data-testid), not the
        // Java field name "testId" - that's not a real attribute and would produce a dead selector.
        assertTrue(userPrompt.contains("data-test/data-testid=add-to-cart-sauce-labs-backpack"));
        assertTrue(userPrompt.contains("data-test/data-testid=add-to-cart-sauce-labs-bike-light"));
        assertTrue(!userPrompt.contains("data-test/data-testid=add-to-cart-sauce-labs-backpack [NOT UNIQUE]"));
        assertTrue(!userPrompt.contains("data-test/data-testid=add-to-cart-sauce-labs-bike-light [NOT UNIQUE]"));
        assertTrue(!userPrompt.contains("testId="), "the raw Java field name \"testId\" must never appear as a prompt label");
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
