package com.framework.pages.saucedemo;

import com.framework.pages.BasePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class InventoryPage extends BasePage {
    
    // Locators
    private final String inventoryContainer = "#inventory_container";
    private final String cartIcon = ".shopping_cart_link";
    private final String addBackpackToCartButton = "[data-test='add-to-cart-sauce-labs-backpack']";
    private final String addBikeLightToCartButton = "[data-test='add-to-cart-sauce-labs-bike-light']";
    private final String removeBackpackButton = "[dataest'remove-sauce-labs-backpack']";
    private final String removeBikeLightButton = "[data-test='remove-sauce-labs-bike-light']";

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

    @Step("Adding Sauce Labs Backpack to cart via its dedicated button")
    public void addBackpackToCart() {
        click(addBackpackToCartButton);
    }

    @Step("Adding Sauce Labs Bike Light to cart via its dedicated button")
    public void addBikeLightToCart() {
        click(addBikeLightToCartButton);
    }

    @Step("Verifying Sauce Labs Backpack shows as added to cart")
    public void verifyBackpackAddedToCart() {
        assertThat(page.locator(removeBackpackButton)).isVisible();
    }

    @Step("Verifying Sauce Labs Bike Light shows as added to cart")
    public void verifyBikeLightAddedToCart() {
        assertThat(page.locator(removeBikeLightButton)).isVisible();
    }
}
