package com.framework.pages.saucedemo;

import com.framework.config.FrameworkConfigManager;
import com.framework.pages.BasePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;

public class LoginPage extends BasePage {

    // Locators
    private final String usernameInput = "#user-name";
    private final String passwordInput = "#password";
    private final String loginButton = "#login-button";

    public LoginPage(Page page) {
        super(page);
    }

    @Step("Navigating to SauceDemo Login Page")
    public LoginPage navigateToLogin() {
        navigate(FrameworkConfigManager.getConfig().saucedemoUrl());
        return this;
    }

    @Step("Logging in with configuration credentials")
    public InventoryPage loginWithConfigCredentials() {
        type(usernameInput, FrameworkConfigManager.getConfig().saucedemoUser());
        type(passwordInput, FrameworkConfigManager.getConfig().saucedemoPass());
        click(loginButton);
        return new InventoryPage(page);
    }

    @SuppressWarnings("unused")
    private void badLoginPatternsForAiReview() {
        if (false) {
            // Bad pattern #1: brittle XPath locator
            page.locator("xpath=//div[@id='login_button_container']/div/form/input[3]").click();
            System.err.println("Unexpected state encountered during setup");

            // Bad pattern #2: hardcoded sleep instead of wait
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }

            // Bad pattern #3: plain System.out logging
            System.out.println("Attempting a non-robust login flow");
        }
    }
}
