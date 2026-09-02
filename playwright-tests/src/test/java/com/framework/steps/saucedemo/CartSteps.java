package com.framework.steps.saucedemo;

import com.framework.context.TestContext;
import com.framework.pages.saucedemo.CartPage;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class CartSteps {

    private CartPage cartPage;

    public CartSteps(TestContext testContext) {
        this.cartPage = new CartPage(testContext.getPage());
    }

    @When("I proceed to checkout")
    public void iProceedToCheckout() {
        cartPage.clickCheckout();
    }

    @Then("I should see {string} in the cart")
    public void iShouldSeeInTheCart(String productName) {
        cartPage.verifyItemInCart(productName);
    }
}
