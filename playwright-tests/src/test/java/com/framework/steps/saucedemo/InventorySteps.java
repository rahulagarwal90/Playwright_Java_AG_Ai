package com.framework.steps.saucedemo;

import com.framework.context.TestContext;
import com.framework.pages.saucedemo.InventoryPage;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class InventorySteps {

    private InventoryPage inventoryPage;

    public InventorySteps(TestContext testContext) {
        this.inventoryPage = new InventoryPage(testContext.getPage());
    }

    @When("I add the {string} to my cart")
    public void iAddTheToMyCart(String productName) {
        inventoryPage.addProductToCart(productName);
    }

    @When("I go to the cart")
    public void iGoToTheCart() {
        inventoryPage.navigateToCart();
    }

    @When("I add Sauce Labs Backpack to my cart using its dedicated button")
    public void iAddSauceLabsBackpackToMyCartUsingItsDedicatedButton() {
        inventoryPage.addBackpackToCart();
    }

    @When("I add Sauce Labs Bike Light to my cart using its dedicated button")
    public void iAddSauceLabsBikeLightToMyCartUsingItsDedicatedButton() {
        inventoryPage.addBikeLightToCart();
    }

    @Then("Sauce Labs Backpack should show as added to the cart")
    public void sauceLabsBackpackShouldShowAsAddedToTheCart() {
        inventoryPage.verifyBackpackAddedToCart();
    }

    @Then("Sauce Labs Bike Light should show as added to the cart")
    public void sauceLabsBikeLightShouldShowAsAddedToTheCart() {
        inventoryPage.verifyBikeLightAddedToCart();
    }
}
