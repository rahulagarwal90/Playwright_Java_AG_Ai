package com.framework.pages.saucedemo;

import com.framework.pages.BasePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class CheckoutStepTwoPage extends BasePage {

    // Locators
    private final String finishButton = "[data-test=finish]";
    private final String itemTotalLabel = ".summary_subtotal_label";

    public CheckoutStepTwoPage(Page page) {
        super(page);
    }

    @Step("Verifying item total is displayed")
    public void verifyItemTotalIsDisplayed() {
        logger.info("Verifying item total label visibility...");
        assertThat(page.locator(itemTotalLabel)).isVisible();
        assertThat(page.locator(itemTotalLabel)).hasText("Item total: $29.99");
    }

    @Step("Clicking on finish button")
    public CheckoutCompletePage clickFinish() {
        click(finishButton);
        return new CheckoutCompletePage(page);
    }
}
