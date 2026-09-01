# How ai-healer works, in plain English

This document walks through every class in `ai-healer`, using one real test run as the
running example throughout: the SauceDemo login test, with its `loginButton` locator
temporarily broken from `#login-button` to `#login-button-BROKEN-TEMP` so we could watch
the whole pipeline handle a real failure.

You don't need to be a Java expert to follow this. You just need to follow one webpage's
worth of story: a test clicked a button that wasn't there, and now nine small classes work
together to figure out what the *real* button was called, rewrite the broken line in the
source file, and check the fix actually works before deciding whether to keep it.

## The story in one paragraph

Every time you run the test suite (`mvn -pl playwright-tests test`), Maven's Surefire plugin
writes an XML file recording what passed and what failed. Separately, when a test fails,
`Hooks.java` (in `playwright-tests`, not `ai-healer`) takes a snapshot of every clickable
thing on the page at that exact moment and saves it as JSON. `ai-healer` exists to connect
those two files together: read the failure, decide whether it's the kind of failure a broken
locator would cause, and if so, ask a local AI model to look at the real page snapshot and
suggest what the locator *should* have been — using only elements that were genuinely on the
page, never a guess.

Nine classes divide that job up. `HealOrchestrator` sits on top and actually drives the rest:

```
                         HealOrchestrator (runs everything below, end to end)
                                 │
SurefireReportReader → FailureClassifier → LocatorHealer → HealerOllamaClient
   (reads the XML)     (is this fixable?)  (builds the case) (makes the phone call)
        ↓                                        ↑    ↓
   TestFailure ─────────────────────────────────-┘  HealResult → PageObjectPatcher
  (the case file passed between all three)        (the suggestion)  (edits the file)
        ↑                                                                │
DomElement (one page element,                          HealOrchestrator re-runs just that
many of these live inside the DOM snapshot)            one scenario to verify, then keeps
                                                         or reverts the edit
ScenarioNameSanitizer — a shared rule both Hooks and SurefireReportReader use, so their
                        filenames always agree with each other
```

Below, each class gets three questions answered: what real-world problem it solves, what a
concrete input/output pair looks like using our actual login test, and why it's worth keeping
separate from its neighbors instead of folding it into one big class.

---

## ScenarioNameSanitizer

**The problem it solves:** Cucumber scenario names have spaces and punctuation in them —
"Standard User can login successfully" — but that string needs to become part of a filename
on disk, and filenames don't like spaces or punctuation. Something has to turn scenario names
into safe filenames, consistently, everywhere that needs it.

**In → out, with real values:**
```
in:  "Standard User can login successfully"
out: "Standard_User_can_login_successfully"
```
Every character that isn't a letter, digit, underscore, or hyphen becomes an underscore.

**Why it's its own class:** This exact transformation has to happen in *two different places*
that don't otherwise know about each other: `Hooks.java` in `playwright-tests` uses it to
name the DOM snapshot file it writes (`Standard_User_can_login_successfully-dom.json`), and
`SurefireReportReader` in `ai-healer` uses it to *guess* what that filename must have been,
starting only from the test name recorded in the XML report. If each side had its own copy of
this logic — even a copy that looked identical today — a future edit to one copy and not the
other would silently break the matching between failures and snapshots, and nobody would
notice until a real DOM snapshot mysteriously "wasn't found." Making it one shared method
makes that class of bug impossible instead of just unlikely.

---

## SurefireReportReader

**The problem it solves:** After a test run, the results live in an XML file
(`playwright-tests/target/surefire-reports/TEST-com.framework.runners.TestRunner.xml`) full
of Maven/JUnit bookkeeping — timestamps, system properties, passed tests, skipped tests. Most
of that is noise for our purposes. Something has to go find the *failed* tests specifically,
pull out the handful of facts that matter, and quietly note whether a DOM snapshot exists for
each one — without caring *why* anything failed.

**In → out, with real values:**

The input is the XML report. Buried inside it, our failed test looked like this (real output,
lightly trimmed):
```xml
<testcase name="Standard User can login successfully" classname="User Login Flow" time="32.7">
  <error message="Error {
  message='Timeout 30000ms exceeded.
  name='TimeoutError
  ...
Call log:
- waiting for locator(&quot;#login-button-BROKEN-TEMP&quot;)
" type="com.microsoft.playwright.TimeoutError">
    com.microsoft.playwright.TimeoutError: ...
	at com.framework.pages.BasePage.click(BasePage.java:34)
	at com.framework.pages.saucedemo.LoginPage.loginWithConfigCredentials(LoginPage.java:29)
	...
  </error>
</testcase>
```
The output is one `TestFailure` object with those facts pulled out and the matching DOM
snapshot located:
```
testName        = "Standard User can login successfully"
className       = "User Login Flow"
failureType     = "com.microsoft.playwright.TimeoutError"
failureMessage  = "Error {\n  message='Timeout 30000ms exceeded.\n...Call log:\n- waiting for locator(\"#login-button-BROKEN-TEMP\")\n"
stackTrace      = "com.microsoft.playwright.TimeoutError: ...\n\tat com.framework.pages.BasePage.click(BasePage.java:34)\n\tat com.framework.pages.saucedemo.LoginPage.loginWithConfigCredentials(LoginPage.java:29)\n..."
domSnapshotPath = playwright-tests/target/dom-snapshots/Standard_User_can_login_successfully-dom.json
domSnapshotFound = true
```
Notice: a real Playwright timeout shows up in the XML as an `<error>` element, not a
`<failure>` element — that surprised us the first time we ran this for real, so
`SurefireReportReader` reads both the same way. A test that merely times out on a
non-locator problem, or a completely unrelated test that passed, never becomes a
`TestFailure` at all — only failed testcases make it through.

**Why it's its own class:** This class only knows about XML parsing, file paths, and the
Surefire report format. It has zero opinion about whether a failure is fixable, and zero
knowledge that Ollama exists. That separation means if Maven ever changes its XML report
format, or we swap Surefire for a different test runner's report, only this one class needs
to change — `FailureClassifier` and `LocatorHealer` downstream never notice, because they
only ever see the finished `TestFailure` object, never the XML.

---

## TestFailure

**The problem it solves:** Once `SurefireReportReader` has pulled six or seven separate facts
out of one failed test, something needs to carry all of those facts around together as one
bundle. Without it, every method that needs "the failure" would have to pass around six
separate parameters (name, message, type, stack trace, snapshot path, snapshot-found flag) —
easy to get out of order, easy to forget one.

**In → out, with real values:** `TestFailure` doesn't *do* anything — it's the form, not the
clerk. For our login test, one filled-out `TestFailure` looks exactly like the block shown
above under `SurefireReportReader`'s output. `FailureClassifier` reads two of its fields
(`failureType`, `failureMessage`); `LocatorHealer` reads four (`failureMessage`, `stackTrace`,
`domSnapshotPath`, `domSnapshotFound`). Both are reading from the *same* object — nobody
re-derives or re-fetches anything.

**Why it's its own class:** It's what programmers call a "plain data holder" — on purpose, it
contains no logic at all. Keeping it logic-free is exactly why it's useful: it's the one
shape of object that `SurefireReportReader` (which produces it), `FailureClassifier` (which
reads part of it), and `LocatorHealer` (which reads a different part of it) can all agree on
without any of those three classes needing to know how the other two work internally.

---

## FailureClassifier

**The problem it solves:** Not every test failure is a broken locator. A test can fail
because of a real product bug, a flaky network, a bad assertion, a null pointer in test code
— dozens of reasons. Before spending an AI call trying to "fix" a locator, something has to
make the call: does this failure even look like a broken locator, using only fast, free,
deterministic checks? Getting this wrong in the "too eager" direction is the dangerous
failure mode — if a real product bug gets waved through as "just a broken locator," the
Healer would end up patching a test to hide an actual defect.

**In → out, with real values:**

Our login failure's `TestFailure` goes in. Its `failureType` is
`"com.microsoft.playwright.TimeoutError"` and its `failureMessage` contains
`"- waiting for locator(\"#login-button-BROKEN-TEMP\")"` with no "resolved to N elements"
text anywhere in it. `FailureClassifier.classify(...)` returns `LOCATOR_FAILURE`.

Contrast that with a second real case we ran deliberately: the same test, locator untouched,
but with a fake failed assertion added to the last step. That `TestFailure` had
`failureType = "org.opentest4j.AssertionFailedError"` and a message like
`"expected: <wrong-expected-value> but was: <actual-value>"` — no mention of `TimeoutError`
anywhere. `classify(...)` returns `NOT_FIXABLE`.

There's a third, subtler case worth knowing about: a locator that *did* find an element, but
the element never became clickable in time (still loading, hidden behind a spinner, etc). Its
message would contain both "waiting for locator" *and* something like "resolved to 1
element." `FailureClassifier` treats that as `NOT_FIXABLE` too — the locator worked, so
patching it wouldn't fix anything; the timeout is telling you something else is actually
wrong.

A fourth case, found for real rather than invented: `TimeoutError` isn't the only exception
shape a broken locator produces. `InventoryPage.verifySuccessfulLogin()` doesn't call
`click()`/`type()` at all - it calls Playwright's web-first assertion,
`assertThat(page.locator(inventoryContainer).first()).isVisible()`. When
`inventoryContainer` was a real, pre-existing typo (`"#nventory_container"`, missing the `i`),
that assertion timed out and threw `org.opentest4j.AssertionFailedError` - a completely
different exception type - with message `"Locator expected to be visible"` and a call log
still containing `"waiting for locator(\"#nventory_container\").first()"`. `FailureClassifier`
originally only recognized `TimeoutError`, so this classified `NOT_FIXABLE` - a real
misclassification, not a hypothetical one. Fixed by recognizing this as a second, independent
pattern: `AssertionFailedError` whose message contains `"expected to be visible"` gets the same
treatment as `TimeoutError` from there on (same "waiting for locator" requirement, same
"resolved to N > 0" exclusion) - deliberately narrow, so a real value-mismatch assertion
(`REAL_ASSERTION_FAILURE_MESSAGE` above) still isn't swept in just because its type also
happens to be `AssertionFailedError`. A second real locator, `CheckoutStepTwoPage`'s
`itemTotalLabel` (typo'd to `"ummary_subtotal_label"`), confirmed the same pattern from a
different page object. Both now classify `LOCATOR_FAILURE` and heal correctly - see
`LocatorHealer` below for what that healing actually produced.

A fifth case, also found chasing a real chain of pre-existing typos rather than invented:
`CheckoutStepOnePage.lastNameInput`, typo'd to `"[data-test'lastName']"` (missing `=`), doesn't
time out at all - `type(lastNameInput, ...)` throws `com.microsoft.playwright.PlaywrightException`
*synchronously*, because the selector string itself can't be resolved to real elements: the
browser's own `querySelectorAll` rejects it as a `DOMException`
(`"...'[data-test\"lastName\"]' is not a valid selector."` - note the DOMException's own
echoed-back text has its quotes swapped from the original; the call log's
`waiting for locator("[data-test'lastName']")` line is the reliable source for the real,
original text), relayed back through Playwright. `FailureClassifier` originally had no pattern
for this at all, so it classified `NOT_FIXABLE`. A second real locator down the same chain,
`CheckoutStepOnePage.continueButton` (typo'd to `"[data-test=continue']"`, an unterminated
quote), turned out to fail a *different* way: Playwright's own CSS parser rejects it before the
selector is ever sent to the browser (`"Unexpected token \"\" while parsing selector
\"[data-test=continue']\""`), and critically, its call log has **no `locator(...)` wrapper at
all** - just the bare `"- waiting for [data-test=continue']"`, since Playwright never got far
enough to build a proper locator descriptor for a selector it couldn't parse. Fixed by adding a
third, independent pattern: `PlaywrightException` whose message contains either
`"is not a valid selector"` or `"while parsing selector"` classifies `LOCATOR_FAILURE`
unconditionally - this pattern deliberately skips the "waiting for locator" / "resolved to"
checks the other two patterns share, since a parse-time failure never produces either. Both real
sub-shapes now classify `LOCATOR_FAILURE` and heal correctly - see `LocatorHealer` below for the
matching extraction-regex fallback the second sub-shape needed. One more real locator from the
same chain deliberately does **not** match this or any pattern, on purpose: `zipcodeInput`
(`"[data-test=postalCode]"`, unquoted) turned out not to be broken at all - an unquoted CSS
attribute value is functionally identical to a quoted one, confirmed by a clean, all-passing run
with it in that state.

A sixth case is the one that most needed careful verification before writing any code, because
guessing wrong here would have meant auto-"fixing" a locator that was never broken.
`CheckoutCompletePage.completeHeader`'s real failure - `AssertionFailedError`, `"Locator
expected to have text: Thank you for your order!\nReceived: null"` - looks at first glance like
it could be a genuine text-content mismatch: a wrong expected value, or the app's real copy
differing from what the test expects. If that were true, the *locator* wouldn't be the problem
at all, and adding a pattern that auto-healed it would be actively wrong - editing a selector
that already worked while masking a real defect. So before writing a fourth pattern, the real
question got answered for real: with `completeHeader` broken to `.completeheader` (missing the
hyphen), is the element genuinely not found, or found-but-wrong-text? The message's `"Received:
null"` and the complete absence of any `"locator resolved to <...>"` line in the call log both
point to zero elements matched. Restoring the correct `.complete-header` confirmed it directly -
5/5 tests pass, and the real element is `<h2 class="complete-header"
data-test="complete-header">Thank you for your order!</h2>`, text matching exactly. For a clean
side-by-side, the same correct locator was paired with a deliberately wrong expected string:
```
Locator expected to have text: TEMP: deliberately wrong expected text for classifier testing
Received: Thank you for your order!
Call log:
waiting for locator(".complete-header")
  locator resolved to <h2 class="complete-header" data-test="complete-head…>Thank you for your order!</h2>
  unexpected value "Thank you for your order!"
```
A real `Received:` value, and a call log line that echoes back the actual matched element -
unmistakably different from the broken-locator case. `completeHeader`'s real failure was
genuinely a broken locator (same underlying "never resolves" cause as pattern 2, just via
`.hasText()` instead of `.isVisible()`), not a mismatch - so the fourth pattern requires **both**
`"Locator expected to have text"` *and* `"Received: null"`, tested against both real messages so
a genuine mismatch (the wrong-expected-string case above) stays `NOT_FIXABLE` on purpose. Both
now classify correctly, and `completeHeader` heals for real - see `HealOrchestrator` below for
what that healing actually produced.

**Why it's its own class:** This logic needs to stay boring and predictable on purpose — it's
plain string matching with no AI involved, so the same failure always gets the same verdict,
and a human can read the `if` checks and know exactly why something was or wasn't classified as
fixable. Merging this into `LocatorHealer` would mean every future change to "how do we build
the AI prompt" risks also silently changing "what counts as fixable" — two very different kinds
of edits that should never accidentally affect each other.

---

## DomElement

**The problem it solves:** The DOM snapshot `Hooks.java` writes to disk is a JSON array —
each entry describing one clickable thing that existed on the page at the moment of failure.
Something needs to represent one of those entries as a Java object so the rest of the code
can work with a list of "real page elements" instead of raw JSON text.

**In → out, with real values:** One line of the real snapshot file
(`Standard_User_can_login_successfully-dom.json`) reads:
```json
{"tag":"INPUT","id":"login-button","testId":"login-button","role":null,"aria":null,"text":""}
```
That becomes one `DomElement` object:
```
tag = "INPUT"
id = "login-button"
testId = "login-button"
role = null
aria = null
text = ""
```
The whole snapshot file — seven elements for our login page — becomes a list of seven of
these. One thing worth flagging here so it doesn't cause confusion later: `testId` is this
field's *Java name*, chosen to match the JSON key Hooks writes - it is not the name of a real
HTML attribute. The actual attribute Hooks read the value from (`data-test`, falling back to
`data-testid` - see `captureDomSnapshot` below) only shows up again when `LocatorHealer` builds
its prompt, and getting that translation right turned out to matter a lot (see `LocatorHealer`'s
Step 4 below).

**Why it's its own class:** Its fields are named to match the JSON keys exactly
(`tag`/`id`/`testId`/`role`/`aria`/`text`) *on purpose*, so the JSON-to-Java conversion is
automatic and needs no custom code — but that also means `DomElement` is tightly coupled to
what `Hooks.java` writes, in a different module entirely. Keeping it as its own tiny class
means that coupling is contained in one obvious place. If `Hooks.java` ever adds a new field
to the snapshot (say, a CSS class name), one class changes, not scattered code throughout
`LocatorHealer`.

---

## HealerOllamaClient

**The problem it solves:** Talking to a local Ollama server means building an HTTP request
with a specific JSON shape, POSTing it to the right URL, checking the response succeeded, and
pulling the answer text back out — all mechanical, and all completely uninteresting to
whatever is *asking* the question. Something needs to own "how do we talk to Ollama" as a
self-contained skill, separate from "what do we ask it."

**In → out, with real values:** Given a system prompt and a user prompt (both shown in full
under `LocatorHealer` below), `suggestLocator(...)` sends them to
`http://localhost:11434/api/chat` using the model named by the `OLLAMA_MODEL` environment
variable (or `qwen2.5-coder:14b` if that variable isn't set), and hands back the raw text
Ollama replied with:
```
{
  "newSelector": "#login-button",
  "matchedElement": "tag=INPUT id=login-button data-test/data-testid=login-button",
  "confidence": "high"
}
```
It doesn't parse that JSON into anything friendlier, and it doesn't know what a "locator" or
a "DOM snapshot" even is — it just sends text and returns text.

**Why it's its own class:** `ai-healer` deliberately does *not* reuse `ai-reviewer`'s existing
Ollama-calling code (`OllamaConfig`/`OllamaReviewClient`), even though the two are almost
identical in spirit — `ai-healer` has no dependency on the `ai-reviewer` module, and whether
to eventually share that plumbing is a decision parked for later rather than made by
accident here. Keeping the HTTP mechanics in their own class also means that if we ever want
to point the Healer at a different AI provider, or add retry logic, or change how the model
name is resolved, exactly one class needs surgery — and it's a class with no idea what a
"broken locator" is, so there's no risk of that change accidentally altering what gets asked.

---

## LocatorHealer

**The problem it solves:** Everything upstream has produced raw ingredients — a `TestFailure`
with a broken locator buried in a paragraph of exception text, and a DOM snapshot file
sitting on disk. Something has to actually turn those ingredients into a good question for
the AI, ask it, and turn the answer back into something usable. This is the class where the
actual "healing" idea lives.

**In → out, with real values:** This is worth walking through in full, because it's the heart
of the module and it's exactly what we ran for real.

*Step 1 — pull the broken locator out of the failure message.* From
`"- waiting for locator(\"#login-button-BROKEN-TEMP\")"`, it extracts just
`#login-button-BROKEN-TEMP`.

*Step 2 — load the DOM snapshot.* It reads
`Standard_User_can_login_successfully-dom.json` and turns it into a list of seven
`DomElement` objects (see above).

*Step 3 — find useful file/line context.* It scans `stackTrace` for the first line that
belongs to a page object — not `BasePage.java:34` (every page object's `click()` and `type()`
calls funnel through `BasePage`, so that line is the same for almost every locator failure in
the whole suite and tells you nothing) but the concrete page object one frame after it:
`LoginPage.java:29`, the actual line where `click(loginButton)` was called. That's useful
context for the prompt, but it's *not* where the fix needs to go — line 29 is where the
locator is *used*, not where it's *declared*. So `LocatorHealer` takes it one step further:
it opens `LoginPage.java` itself and searches for the one field line whose quoted value is
exactly `#login-button-BROKEN-TEMP` — which turns out to be line 13, three lines above the
constructor. That's the line `PageObjectPatcher` will actually be told to edit.

*Step 4 — build the prompt.* The real user-facing prompt sent to Ollama was:
```
BROKEN LOCATOR: #login-button-BROKEN-TEMP
USED AT: LoginPage.java:29

NOTE: Some candidate elements below share the same text, role, or aria value - marked
[NOT UNIQUE]. A locator built from a [NOT UNIQUE] value could match more than one element
on the real page. Prefer a candidate's id or data-test/data-testid value in that case; if
only [NOT UNIQUE] properties match and no candidate has a unique id/data-test/data-testid,
set confidence to "low" and say so in matchedElement.

CANDIDATE ELEMENTS:
1. tag=DIV data-test/data-testid=login-container text="Accepted usernames are:
standard_user
lo" [NOT UNIQUE]
2. tag=INPUT id=user-name data-test/data-testid=username
3. tag=INPUT id=password data-test/data-testid=password
4. tag=INPUT id=login-button data-test/data-testid=login-button
5. tag=DIV data-test/data-testid=login-credentials-container text="Accepted usernames are:
standard_user
lo" [NOT UNIQUE]
6. tag=DIV id=login_credentials data-test/data-testid=login-credentials text="Accepted usernames are:
standard_user
lo" [NOT UNIQUE]
7. tag=DIV data-test/data-testid=login-password text="Password for all users:
secret_sauce"
```
alongside a fixed system prompt instructing the model to answer with a *bare selector value* -
not a Java statement like `page.locator("#id")`, just the raw text a page object field would
hold, e.g. `#id` or `[data-test='x']` - built *only* from values that appear in that candidate
list, never an invented id or property. That instruction matters: this codebase's page object
fields hold exactly that kind of bare string (`BasePage.click(selector)` passes it straight to
`page.click(selector)`), so asking for anything else would produce a suggestion `PageObjectPatcher`
could write into the file but that Playwright could never actually use - see the closing section
below for what that looked like in practice before this was fixed. Notice the label itself:
`DomElement`'s Java field is called `testId`, but the prompt never says "testId" - it says
`data-test/data-testid`, the actual HTML attribute Hooks read the value from (see
`captureDomSnapshot`: `e.dataset.test||e.dataset.testid`). A model told "testId=X" has no way to
know that's not a real attribute name; a model told "data-test/data-testid=X" can build a
selector that will actually match something on the real page. Three of these seven candidates
(1, 5, and 6) happen to share the exact same visible text - "Accepted usernames are:
standard_user..." - so all three are correctly marked `[NOT UNIQUE]`, and the NOTE above appears.

*Step 4½ — spot ambiguous candidates before they cause a bad pick.* Before building the prompt,
`LocatorHealer` checks whether any candidate's `text`, `role`, or `aria` value is shared by more
than one candidate (id/data-test/data-testid aren't checked — they're treated as reliably unique
per-element identifiers). SauceDemo's real inventory page is an even sharper example: every "Add
to cart" button has the *same visible text* but a *different* `data-test` value per product. Two
real Ollama calls with that exact shape, run for real to confirm this actually works and not
just in theory:
```
CANDIDATE ELEMENTS (three "Add to cart" buttons, each with its own data-test value):
1. tag=BUTTON data-test/data-testid=add-to-cart-sauce-labs-backpack text="Add to cart" [NOT UNIQUE]
2. tag=BUTTON data-test/data-testid=add-to-cart-sauce-labs-bike-light text="Add to cart" [NOT UNIQUE]
3. tag=BUTTON data-test/data-testid=add-to-cart-sauce-labs-bolt-t-shirt text="Add to cart" [NOT UNIQUE]

→ real response: newSelector = "[data-test='add-to-cart-sauce-labs-backpack']", confidence = "high"
```
The model correctly reached for the unique `data-test` value instead of the shared "Add to cart"
text — a text-based locator here would have matched all three buttons, not just the one it
meant — and, just as important, it wrote a *real* attribute selector this time. Earlier runs of
this exact scenario (before the label was fixed) produced `[testId='add-to-cart-sauce-labs-backpack']`
instead: it looked plausible in the transcript, but `testId` isn't an HTML attribute that exists
on any real page, so that selector would have matched nothing. `[data-test='...']` is the actual
convention this app's real page objects use (see `InventoryPage.addProductToCart`, which builds
the identical `[data-test='add-to-cart-...']` shape by hand) - this selector would genuinely work.
Take the data-test values away entirely, leaving only three identical text-only candidates with
no id or data-test/data-testid anywhere:
```
CANDIDATE ELEMENTS (three plain divs, nothing but shared text):
1. tag=DIV text="Add to cart" [NOT UNIQUE]
2. tag=DIV text="Add to cart" [NOT UNIQUE]
3. tag=DIV text="Add to cart" [NOT UNIQUE]

→ real response: newSelector = "text='Add to cart'", confidence = "low"
```
With no unique property available at all, the model still answered (rather than refusing
outright), but honestly flagged low confidence instead of pretending it knew which of the three
identical elements was the right one.

*Step 5 — hand that off to `HealerOllamaClient` and parse what comes back.* The real answer,
shown under `HealerOllamaClient` above, becomes a `HealResult`:
```
newSelector    = "#login-button"
matchedElement = "tag=INPUT id=login-button data-test/data-testid=login-button"
confidence     = "high"
filePath       = ".../saucedemo/LoginPage.java"
lineNumber     = 13
```
Candidate #4 is genuinely the right element — it's what the locator pointed to before we
broke it on purpose for this test — and the model picked it correctly, from real page data,
inventing nothing. `filePath`/`lineNumber` are the declaration line from Step 3, carried along
so `PageObjectPatcher` has an actual place to make the edit — not just a suggestion in the abstract.

*A note on that `#login-button` answer above*: it predates a later fix and would likely come out
differently today. Candidate #4 has **both** `id=login-button` and `data-test/data-testid=login-button`
— and the prompt used to leave the choice between them entirely up to the model. A real later run
with a different broken locator (`CartPage.checkoutButton`) surfaced the problem this caused: given
a candidate with both attributes, the model answered `#checkout`, not this codebase's actual
convention, `[data-test='checkout']` (see `InventoryPage.addProductToCart()`, which builds that
exact shape by hand). Fixed with one more rule added to the prompt: prefer `data-test`/`data-testid`
over `id` whenever a candidate has both, falling back to `id` only when a candidate has no
`data-test`/`data-testid` value at all. **Confirmed for real across three separate Ollama calls**
in one later chain-healing run, each with a candidate carrying both attributes:
```
lastNameInput's candidate:  id=last-name  data-test/data-testid=lastName
  → newSelector = "[data-test='lastName']"   (not "#last-name")
continueButton's candidate: id=continue    data-test/data-testid=continue
  → newSelector = "[data-test='continue']"   (not "#continue")
finishButton's candidate:   id=finish      data-test/data-testid=finish
  → newSelector = "[data-test='finish']"     (not "#finish")
```
All three chose the `data-test`-based selector, never the `id`-based one — the fix holds.

**Why it's its own class:** This is the "case manager" — it's the only class that understands
the *shape* of the whole problem (a broken locator, plus context, plus real candidates,
equals a good question), but it deliberately doesn't know how to physically talk to Ollama
(that's `HealerOllamaClient`'s job), doesn't know how to read Surefire XML
(`SurefireReportReader`'s job), and doesn't decide whether a failure was worth investigating
in the first place (`FailureClassifier`'s job). This mirrors how `ai-reviewer` splits
`OllamaReviewClient` (the HTTP call) from `LocalCodeReviewer` (the orchestration) — the same
shape of problem gets the same shape of solution, so a developer who already understands one
module recognizes the pattern in the other.

---

## PageObjectPatcher

**The problem it solves:** `LocatorHealer` has produced a suggestion and a location, but
nothing has touched the file yet. Something has to actually open `LoginPage.java`, change
*exactly* the broken line, and leave every other byte in the file untouched — and, just as
important, refuse to touch anything if the location it's been given doesn't look like a
locator after all. This class is deliberately not smart: it doesn't decide whether the new
locator is a *good* one, it just performs the edit, carefully.

**In → out, with real values:** Given `filePath = LoginPage.java`, `lineNumber = 13`, and
`newSelector = "#login-button"` from the real `HealResult` above, it first reads line 13 and
checks it against the shape `private final String <name> = "...";` — it matches, so the edit
goes ahead:
```diff
     private final String usernameInput = "#user-name";
     private final String passwordInput = "#password";
-    private final String loginButton = "#login-button-BROKEN-TEMP";
+    private final String loginButton = "#login-button";
```
Comparing the file byte-for-byte before and after confirms that line is the *only* thing that
changed — same imports, same blank lines, same everything else. The result it hands back is
`applied = true`, with a plain-English reason describing exactly what it replaced. In this real
run, the patched line is byte-for-byte identical to what the field held *before* it was ever
broken for this test - and re-running the actual test suite against the patched file confirms
it: the login test passes again, for real, not just textually.

Point it at a line that isn't a locator field — say, `public LoginPage(Page page) {` — and it
refuses instead of guessing:
```
applied: false
reason: Line 15 of LoginPage.java doesn't look like a locator field declaration
        (expected 'private final String <name> = "...";'), found:
        public LoginPage(Page page) {
```
The file is left completely untouched when that happens.

**Why it's its own class:** Editing a source file is a fundamentally different *kind* of
responsibility than deciding what to write into it — one is "does this text change preserve
everything else," the other is "is this the right fix." Keeping them apart means
`PageObjectPatcher` can be trusted never to corrupt a file (it has exactly one job, and a
test-covered refusal path for every way its input could be wrong), while all of the judgment
about *what* to suggest stays entirely in `LocatorHealer`, and whether a fix is even worth
keeping stays entirely in `HealOrchestrator` below.

---

## HealOrchestrator

**The problem it solves:** Every class above does one job well, but nobody actually runs them
in order, decides what a "successful" fix means, or takes responsibility for cleaning up after
a fix that didn't work. Something has to be the one piece that says: read the failures, skip
the ones a human needs to look at, ask for a fix, apply it, *check that it actually worked*,
and only keep it if it did.

**In → out, with real values:** This is the class that runs the entire pipeline for real, so
walking through what it actually did is more useful than a single input/output pair.

`HealOrchestrator.run()` reads every failure currently in the Surefire report (not just the
first one) and handles each independently:

- **`NOT_FIXABLE` failures** just get logged - `[NOT_FIXABLE] "Some assertion failure" (org.opentest4j.AssertionFailedError) - needs human attention`
  - and nothing else happens. No Ollama call, no file touched.
- **`LOCATOR_FAILURE`s** always get a suggestion from `LocatorHealer` - that much happens
  everywhere, including in a GitHub PR pipeline (detected the same way `ai-reviewer`'s
  `GitHubContext.isPresent()` does, duplicated rather than depended on - see below). In a
  pipeline, that's *all* that happens: the suggestion gets logged and nothing is written or
  re-run, matching the "suggestion only" design a CI gate needs.
- **In a local checkout**, it goes further: `PageObjectPatcher` applies the fix, then
  `HealOrchestrator` re-runs *just that one scenario* - `mvn -pl playwright-tests test
  -Dcucumber.filter.name="^\QStandard User can login successfully\E$"
  -Dhealer.skipArtifactCleanup=true` - confirmed for real that the name filter narrows this down
  to exactly one of five scenarios, not the whole suite. If that re-run passes, the patched file
  is left exactly as it is, uncommitted, for a human to review. If it still fails, whether the
  file gets reverted depends on *where* it's still failing - see below.

**A scenario can have more than one broken locator, and the first version of this reverted a
correct fix whenever the re-run still failed for any reason at all** - including the exact case
where fixing locator #1 unmasks a previously-hidden locator #2. That meant a correct fix got
thrown away every time, and a second invocation could never make progress: the file went back to
its original broken state, so the next full-suite run reported the exact same original failure,
`HealOrchestrator` "fixed" it again, hit the same masked failure again, reverted again - forever.
Confirmed for real, twice in a row, against a genuine `passwordInput`/`loginButton`
double-break fixture.

**The fix**: compare the fresh re-run failure's locator against the one just patched.
- **Same locator** → the suggested fix didn't work → revert, exactly as before.
- **A different locator** (or a fresh failure that isn't locator-shaped at all) → the fix was
  correct, the scenario just has another problem → **keep it**, and immediately chase the
  newly-unmasked failure the same way, up to `ai.healer.maxRetries` heal attempts total (a
  global budget shared across every failure `run()` processes in one invocation, not a
  per-failure allowance - `config.properties`, default `2`).

Re-running the exact same double-break fixture with this fix, `maxRetries=2` (the default):
```
[PROGRESS] "Standard User can login successfully" - LoginPage.java:12 "password" -> "#password"
kept; the scenario's failure has changed to a different locator (#login-button-BROKEN-TEMP) -
continuing.
[HEALED] "Standard User can login successfully" - LoginPage.java:13 "#login-button-BROKEN-TEMP"
-> "#login-button"; re-run passed. Left as an uncommitted change for review.
```
Both locators healed and kept in a single invocation - the file ends up byte-for-byte identical
to its known-correct state, confirmed with `diff`, and a full-suite re-run passes 5/5 for real.
If `maxRetries` runs out before full resolution, `run()` stops there and the result records which
locators were healed and kept plus a plain-English note on what's left, e.g. *"test still fails
after healing LoginPage.java:12 \"password\" -> \"#password\" - the failure has changed to a
different locator (#login-button-BROKEN-TEMP), which may indicate the original fix was correct
but the test has more than one problem. Ran out of retries (max 1) before resolving it."* -
confirmed with `maxRetries=1` forced, which stops after exactly the first fix and correctly
reports it as kept, not reverted.

**One thing worth knowing about this design**: the budget is *global*, not per-failure. In the
real run above, the `Login` scenario's chain used both available attempts, so when `run()` moved
on to the second Surefire failure (`Purchase Flow`, caused by the same underlying break) the
budget was already spent - it got reported as `MAX_RETRIES_EXCEEDED`, "not attempted," without
ever being re-checked. A follow-up full-suite run showed `Purchase Flow` was actually already
passing too (both locators live in the same page object), so that particular report was overly
pessimistic - `HealOrchestrator` doesn't spend a free re-run confirming an untouched failure
might already be fixed as a side effect. A reasonable enough trade-off (verifying costs a real
browser run), but worth knowing if a report says "not attempted" - it might already be fine.

**A second real bug found and fixed in the same pass, unrelated to the revert logic**: each
re-run spawns a fresh `mvn test` subprocess, and that subprocess's own `Hooks` used to clear
`target/dom-snapshots/` on startup (its "once per JVM" artifact-clearing logic has no way to know
a previous JVM already ran once this session) - wiping out *other* not-yet-processed failures'
DOM snapshots before their turn came, surfacing as `HEAL_ERROR` for reasons that had nothing to
do with whether they were actually healable. Fixed by having `HealOrchestrator` pass
`-Dhealer.skipArtifactCleanup=true` on every re-run subprocess it spawns, and `Hooks.setup()`
checking that system property before clearing anything. Confirmed for real: the MD5 hash of an
untouched failure's DOM snapshot file was identical before and after two separate re-run
subprocesses ran.

**Why it's its own class:** It's the only class allowed to make the two decisions everything
below it deliberately avoids: "is this fix good enough to keep" and "are we in a context where
we're even allowed to change files." Every other class in this document either reads something,
asks something, or edits something, but none of them decide whether the *result* was actually
good - that judgment call needed one place to live, and it needed to be separate from
`PageObjectPatcher` (which must never judge, only edit) for exactly the same reason `FailureClassifier`
stays separate from `LocatorHealer`: so a future change to "how do we verify a fix" can't
accidentally change "how do we edit a file" or vice versa.

**One more thing this class had to get right: not running at all on the wrong data.** Nothing
used to stop `HealOrchestrator` from being pointed at an *old* report - run the tests, walk away,
come back the next day, run `HealOrchestrator` again without re-running the tests, and it would
have happily "healed" yesterday's failures using today's (possibly already-different) source
files. Tested for real, before any fix: an all-passing test run followed by `HealOrchestrator`
logged `"HealOrchestrator finished: 0 failure(s) processed."` - and a *missing* `surefire-reports`
directory entirely logged the exact same line. No crash either way, but no way to tell "you're
all done" apart from "you never ran the tests" from the output alone.

The fix lives in `SurefireReportReader` (which already owns the directory path, so it's the
natural place - keeping this out of `HealOrchestrator` also means the check is reachable without
mocking the filesystem out from under a real `RepoRoot.resolve()` call, which was the first,
wrong version of this fix: it read the *real* `playwright-tests/target/surefire-reports` directly
inside `HealOrchestrator.run()`, bypassing whatever `SurefireReportReader` a test had injected -
every existing test still passed, but only because this repo's real directory happened to exist
at the time; a fresh checkout would have broken every one of them silently). `readFailures()` now
checks, in order: does the directory exist; does it contain any `.xml` files; and - only if a
caller set the `healer.minReportTimestamp` system property - is the newest report file at least
that recent. Each of the first three produces exactly one clear log line and an empty list, and a
fresh, non-empty, on-time report that simply contains zero failures gets its own line too:
`"Test suite passed - nothing to heal."` Confirmed for real, all four cases: renaming the
directory away, creating it empty, setting `healer.minReportTimestamp` to an hour in the future
against a real report, and a genuine all-passing run each produced their own distinct message -
only a fresh, non-empty, on-time, actually-failing report gets processed.

That timestamp is what closes the loop with `run-tests-and-heal.sh` (repo root, the only shell
script in this project): it captures the time in whole seconds *before* running
`mvn -pl playwright-tests test`, and if that fails, passes it straight through as
`-Dhealer.minReportTimestamp` on the `HealOrchestrator` invocation that follows - automatically,
in the same command, no separate manual step. Run for real against the same double-broken
`passwordInput`/`loginButton` fixture from above: the script ran the tests, saw them fail, and
invoked `HealOrchestrator` itself, which healed and kept both locators exactly as the standalone
run did earlier, all from one command. Run again immediately afterward (everything now fixed):
```
=== Running playwright-tests ===
...
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
...
=== All tests passed - nothing to heal ===
```
`HealOrchestrator` is never even invoked on that second run - the script's own `if mvn ...; then`
branch short-circuits before it gets the chance, which is the cleanest possible "nothing to heal."

**`TestRunAndHeal` does the same job as one `mvn` command instead of two.** The shell script
above chains two separate Maven invocations - one process runs the tests, exits, and a second
`mvn -pl ai-healer exec:java` process reads whatever that left behind. `TestRunAndHeal`
(`com.ai.healer.TestRunAndHeal`, runnable via
`mvn -pl ai-healer exec:java -Dexec.mainClass=com.ai.healer.TestRunAndHeal`) does the same thing
in one: it captures the timestamp, runs `mvn -pl playwright-tests test` as a subprocess via a
small extracted helper (`MavenRunner` - the same `ProcessBuilder`/`inheritIO()` plumbing
`HealOrchestrator`'s own scenario re-run already used, pulled out so this doesn't duplicate it),
and if that subprocess exits non-zero, calls `HealOrchestrator.main()` directly as a Java method
in the *same* JVM - both classes live in `ai-healer`, so there's no reason to pay for a second
`mvn` startup just to get there. The timestamp still closes the staleness-guard loop exactly as
before, just via `System.setProperty(...)` instead of a subprocess `-D` flag.

Confirmed for real, via the single `mvn` command above and nothing else - no shell script, no
manual `HealOrchestrator` invocation: with `InventoryPage`'s `inventoryContainer` temporarily
broken back to `"#nventory_container"` (the exact typo from the `FailureClassifier` case study
earlier in this document), `TestRunAndHeal` ran the suite, saw it fail, and healed
`#inventory_container` back automatically - `[HEALED] "Standard User can login successfully" -
InventoryPage.java:11 "#nventory_container" -> "#inventory_container"; re-run passed.` The log
output makes the "one JVM, not two `mvn` processes" claim checkable, too: exactly one
`Building ai-healer` block appears (the outer command), against two `Building playwright-tests`
blocks (`TestRunAndHeal`'s own full-suite run, then `HealOrchestrator`'s inner one-scenario
re-run/verify) - if `HealOrchestrator` had been invoked as a second subprocess, there would be a
second `Building ai-healer` block, and there isn't one.

That same real run surfaced a genuine bug along the way, since fixed in a follow-up task:
`CartPage`'s `checkoutButton` field held `"[datatest='checkout']"` (missing the hyphen in
`data-test`), which matched nothing on the real page. `FailureClassifier` correctly classified
the resulting `TimeoutError` as `LOCATOR_FAILURE` - its check is a plain substring test,
`message.contains("waiting for locator")` - but `LocatorHealer`'s own, *stricter* extraction
regex (`waiting for locator\(["']([^"']+)["']\)`, which assumed the locator text inside contained
neither quote character) came up empty against `waiting for locator("[datatest='checkout']")`:
the broken selector's own single-quoted attribute value sits inside the call log's double-quoted
wrapper, so the regex's `[^"']+` capture stopped at that embedded `'` before it ever reached a
closing quote, and the whole pattern failed to match. `HealOrchestrator` caught the resulting
`IllegalStateException` and reported `HEAL_ERROR` rather than crashing - the right behavior for
something it can't fix, but not the goal.

**The fix**: `LOCATOR_IN_CALL_LOG` now captures the opening quote character itself
(`waiting for locator\((["'])(.+)\1\)`) and matches the closing delimiter with a *backreference*
to that same character, rather than a character class that excludes both quote types. Greedy
backtracking naturally resolves this to the *rightmost* occurrence of the wrapping quote
immediately before `)` - the true outer boundary - not the first quote character encountered
anywhere inside. Confirmed for real, with `checkoutButton` still broken: a single `TestRunAndHeal`
invocation now extracts the full `[datatest='checkout']`, not the truncated `[datatest=`, and
heals it to `#checkout` - `[PROGRESS] "Standard Customer Complete Purchase Flow" -
CartPage.java:11 "[datatest='checkout']" -> "#checkout" kept`.

**Chasing the rest of the checkout flow's chain exposed a gap - since fixed - and then a second,
genuinely different one that's still open.** Beyond `checkoutButton`, the same scenario has
several more real pre-existing typos further down, masked by Cucumber's fail-fast until each one
ahead of it is fixed: `CheckoutStepOnePage.lastNameInput` (`"[data-test'lastName']"`, missing
`=`), `zipcodeInput`, `continueButton` (`"[data-test=continue']"`, unterminated quote),
`CheckoutStepTwoPage.finishButton` (`"[data-tes'finish']"`, missing `t=`), and
`CheckoutCompletePage.completeHeader` (`".completeheader"`, missing a hyphen). The first time
this chain was chased, it stopped dead at `lastNameInput`: a raw
`com.microsoft.playwright.PlaywrightException` (not a `TimeoutError` or a visibility
`AssertionFailedError`) that `FailureClassifier` had no pattern for at all, so it classified
`NOT_FIXABLE` and re-running `TestRunAndHeal` made zero further progress, confirmed for real
twice in a row.

**The fix**: a third, independent `FailureClassifier` pattern for `PlaywrightException` whose
message contains `"is not a valid selector"` or `"while parsing selector"` - see the
`FailureClassifier` section above for the two distinct real message/call-log shapes behind those
two substrings, and `LocatorHealer` above for the matching extraction-regex fallback the second
shape needed (its call log has no `locator(...)` wrapper at all - the original guess that *no*
syntax-error call log would have one turned out to be only half right). **Confirmed for real**:
re-chasing the same chain, `TestRunAndHeal` healed `lastNameInput` and `continueButton` in one
invocation (`ai.healer.maxRetries`'s default of `2` accounting for stopping there, not a gap),
then `finishButton` in a second invocation - all three correctly, `id`-vs-`data-test` fix and
all. `zipcodeInput` was never actually broken (an unquoted CSS attribute value is functionally
identical to a quoted one - confirmed by a clean, all-passing run with it in that state).

**`completeHeader` was where the chain stopped - until the question "is this actually a broken
locator?" got answered for real instead of assumed.** Its `AssertionFailedError` message reads
`"Locator expected to have text: Thank you for your order!\nReceived: null"` - at a glance this
looks like it could be a text-content mismatch (a real defect: wrong expected value, or the
app's real copy differing from the test), which would mean auto-healing the *locator* here would
be wrong regardless of what pattern caught it. Verified which one it actually was, rather than
guessing: with `completeHeader` broken to `.completeheader` (missing the hyphen), `"Received:
null"` and **no** `"locator resolved to <...>"` line anywhere in the call log - Playwright's own
signal that the locator matched *zero* elements. Restoring the real, correct `.complete-header`
and re-running confirmed it directly: 5/5 pass, and the real element is `<h2
class="complete-header" data-test="complete-header">Thank you for your order!</h2>` - text
matches exactly, nothing wrong with the app or the expected value. To see the *other* shape for
comparison, the same correct locator was paired with a deliberately wrong expected string:
```
Locator expected to have text: TEMP: deliberately wrong expected text for classifier testing
Received: Thank you for your order!

Call log:
Locator.expect with timeout 5000ms
waiting for locator(".complete-header")
  locator resolved to <h2 class="complete-header" data-test="complete-head…>Thank you for your order!</h2>
  unexpected value "Thank you for your order!"
```
Night-and-day different from the broken-locator case: a real `Received:` value, and a call log
line that echoes back the actual matched element. `completeHeader`'s real failure was genuinely
a broken locator, not a mismatch - so `FailureClassifier` gained a fourth pattern:
`AssertionFailedError` requiring **both** `"Locator expected to have text"` *and* `"Received:
null"` - the second condition is what keeps a real mismatch (the deliberately-wrong-string case
above) correctly `NOT_FIXABLE`, tested for real against both real messages. **Confirmed for
real**: `TestRunAndHeal` healed `completeHeader` to `[data-test='complete-header']` - not the
original `.complete-header`, since the DOM snapshot only captures `data-test`/`id`/etc. (not CSS
classes) and the `data-test`-preference fix above chose it anyway - and the full suite now
passes 5/5. Every locator broken across this session's checkout-flow chain is healed.

---

## A real problem this surfaced — since fixed

Running the real case end-to-end first exposed something worth knowing the history of:
`BasePage.click(selector)` calls Playwright's `page.click(selector)` directly, which means
every page object field is a *bare selector string* — `"#login-button"`, not a Java expression.
But `LocatorHealer`'s prompt originally asked Ollama for "one replacement Playwright Java
locator statement," and the model reasonably answered with exactly that:
`page.locator('#login-button')`. `PageObjectPatcher` did its job faithfully and inserted it
verbatim, so the field ended up reading:
```java
private final String loginButton = "page.locator('#login-button')";
```
That's valid Java — it compiles — but it isn't a valid CSS selector, so the test would still
fail, just with a more confusing error than before. `PageObjectPatcher` was never the right
place to fix this: silently rewriting `page.locator('#login-button')` down to `#login-button`
would be exactly the kind of "deciding what a good fix looks like" it's built not to do.

The honest fix belonged upstream, and that's where it landed: `LocatorHealer`'s schema field was
renamed from `newLocatorCode` to `newSelector`, and its prompt now explicitly asks for "a
replacement bare selector value - not a Java statement," with examples in that shape
(`"#id"`, `"[data-test='x']"`) instead of `page.locator("#id")`. Re-running the exact same real
case end-to-end confirms it: Ollama now answers `newSelector = "#login-button"`, and
`PageObjectPatcher` writes that bare value straight into the field - which happens to be
byte-for-byte identical to what the field held before it was ever broken. Re-running the actual
test suite against the patched file confirms the fix is real, not just textual: the login test
passes.
