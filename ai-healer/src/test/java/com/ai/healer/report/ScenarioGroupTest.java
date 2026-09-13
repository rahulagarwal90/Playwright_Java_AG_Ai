package com.ai.healer.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ScenarioGroupTest {

    @Test
    void flatBranchNameLowercasesAndReplacesNonAlphanumericsWithHyphens() {
        ScenarioGroup group = new ScenarioGroup(Path.of("saucedemo", "cart", "cart.feature"), List.of());

        assertEquals("cart-feature", group.flatBranchName());
    }

    @Test
    void flatBranchNameIgnoresTheFolderPathOfTheFeatureFile() {
        ScenarioGroup deep = new ScenarioGroup(Path.of("a", "b", "c", "Login_Flow.feature"), List.of());

        assertEquals("login-flow-feature", deep.flatBranchName());
    }
}
