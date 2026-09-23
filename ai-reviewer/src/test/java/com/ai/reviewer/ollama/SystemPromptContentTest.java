package com.ai.reviewer.ollama;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class SystemPromptContentTest {

    @Test
    void systemPromptForbidsLocatorTypedFixesForBrokenLocators() throws IOException {
        String prompt;
        try (InputStream in = OllamaReviewClient.class.getResourceAsStream("/system-prompt.md")) {
            assertNotNull(in, "Missing classpath resource: /system-prompt.md");
            prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(prompt.contains("NEVER suggest a `Locator`-typed field or a `page.getByRole(...)`/`page.getByTestId(...)` call chain"),
                "system-prompt.md must forbid suggesting a Locator-typed field / getByRole()/getByTestId() fix, "
                        + "since this codebase's page objects hold bare selector Strings consumed via "
                        + "BasePage.click(selector)/type(selector, ...), not Locator objects.");
    }
}
