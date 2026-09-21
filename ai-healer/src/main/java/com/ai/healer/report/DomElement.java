package com.ai.healer.report;

/**
 * Plain data holder for one entry in a Hooks-captured DOM snapshot JSON array — nothing else. No
 * logic, no I/O. Field names mirror the JSON keys Hooks writes (see
 * captureDomSnapshot in playwright-tests' Hooks.java) so Gson can deserialize directly.
 */
public class DomElement {
    public String tag;
    public String id;
    // Split from a single, conflated testId field: dataTest/dataTestId are read independently off
    // an element's real data-test/data-testid attributes (Hooks.captureDomSnapshot no longer
    // merges them with ||), so a caller can tell which real attribute a value actually came from.
    public String dataTest;
    public String dataTestId;
    // The element's raw class attribute value (Hooks.captureDomSnapshot's [class] selector
    // addition, added after a real scan of this codebase's locators found class-based selectors
    // in use). Not yet surfaced by LocatorHealer.formatCandidates() or included in its
    // uniqueness-counting - out of scope for the task that added capture of it.
    public String className;
    public String role;
    public String aria;
    public String text;
}
