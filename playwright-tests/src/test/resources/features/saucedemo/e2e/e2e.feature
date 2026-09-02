@UI @SauceDemo
Feature: End-to-End Customer Purchasing Flow
  As a SauceDemo Customer
  I want to browse the inventory, add items to my cart, and complete a purchase
  So I can successfully buy products

  @E2E
  Scenario: Standard Customer Complete Purchase Flow
    Given I navigate to SauceDemo Login
    And I attempt login with config credentials
    When I add the "Sauce Labs Backpack" to my cart
    And I go to the cart
    Then I should see "Sauce Labs Backpack" in the cart
    When I proceed to checkout
    And I enter my shipping information "Jane", "Doe", and "12345"
    And I confirm my order
    Then I should see the order completion message
