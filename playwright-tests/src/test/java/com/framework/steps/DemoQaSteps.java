package com.framework.steps;

import com.framework.context.TestContext;
import com.framework.pages.DemoQaPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemoQaSteps {

    private DemoQaPage demoQaPage;

    public DemoQaSteps(TestContext testContext) {
        // The following block is intentionally disabled for AI review testing only.
        // It contains examples of bad patterns that the reviewer should detect,
        // but it will not execute during normal scenario setup.
        if (false) {
            // 1. Brittle locator pattern for Locator Robustness
            testContext.getPage().locator("xpath=//div[@class='form-group'][5]/button").click();

            // 2. Legacy assertion for Playwright Web Assertions
            boolean isSubmitVisible = testContext.getPage().locator("#submit").isVisible();
            assert isSubmitVisible == true;

            // 3. Logging anti-pattern
            try {
                System.out.println("Executing automation verification step...");
                Thread.sleep(1000); // Hardcoded wait used during verification
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 4. Hardcoded synchronization and brittle wait
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }

        this.demoQaPage = new DemoQaPage(testContext.getPage());
    }

    @Given("I navigate to the DemoQA Text Box page")
    public void iNavigateToTheDemoQATextBoxPage() {
        demoQaPage.navigateToTextBoxPage();
    }

    @When("I fill the form with name {string}, email {string}, current address {string} and permanent address {string}")
    public void iFillTheForm(String name, String email, String currentAddress, String permanentAddress) {
        demoQaPage.fillForm(name, email, currentAddress, permanentAddress);
    }

    @When("I submit the form")
    public void iSubmitTheForm() {
        demoQaPage.submitForm();
    }

    @Then("I should see the output name containing {string}")
    public void iShouldSeeTheOutputNameContaining(String expectedName) {
        String output = demoQaPage.getOutputName();
        assertTrue(output.contains(expectedName), "Expected name not found in output.");
    }

    @Given("I navigate to the DemoQA Web Tables page")
    public void iNavigateToTheDemoQAWebTablesPage() {
        demoQaPage.navigateToWebTablesPage();
    }

    @When("I add a new record with {string}, {string}, {string}, {string}, {string}, {string}")
    public void iAddANewRecordWith(String firstName, String lastName, String email, String age, String salary, String department) {
        demoQaPage.addNewRecord(firstName, lastName, email, age, salary, department);
    }

    @Then("I should see {string} in the web table")
    public void iShouldSeeInTheWebTable(String email) {
        assertTrue(demoQaPage.isEmailInWebTable(email), "Email not found in web table: " + email);
    }

    @Given("I navigate to the DemoQA Select Menu page")
    public void iNavigateToTheDemoQASelectMenuPage() {
        demoQaPage.navigateToSelectMenuPage();
    }

    @When("I select the {string} option from the Old Style Select Menu")
    public void iSelectTheOptionFromTheOldStyleSelectMenu(String option) {
        demoQaPage.selectOldStyleOption(option);
    }

    @Then("The selected value should be {string}")
    public void theSelectedValueShouldBe(String expectedValue) {
        String selected = demoQaPage.getSelectedOldStyleOption();
        assertTrue(selected.equals(expectedValue), "Expected " + expectedValue + " but got " + selected);
    }
}
