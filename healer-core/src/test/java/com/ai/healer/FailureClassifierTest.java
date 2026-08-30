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

    @Test
    void realLocatorTimeoutClassifiesAsLocatorFailure() {
        TestFailure failure = failureOf("com.microsoft.playwright.TimeoutError", REAL_LOCATOR_NOT_FOUND_MESSAGE);

        assertEquals(Classification.LOCATOR_FAILURE, FailureClassifier.classify(failure));
    }

    @Test
    void realAssertionFailureClassifiesAsNotFixable() {
        TestFailure failure = failureOf("org.opentest4j.AssertionFailedError", REAL_ASSERTION_FAILURE_MESSAGE);

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
