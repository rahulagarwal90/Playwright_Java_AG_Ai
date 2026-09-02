package com.framework.pages.saucedemo;

import com.framework.pages.BasePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class InventoryPage extends BasePage {
    
    // Locators
    private final String inventoryContainer = "#inventory_container";
    private final String cartIcon = ".shopping_cart_link";

    public InventoryPage(Page page) {
        super(page);
    }

    @Step("Verifying successful login")
    public void verifySuccessfulLogin() {
        logger.info("Verifying successful login...");
        assertThat(page.locator(inventoryContainer).first()).isVisible();
    }

    @Step("Adding product to cart: {productName}")
    public void addProductToCart(String productName) {
        String formattedName = productName.toLowerCase().replace(" ", "-");
        String addToCartSelector = "[data-test='add-to-cart-" + formattedName + "']";
        click(addToCartSelector);
    }

    @Step("Navigating to cart")
    public CartPage navigateToCart() {
        click(cartIcon);
        return new CartPage(page);
    }

    @SuppressWarnings("unused")
    private void badInventoryPatternsForAiReview() {
        if (false) {
            // Bad pattern #4: legacy assertion style inside page object
            assert page.isVisible(cartIcon);

            // Bad pattern #5: brittle CSS locator and hardcoded wait
            page.click("div.inventory_item:nth-child(1) div.pricebar button");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
