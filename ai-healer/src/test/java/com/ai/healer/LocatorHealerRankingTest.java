package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ai.healer.ollama.LocatorHealer;
import com.ai.healer.report.DomElement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises LocatorHealer.rankBySimilarityAndCap() directly - the fix for a real problem a
 * class-heavy DemoQA page surfaced: Hooks.captureDomSnapshot's broadened [id]/[class] capture can
 * produce well over 100 candidates, and the correct element can land anywhere in raw DOM order,
 * including past whatever cap gets applied. Ranking by similarity to the broken locator's own
 * text before capping is what keeps the actually-relevant candidates instead of losing them to
 * DOM-order luck.
 */
public class LocatorHealerRankingTest {

    private static DomElement noiseElement(int index) {
        DomElement element = new DomElement();
        element.tag = "DIV";
        element.id = "nav-link-" + index;
        element.className = "sidebar-item";
        element.text = "Some unrelated menu item " + index;
        return element;
    }

    @Test
    void movesTheCorrectMatchNearTheTopOfALongSyntheticListBeforeCapping() {
        // The exact real shape that surfaced this bug: a class-heavy page with well over 100
        // elements, the target - a plain <p id="name"> with no data-test/data-testid/role, only
        // captured at all because of this branch's [id] broadening - placed deliberately at the
        // very end, past any raw-DOM-order cap.
        List<DomElement> candidates = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            candidates.add(noiseElement(i));
        }
        DomElement correct = new DomElement();
        correct.tag = "P";
        correct.id = "name";
        correct.className = "mb-1";
        correct.text = "Name:John Doe";
        candidates.add(correct); // index 150 - would never survive a raw-order 100-cap

        List<DomElement> ranked = LocatorHealer.rankBySimilarityAndCap("#output #nam", candidates);

        assertEquals(100, ranked.size(), "the cap must still be enforced - just after ranking, not before");
        int position = ranked.indexOf(correct);
        assertTrue(position >= 0, "the correct element must survive the cap, not be silently dropped");
        assertTrue(position < 5,
                "the correct element should rank near the top given its similarity to the broken locator; "
                        + "was at position " + position);
    }

    @Test
    void ranksAnExactSubstringTypoAboveUnrelatedNoise() {
        // CartPage's real historical bug: "[datatest='checkout']" (missing hyphen) against the
        // real data-test="checkout" attribute - a near-miss typo, not an exact match.
        List<DomElement> candidates = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            candidates.add(noiseElement(i));
        }
        DomElement correct = new DomElement();
        correct.tag = "BUTTON";
        correct.dataTest = "checkout";
        correct.text = "Checkout";
        candidates.add(correct);

        List<DomElement> ranked = LocatorHealer.rankBySimilarityAndCap("[datatest='checkout']", candidates);

        assertEquals(100, ranked.size());
        int position = ranked.indexOf(correct);
        assertTrue(position >= 0 && position < 5,
                "expected the near-miss typo match to rank near the top; was at position " + position);
    }

    @Test
    void isANoOpWhenCandidatesAreAlreadyUnderTheCap() {
        List<DomElement> candidates = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candidates.add(noiseElement(i));
        }

        List<DomElement> result = LocatorHealer.rankBySimilarityAndCap("#anything", candidates);

        assertEquals(candidates, result, "under the cap, the list should pass through unranked and unchanged");
    }
}
