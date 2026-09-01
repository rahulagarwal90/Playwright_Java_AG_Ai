package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ai.healer.FailureClassifier.Classification;
import org.junit.jupiter.api.Test;

public class FailureClassifierTest {

    // Captured verbatim from a real Surefire run against this repo's own login.feature, with
    // LoginPage's loginButton locator temporarily broken to "#login-button-BROKEN-TEMP".
    private static final String REAL_LOCATOR_NOT_FOUND_MESSAGE =
            "Error {\n"
            + "  message='Timeout 30000ms exceeded.\n"
            + "  name='TimeoutError\n"
            + "  stack='TimeoutError: Timeout 30000ms exceeded.\n"
            + "    at ProgressController.run (.../progress.js:78:26)\n"
            + "    at Frame.click (.../frames.js:996:23)\n"
            + "    at FrameDispatcher.click (.../frameDispatcher.js:158:30)\n"
            + "    at FrameDispatcher._handleCommand (.../dispatcher.js:94:40)\n"
            + "    at DispatcherConnection.dispatch (.../dispatcher.js:365:39)\n"
            + "}\n"
            + "Call log:\n"
            + "- waiting for locator(\"#login-button-BROKEN-TEMP\")\n";

    // Captured verbatim from the same feature with the locator left alone and a deliberate
    // Assertions.assertEquals("wrong-expected-value", "actual-value", ...) added to the Then step.
    private static final String REAL_ASSERTION_FAILURE_MESSAGE =
            "TEMP: deliberate assertion failure for FailureClassifier testing "
            + "==> expected: <wrong-expected-value> but was: <actual-value>";

    // Captured verbatim from a real run with InventoryPage's inventoryContainer field broken to
    // "#nventory_container" (typo). assertThat(page.locator(inventoryContainer).first()).isVisible()
    // times out through Playwright's web-first assertion API, not a raw click()/fill() - a
    // completely different exception type (AssertionFailedError, not TimeoutError) for the exact
    // same underlying problem: the locator never resolves.
    private static final String REAL_VISIBILITY_ASSERTION_NOT_FOUND_MESSAGE =
            "Locator expected to be visible\n"
            + "Call log:\n"
            + "Locator.expect with timeout 5000ms\n"
            + "waiting for locator(\"#nventory_container\").first()\n";

    // Captured verbatim from a real run with CheckoutStepTwoPage's itemTotalLabel field broken to
    // "ummary_subtotal_label" (typo, missing leading "s" and the "." CSS prefix). Same shape as
    // above, from a different page object and a different call site.
    private static final String REAL_VISIBILITY_ASSERTION_NOT_FOUND_MESSAGE_2 =
            "Locator expected to be visible\n"
            + "Call log:\n"
            + "Locator.expect with timeout 5000ms\n"
            + "waiting for locator(\"ummary_subtotal_label\")\n";

    // Captured verbatim (stack frames trimmed) from a real run with CheckoutStepOnePage's
    // lastNameInput field broken to "[data-test'lastName']" (missing "="). type(lastNameInput,
    // ...) throws com.microsoft.playwright.PlaywrightException - the browser's own
    // querySelectorAll rejects the selector as a DOMException, relayed back through Playwright.
    // Note the DOMException's own echoed-back selector text has its quotes swapped
    // ('[data-test"lastName"]', not the original '[data-test\'lastName\']') - the call log's
    // "waiting for locator(...)" line is the reliable source for the real, original text.
    private static final String REAL_MALFORMED_SELECTOR_DOM_EXCEPTION_MESSAGE =
            "Error {\n"
            + "  message='DOMException: Failed to execute 'querySelectorAll' on 'Document': "
            + "'[data-test\"lastName\"]' is not a valid selector.\n"
            + "    at query (<anonymous>:3352:41)\n"
            + "}\n"
            + "Call log:\n"
            + "- waiting for locator(\"[data-test'lastName']\")\n";

    // Captured verbatim (stack frames trimmed) from a real run with CheckoutStepOnePage's
    // continueButton field broken to "[data-test=continue']" (unterminated quote). This time
    // Playwright's own CSS parser rejects the selector before it's ever sent to the browser - a
    // different message wording ("Unexpected token ... while parsing selector ...") and,
    // critically, a call log with no "locator(...)" wrapper at all: just the bare selector text
    // after "waiting for ".
    private static final String REAL_MALFORMED_SELECTOR_PARSE_ERROR_MESSAGE =
            "Error {\n"
            + "  message='Unexpected token \"\" while parsing selector \"[data-test=continue']\"\n"
            + "    at unexpected (.../cssParser.js:68:12)\n"
            + "}\n"
            + "Call log:\n"
            + "- waiting for [data-test=continue']\n";

    @Test
    void realLocatorTimeoutClassifiesAsLocatorFailure() {
        TestFailure failure = failureOf("com.microsoft.playwright.TimeoutError", REAL_LOCATOR_NOT_FOUND_MESSAGE);

        assertEquals(Classification.LOCATOR_FAILURE, FailureClassifier.classify(failure));
    }

    @Test
    void realAssertionFailureClassifiesAsNotFixable() {
        // AssertionFailedError, but the message is a plain value mismatch, not
        // "expected to be visible" - a genuine defect, not a locator problem.
        TestFailure failure = failureOf("org.opentest4j.AssertionFailedError", REAL_ASSERTION_FAILURE_MESSAGE);

        assertEquals(Classification.NOT_FIXABLE, FailureClassifier.classify(failure));
    }

    @Test
    void realVisibilityAssertionTimeoutClassifiesAsLocatorFailure() {
        TestFailure failure = failureOf("org.opentest4j.AssertionFailedError", REAL_VISIBILITY_ASSERTION_NOT_FOUND_MESSAGE);

        assertEquals(Classification.LOCATOR_FAILURE, FailureClassifier.classify(failure));
    }

    @Test
    void secondRealVisibilityAssertionTimeoutClassifiesAsLocatorFailure() {
        TestFailure failure = failureOf("org.opentest4j.AssertionFailedError", REAL_VISIBILITY_ASSERTION_NOT_FOUND_MESSAGE_2);

        assertEquals(Classification.LOCATOR_FAILURE, FailureClassifier.classify(failure));
    }

    @Test
    void visibilityAssertionThatResolvedToAnElementClassifiesAsNotFixable() {
        // Ambiguous case, same reasoning as locatorFoundButNotVisibleClassifiesAsNotFixable below
        // but through the assertion-timeout path: the locator DID resolve to a real element, it
        // just never became visible in time - a real timing/app bug, not a broken locator.
        String message = "Locator expected to be visible\n"
                + "Call log:\n"
                + "Locator.expect with timeout 5000ms\n"
                + "waiting for locator(\"#inventory_container\")\n"
                + "  locator resolved to 1 element\n"
                + "  unexpected value \"hidden\"\n";
        TestFailure failure = failureOf("org.opentest4j.AssertionFailedError", message);

        assertEquals(Classification.NOT_FIXABLE, FailureClassifier.classify(failure));
    }

    @Test
    void explicitResolvedToZeroElementsClassifiesAsLocatorFailure() {
        // A different Playwright action (e.g. an auto-retrying expect()) does report the
        // resolved-element count explicitly, unlike the plain click() timeout captured above.
        String message = "Timeout 5000ms exceeded.\n"
                + "Call log:\n"
                + "  - expect.toBeVisible with timeout 5000ms\n"
                + "  - waiting for locator('#missing-element')\n"
                + "    9 x locator resolved to 0 elements\n";
        TestFailure failure = failureOf("com.microsoft.playwright.TimeoutError", message);

        assertEquals(Classification.LOCATOR_FAILURE, FailureClassifier.classify(failure));
    }

    @Test
    void locatorFoundButNotVisibleClassifiesAsNotFixable() {
        // Ambiguous case: the call log contains "waiting for locator", which alone would look
        // like a locator problem, but "locator resolved to 1 element" proves the selector did
        // match a real element - it just never became visible/enabled/stable in time. That is a
        // timing or application bug (e.g. a slow spinner, a CSS regression hiding a real button),
        // not a broken locator, so a Healer that patched the locator here would be editing a
        // selector that already works while leaving the actual defect in place. Classified
        // NOT_FIXABLE per the "err toward NOT_FIXABLE when ambiguous" rule.
        String message = "Timeout 30000ms exceeded.\n"
                + "Call log:\n"
                + "  - waiting for locator('#login-button')\n"
                + "    - locator resolved to 1 element\n"
                + "    - attempting click action\n"
                + "    2 x waiting for element to be visible, enabled and stable\n"
                + "      - element is not visible\n";
        TestFailure failure = failureOf("com.microsoft.playwright.TimeoutError", message);

        assertEquals(Classification.NOT_FIXABLE, FailureClassifier.classify(failure));
    }

    @Test
    void navigationTimeoutWithNoLocatorWaitClassifiesAsNotFixable() {
        // Ambiguous case: this is a genuine Playwright TimeoutError, but its call log never
        // mentions waiting on a locator at all - it's a page navigation that didn't finish
        // loading in time (slow environment, backend outage, etc). There is no locator here for
        // a Healer to fix, so it must not be swept into LOCATOR_FAILURE just because the
        // exception type matches. Classified NOT_FIXABLE.
        String message = "Timeout 30000ms exceeded.\n"
                + "Call log:\n"
                + "  - navigating to \"https://www.saucedemo.com\", waiting until \"load\"\n";
        TestFailure failure = failureOf("com.microsoft.playwright.TimeoutError", message);

        assertEquals(Classification.NOT_FIXABLE, FailureClassifier.classify(failure));
    }

    @Test
    void realMalformedSelectorDomExceptionClassifiesAsLocatorFailure() {
        TestFailure failure = failureOf("com.microsoft.playwright.PlaywrightException",
                REAL_MALFORMED_SELECTOR_DOM_EXCEPTION_MESSAGE);

        assertEquals(Classification.LOCATOR_FAILURE, FailureClassifier.classify(failure));
    }

    @Test
    void realMalformedSelectorParseErrorClassifiesAsLocatorFailure() {
        // No "waiting for locator(...)" call log at all in this real case - the third pattern
        // must not depend on that gate the way the other two do.
        TestFailure failure = failureOf("com.microsoft.playwright.PlaywrightException",
                REAL_MALFORMED_SELECTOR_PARSE_ERROR_MESSAGE);

        assertEquals(Classification.LOCATOR_FAILURE, FailureClassifier.classify(failure));
    }

    @Test
    void otherPlaywrightExceptionClassifiesAsNotFixable() {
        // A PlaywrightException that isn't about invalid selector syntax - e.g. a browser/network
        // failure - must stay NOT_FIXABLE. Deliberately conservative: matching on the exception
        // type alone would be far too broad.
        String message = "Error {\n"
                + "  message='net::ERR_CONNECTION_REFUSED at https://www.saucedemo.com/\n"
                + "}\n";
        TestFailure failure = failureOf("com.microsoft.playwright.PlaywrightException", message);

        assertEquals(Classification.NOT_FIXABLE, FailureClassifier.classify(failure));
    }

    @Test
    void nullPointerExceptionClassifiesAsNotFixable() {
        TestFailure failure = failureOf("java.lang.NullPointerException", "Cannot invoke \"String.trim()\" because \"x\" is null");

        assertEquals(Classification.NOT_FIXABLE, FailureClassifier.classify(failure));
    }

    private static TestFailure failureOf(String type, String message) {
        TestFailure failure = new TestFailure();
        failure.testName = "irrelevant for classification";
        failure.className = "irrelevant for classification";
        failure.failureType = type;
        failure.failureMessage = message;
        return failure;
    }
}
