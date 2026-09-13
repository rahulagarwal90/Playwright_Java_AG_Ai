package com.framework.pages;

import com.framework.config.FrameworkConfigManager;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;

public class DemoQaPage extends BasePage {

    // Locators
    private final String fullNameInput = "#userName";
    private final String emailInput = "#userEmail";
    private final String currentAddressInput = "#currentAddress";
    private final String permanentAddressInput = "#permanentAddress";
    private final String submitButton = "#submit";
    private final String outputName = "#output #name";
    private final String webTablesNavLink = "text=Web Tables";

    public DemoQaPage(Page page) {
        super(page);
    }

    @Step("Navigating to DemoQA Text Box page")
    public void navigateToTextBoxPage() {
        String url = FrameworkConfigManager.getConfig().baseUrl() + FrameworkConfigManager.getConfig().demoQaTextBoxPath();
        navigate(url);
    }

    @Step("Filling the form with name: {name}, email: {email}")
    public void fillForm(String name, String email, String currentAddress, String permanentAddress) {
        type(fullNameInput, name);
        type(emailInput, email);
        type(currentAddressInput, currentAddress);
        type(permanentAddressInput, permanentAddress);
    }

    @Step("Submitting the form")
    public void submitForm() {
        click(submitButton);
    }

    @Step("Getting the output name")
    public String getOutputName() {
        return getText(outputName);
    }

    @Step("Navigating to DemoQA Web Tables page")
    public void navigateToWebTablesPage() {
        String url = FrameworkConfigManager.getConfig().baseUrl() + FrameworkConfigManager.getConfig().demoQaWebTablesPath();
        navigate(url);
    }

    @Step("Adding a new record: {email}")
    public void addNewRecord(String firstName, String lastName, String email, String age, String salary, String department) {
        click("#addNewRecordButton");
        type("#firstName", firstName);
        type("#lastName", lastName);
        type("#userEmail", email);
        type("#age", age);
        type("#salary", salary);
        type("#department", department);
        click("#submit");
    }

    @Step("Verifying if email {email} is in web table")
    public boolean isEmailInWebTable(String email) {
        return isVisible("text=" + email);
    }

    @Step("Navigating to DemoQA Select Menu page")
    public void navigateToSelectMenuPage() {
        String url = FrameworkConfigManager.getConfig().baseUrl() + FrameworkConfigManager.getConfig().demoQaSelectMenuPath();
        navigate(url);
    }

    @Step("Selecting option {optionText} from Old Style Select Menu")
    public void selectOldStyleOption(String optionText) {
        selectFromDropdown("#oldSelectMenu", optionText);
    }

    @Step("Getting selected option from Old Style Select Menu")
    public String getSelectedOldStyleOption() {
        return page.locator("#oldSelectMenu").evaluate("el => el.options[el.selectedIndex].text").toString();
    }

    @Step("Clicking the Web Tables link in the left navigation")
    public void clickWebTablesNavLink() {
        click(webTablesNavLink);
    }

    @Step("Verifying the browser is on the Web Tables page")
    public boolean isOnWebTablesPage() {
        return page.url().contains("/webtables");
    }
}
