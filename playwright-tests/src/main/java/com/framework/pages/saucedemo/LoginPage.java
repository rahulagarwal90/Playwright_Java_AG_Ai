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
}
