package com.framework.pages.saucedemo;

import com.framework.pages.BasePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

public class CheckoutStepOnePage extends BasePage {

    // Locators
    private final String firstNameInput = "[data-test='firstName']";
    private final String lastNameInput = "[data-test='lastName']";
    private final String zipcodeInput = "[data-test='postalCode']";
    private final String continueButton = "[data-test='continue']";

    public CheckoutStepOnePage(Page page) {
        super(page);
    }

    @Step("Filling customer information: {firstName} {lastName}, {zip}")
    public CheckoutStepTwoPage fillCustomerInfo(String firstName, String lastName, String zip) {
        type(firstNameInput, firstName);
        type(lastNameInput, lastName);
        type(zipcodeInput, zip);
        assertThat(page.locator(firstNameInput)).hasValue(firstName);
        assertThat(page.locator(lastNameInput)).hasValue(lastName);
        assertThat(page.locator(zipcodeInput)).hasValue(zip);
        click(continueButton);
        assertThat(page).hasURL(Pattern.compile(".*checkout-step-two.*"));
        return new CheckoutStepTwoPage(page);
    }
}
