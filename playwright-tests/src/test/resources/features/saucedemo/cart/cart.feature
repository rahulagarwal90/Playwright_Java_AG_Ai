@UI @SauceDemo
Feature: Shopping Cart Near-Duplicate Locators
  As a SauceDemo Customer
  I want to add multiple products whose "Add to cart" buttons have very similar data-test values
  So each product is added correctly, not confused with a similar sibling

  @NearDuplicateLocators
  Scenario: Adding two products with near-identical add-to-cart selectors keeps each one distinct
    Given I navigate to SauceDemo Login
    And I attempt login with config credentials
    When I add Sauce Labs Backpack to my cart using its dedicated button
    And I add Sauce Labs Bike Light to my cart using its dedicated button
    Then Sauce Labs Backpack should show as added to the cart
    And Sauce Labs Bike Light should show as added to the cart
