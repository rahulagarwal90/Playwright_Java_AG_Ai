@UI @DemoQA
Feature: DemoQA UI Elements Testing
  As a user on DemoQA
  I want to interact with various input forms and tables
  So that I can verify complete UI testing functionality

  @TextBox
  Scenario: User can successfully fill and submit the text box form
    Given I navigate to the DemoQA Text Box page
    When I fill the form with name "John Doe", email "john@test.com", current address "123 Main St" and permanent address "456 Oak St"
    And I submit the form
    Then I should see the output name containing "Name:John Doe"

  @WebTable
  Scenario: User can manipulate data in Web Tables
    Given I navigate to the DemoQA Web Tables page
    When I add a new record with "Alice", "Smith", "alice@test.com", "25", "10000", "QA"
    Then I should see "alice@test.com" in the web table

  @Dropdown
  Scenario: User can interact with Dropdowns
    Given I navigate to the DemoQA Select Menu page
    When I select the "Blue" option from the Old Style Select Menu
    Then The selected value should be "Blue"

  @StructuralLocator
  Scenario: User can navigate via a left-nav link identified only by text
    Given I navigate to the DemoQA Text Box page
    When I click the Web Tables link in the left navigation
    Then I should be on the Web Tables page
