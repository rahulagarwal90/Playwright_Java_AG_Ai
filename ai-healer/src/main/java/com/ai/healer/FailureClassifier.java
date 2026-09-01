package com.ai.healer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure deterministic string/pattern matching — no Ollama call, no AI judgment. Misclassifying a
 * real defect as LOCATOR_FAILURE would send the Healer to "fix" a locator that isn't broken and
 * mask a genuine bug, so every check here is conservative: anything that doesn't unambiguously
 * match the locator-not-found pattern falls through to NOT_FIXABLE.
 *
 * Three independent failure shapes count as a locator-not-found (or locator-can't-even-be-built)
 * pattern:
 * <ol>
 *   <li>Playwright's own TimeoutError (a raw page.click(selector)/page.fill(selector) call that
 *   never resolves).</li>
 *   <li>org.opentest4j.AssertionFailedError from a web-first assertion -
 *   assertThat(locator).isVisible() - timing out for the same underlying reason (the locator
 *   never resolves), just thrown through a different API. Confirmed against two real broken
 *   locators in the same session: InventoryPage's "#nventory_container" and
 *   CheckoutStepTwoPage's "ummary_subtotal_label", both asserted via assertThat(...).isVisible()
 *   and both surfacing as AssertionFailedError with message "Locator expected to be visible" and
 *   a call log containing "waiting for locator(...)" - no "resolved to" line at all, same as a
 *   genuinely-not-found TimeoutError.</li>
 *   <li>com.microsoft.playwright.PlaywrightException from a syntactically invalid selector -
 *   Playwright never gets as far as waiting/resolving at all, so this pattern is independent of
 *   the "waiting for locator" / "resolved to" checks the other two share. Confirmed against two
 *   real broken locators with two different real wordings: CheckoutStepOnePage's lastNameInput
 *   ("[data-test'lastName']", missing "=") throws a DOMException relayed from the browser's own
 *   querySelectorAll ("... is not a valid selector", call log still reads the standard
 *   "waiting for locator(\"...\")"), while CheckoutStepOnePage's continueButton
 *   ("[data-test=continue']", unterminated quote) is rejected by Playwright's own CSS parser
 *   before it ever reaches the browser ("Unexpected token ... while parsing selector ...", call
 *   log reads a bare "waiting for &lt;selector&gt;" with no locator(...) wrapper at all).</li>
 * </ol>
 * Not every real broken-looking locator hits one of these three, on purpose - see
 * ARCHITECTURE_EXPLAINED.md for two real ones deliberately left NOT_FIXABLE:
 * CheckoutStepOnePage's zipcodeInput turned out not to be broken at all (an unquoted CSS
 * attribute value is functionally identical to a quoted one), and CheckoutCompletePage's
 * completeHeader is syntactically valid-but-wrong CSS that fails via a *different* web-first
 * assertion wording ("Locator expected to have text", from .hasText(), not .isVisible()) that
 * pattern 2 deliberately doesn't recognize.
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

        if (isTimeoutError || isVisibilityAssertionFailure) {
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

        // Third independent pattern: the broken selector's own text is syntactically invalid CSS,
        // so Playwright never gets as far as waiting/resolving at all - there's no "waiting for
        // locator(...)" call log to gate on here, so this pattern intentionally doesn't go through
        // the checks above. Two real wordings observed, both com.microsoft.playwright
        // .PlaywrightException, depending on WHERE the rejection happens: the browser's own
        // querySelectorAll relaying a DOMException back ("... is not a valid selector", call log
        // still reads "waiting for locator(\"...\")"), or Playwright's own CSS parser rejecting it
        // before ever sending it to the browser ("Unexpected token ... while parsing selector
        // ...", call log reads the bare "waiting for <selector>" - no locator(...) wrapper, since
        // Playwright can't build a locator descriptor for a selector it couldn't parse).
        // Deliberately narrow to these two specific wordings, not PlaywrightException generally -
        // a browser crash, a network error, or a navigation failure is a real defect, not a
        // locator problem, and must stay NOT_FIXABLE.
        boolean isMalformedSelectorSyntax = type.contains("PlaywrightException")
                && (message.contains("is not a valid selector") || message.contains("while parsing selector"));
        if (isMalformedSelectorSyntax) {
            return Classification.LOCATOR_FAILURE;
        }

        return Classification.NOT_FIXABLE;
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
