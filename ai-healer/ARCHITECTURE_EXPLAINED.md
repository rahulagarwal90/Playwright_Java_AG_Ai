# How ai-healer works, in plain English

This document walks through every class in `ai-healer`, using one real test run as the
running example throughout: the SauceDemo login test, with its `loginButton` locator
temporarily broken from `#login-button` to `#login-button-BROKEN-TEMP` so we could watch
the whole pipeline handle a real failure.

You don't need to be a Java expert to follow this. You just need to follow one webpage's
worth of story: a test clicked a button that wasn't there, and now seven small classes work
together to figure out what the *real* button was called.

## The story in one paragraph

Every time you run the test suite (`mvn -pl playwright-tests test`), Maven's Surefire plugin
writes an XML file recording what passed and what failed. Separately, when a test fails,
`Hooks.java` (in `playwright-tests`, not `ai-healer`) takes a snapshot of every clickable
thing on the page at that exact moment and saves it as JSON. `ai-healer` exists to connect
those two files together: read the failure, decide whether it's the kind of failure a broken
locator would cause, and if so, ask a local AI model to look at the real page snapshot and
suggest what the locator *should* have been — using only elements that were genuinely on the
page, never a guess.

Seven classes divide that job up:

```
SurefireReportReader  →  FailureClassifier  →  LocatorHealer  →  HealerOllamaClient
   (reads the XML)      (is this fixable?)   (builds the case)   (makes the phone call)
        ↓                                           ↑
   TestFailure  ────────────────────────────────────┘
  (the case file passed between all three)
        ↑
DomElement (one page element, many of these live inside the DOM snapshot)

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

**Why it's its own class:** This logic needs to stay boring and predictable on purpose — it's
plain string matching with no AI involved, so the same failure always gets the same verdict,
and a human can read the three `if` checks and know exactly why something was or wasn't
classified as fixable. Merging this into `LocatorHealer` would mean every future change to
"how do we build the AI prompt" risks also silently changing "what counts as fixable" — two
very different kinds of edits that should never accidentally affect each other.

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
these.

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
  "newLocatorCode": "page.locator('#login-button')",
  "matchedElement": "tag=INPUT id=login-button testId=login-button",
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
`LoginPage.java:29`, the actual line where `click(loginButton)` was called.

*Step 4 — build the prompt.* The real user-facing prompt sent to Ollama was:
```
BROKEN LOCATOR: #login-button-BROKEN-TEMP
USED AT: LoginPage.java:29

CANDIDATE ELEMENTS:
1. tag=DIV testId=login-container text="Accepted usernames are:
standard_user
lo"
2. tag=INPUT id=user-name testId=username
3. tag=INPUT id=password testId=password
4. tag=INPUT id=login-button testId=login-button
5. tag=DIV testId=login-credentials-container text="Accepted usernames are:
standard_user
lo"
6. tag=DIV id=login_credentials testId=login-credentials text="Accepted usernames are:
standard_user
lo"
7. tag=DIV testId=login-password text="Password for all users:
secret_sauce"
```
alongside a fixed system prompt instructing the model to build its answer *only* from values
that appear in that candidate list — never to invent an id or testId that isn't shown.

*Step 5 — hand that off to `HealerOllamaClient` and parse what comes back.* The real answer,
shown under `HealerOllamaClient` above, becomes a `HealResult`:
```
newLocatorCode = "page.locator('#login-button')"
matchedElement = "tag=INPUT id=login-button testId=login-button"
confidence     = "high"
```
Candidate #4 is genuinely the right element — it's what the locator pointed to before we
broke it on purpose for this test — and the model picked it correctly, from real page data,
inventing nothing.

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

## What's still missing

Nothing in `ai-healer` writes to `LoginPage.java` yet. `LocatorHealer` produces a suggested
fix and stops there — actually patching the source file, and deciding when a `"low"`
confidence result is trustworthy enough to apply automatically, is later work.
