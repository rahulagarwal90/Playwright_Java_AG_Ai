package com.framework.pages.saucedemo;

import com.framework.pages.BasePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class CheckoutCompletePage extends BasePage {

    // Locators
    private final String completeHeader = "[data-test='complete-header']";

    public CheckoutCompletePage(Page page) {
        super(page);
    }

    @Step("Verifying successful order placement")
    public void verifySuccessfulOrder() {
        logger.info("Verifying order completion message...");
        assertThat(page.locator(completeHeader)).hasText("Thank you for your order!");
    }
}
