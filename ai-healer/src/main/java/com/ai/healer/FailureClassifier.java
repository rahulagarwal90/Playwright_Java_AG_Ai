package com.ai.healer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure deterministic string/pattern matching — no Ollama call, no AI judgment. Misclassifying a
 * real defect as LOCATOR_FAILURE would send the Healer to "fix" a locator that isn't broken and
 * mask a genuine bug, so every check here is conservative: anything that doesn't unambiguously
 * match the locator-not-found pattern falls through to NOT_FIXABLE.
 *
 * Two independent failure shapes both count as a locator-not-found pattern: Playwright's own
 * TimeoutError (a raw page.click(selector)/page.fill(selector) call that never resolves), and
 * org.opentest4j.AssertionFailedError from a web-first assertion - assertThat(locator).isVisible()
 * - timing out for the same underlying reason (the locator never resolves), just thrown through a
 * different API. Confirmed against two real broken locators in the same session:
 * InventoryPage's "#nventory_container" and CheckoutStepTwoPage's "ummary_subtotal_label", both
 * asserted via assertThat(...).isVisible() and both surfacing as AssertionFailedError with message
 * "Locator expected to be visible" and a call log containing "waiting for locator(...)" - no
 * "resolved to" line at all, same as a genuinely-not-found TimeoutError.
 */
public class FailureClassifier {

    public enum Classification {
        LOCATOR_FAILURE,
        NOT_FIXABLE
    }

    // Playwright's call log reports how many elements a locator matched, e.g.
    // "9 x locator resolved to 0 elements" (not found) vs "locator resolved to 1 element" (found,
    // but timed out on something else - visibility/enabled/stable, or a real app bug).
    private static final Pattern RESOLVED_TO_COUNT =
            Pattern.compile("resolved to (\\d+) elements?", Pattern.CASE_INSENSITIVE);

    private FailureClassifier() {
    }

    public static Classification classify(TestFailure failure) {
        String type = failure.failureType == null ? "" : failure.failureType;
        String message = failure.failureMessage == null ? "" : failure.failureMessage;

        boolean isTimeoutError = type.contains("TimeoutError") || message.contains("TimeoutError");
        // Deliberately narrow: only the specific "expected to be visible" web-first assertion
        // wording, not AssertionFailedError generally - a text mismatch, a count assertion, or any
        // other assertion failure is a real defect, not a locator problem, and must stay
        // NOT_FIXABLE. Both phrasings are checked because "Locator expected to be visible" already
        // contains "expected to be visible" as a substring - kept as two explicit checks so the
        // intent (and either real wording Playwright might use) stays obvious to a future reader.
        boolean isVisibilityAssertionFailure = type.contains("AssertionFailedError")
                && (message.contains("Locator expected to be visible") || message.contains("expected to be visible"));

        if (!isTimeoutError && !isVisibilityAssertionFailure) {
            return Classification.NOT_FIXABLE;
        }

        if (!message.contains("waiting for locator")) {
            // A timeout with no locator wait in its call log (navigation, network idle, a
            // custom page.waitForFunction, ...) isn't a locator problem at all.
            return Classification.NOT_FIXABLE;
        }

        if (resolvedToNonZeroElements(message)) {
            // The locator did match something, so it isn't broken - the element exists but
            // timed out on some other condition. Treating this as fixable would paper over
            // a real bug (visibility/timing/app state), so it stays NOT_FIXABLE.
            return Classification.NOT_FIXABLE;
        }

        return Classification.LOCATOR_FAILURE;
    }

    private static boolean resolvedToNonZeroElements(String message) {
        Matcher matcher = RESOLVED_TO_COUNT.matcher(message);
        while (matcher.find()) {
            if (!"0".equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }
}
