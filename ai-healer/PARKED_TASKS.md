## Structural/compound locator building (parked)

---
Today the healer only ever suggests ONE simple selector value (an id,
a data-test attribute, etc.) picked from a flat list of DOM elements
captured at failure time (see FeatureFileResolver/DomElement/LocatorHealer).
This works well when the broken element has a unique attribute, but fails
or guesses wrong when multiple elements share the same text/attributes
with nothing unique to tell them apart (e.g. three identical "Remove"
buttons, or a shared class name with no id/data-test).

Real-world requirement identified: the healer should be able to build a
COMPOUND locator the way a human actually would when there's no unique
attribute available - using parent/child/sibling structure and text
context (e.g. "the button inside the div that contains the text
'Backpack'"), not just pick one flat attribute off a list.

This requires two real changes, not a small tweak:
1. The DOM snapshot capture (Hooks.captureDomSnapshot in playwright-tests)
   would need to capture structural context (parent tag, siblings,
   nesting) for at least the elements near a failure - not just a flat
   list of independent elements as it does today.
2. LocatorHealer's prompt/schema would need to allow Ollama to return a
   compound Playwright selector (parent>child, :has-text() combinations,
   nth-child, etc.), not just a single attribute-based value - and the
   prompt should instruct it to PREFER a simple unique id/data-test when
   one exists, and only build a structural/compound selector as a
   fallback when nothing unique is available (structural selectors are
   more powerful but more brittle/fragile to future layout changes, so
   this order matters).

Open design questions when this is picked up:
- How much structural context is "enough" without re-including too much
  of the DOM (immediate parent+siblings vs. deeper ancestor chain)?
- Should a fallback structural selector be flagged distinctly in the
  heal-confidence output (see recent confidence/ambiguous-match surfacing
  work) since it's inherently less stable than an id-based one?

Status: parked, not started. Discussed and deliberately deferred until
after the initial pipeline-integration/demo work is complete.
---
