package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ScenarioNameSanitizerTest {

    @Test
    void replacesNonAlphanumericCharactersWithUnderscore() {
        assertEquals(
                "Standard_User_can_login_successfully",
                ScenarioNameSanitizer.sanitize("Standard User can login successfully"));
    }

    @Test
    void preservesExistingHyphensAndUnderscores() {
        assertEquals("already-safe_name", ScenarioNameSanitizer.sanitize("already-safe_name"));
    }
}
