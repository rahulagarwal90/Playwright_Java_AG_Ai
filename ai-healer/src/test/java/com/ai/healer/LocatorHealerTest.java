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
                .thenReturn("{\"newLocatorCode\":\"page.locator(\\\"#login-button\\\")\","
                        + "\"matchedElement\":\"id=login-button testId=login-button\",\"confidence\":\"high\"}");

        LocatorHealer healer = new LocatorHealer(mockClient);
        LocatorHealer.HealResult result = healer.heal(failure);

        assertEquals("page.locator(\"#login-button\")", result.newLocatorCode);
        assertEquals("id=login-button testId=login-button", result.matchedElement);
        assertEquals("high", result.confidence);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockClient).suggestLocator(any(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();

        // The broken locator and the real candidate elements must both be in the prompt sent to
        // Ollama, and the file/line context extracted from the stack trace too.
        assertTrue(userPrompt.contains("#login-button-BROKEN-TEMP"));
        assertTrue(userPrompt.contains("id=login-button"));
        assertTrue(userPrompt.contains("testId=username"));
        assertTrue(userPrompt.contains("LoginPage.java:29"));
        assertTrue(!userPrompt.contains("BasePage.java"), "should skip BasePage's generic wrapper frame in favor of the concrete page object frame");
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
