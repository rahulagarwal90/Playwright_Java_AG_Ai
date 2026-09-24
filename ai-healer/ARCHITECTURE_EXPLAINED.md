# How ai-healer works, in plain English

Every time you run the test suite (`mvn -pl playwright-tests test`), Maven's Surefire plugin
writes an XML file recording what passed and what failed. Separately, when a test fails,
`Hooks.java` (in `playwright-tests`, not `ai-healer`) takes a snapshot of every clickable thing on
the page at that exact moment and saves it as JSON. `ai-healer` exists to connect those two files
together: read the failure, decide whether it's the kind of failure a broken locator would cause,
and if so, ask a local AI model to look at the real page snapshot and suggest what the locator
*should* have been — using only elements that were genuinely on the page, never a guess.

You don't need to be a Java expert to follow this document. Each class below gets three questions
answered: what real-world problem it solves, one representative real input/output example, and why
it's worth keeping separate from its neighbors instead of folding it into one big class.

## The shape of this module

13 files across 6 responsibilities:

- **Reading failures and DOM snapshots off disk** — `SurefireReportReader`, `TestFailure`, `DomElement`
- **Classifying which failures look like broken locators** — `FailureClassifier`
- **Talking to Ollama to diagnose and suggest a fix** — `LocatorHealer`, `HealerOllamaClient`
- **Applying that fix to a source file** — `PageObjectPatcher`
- **Orchestrating a full run end to end** — `HealOrchestrator`, `TestRunAndHeal`
- **Running Maven subprocesses** — `MavenRunner`

Plus three small shared helpers used across several of the above: `RepoRoot` (finds the repo root
from any class's code source), `HealerConfig` (resolves `ai-healer`'s and `playwright-tests`'
`config.properties` settings), and `ScenarioNameSanitizer` (the one method shared across the module
boundary with `playwright-tests`, so a failure and its DOM snapshot always agree on a filename).

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

TestRunAndHeal runs the full suite first, then hands off to HealOrchestrator on failure.
MavenRunner and RepoRoot are the subprocess/path-resolution plumbing several classes share.
```

## Class summary

One row per class, old and new. The detailed sections below cover the original ones in depth;
the newer ones (feature-file grouping, the pipeline-context git/PR flow, and the run report) are
described here and in `CLAUDE.md`'s `ai-healer` section, not yet expanded into their own
three-question sections below.

| Package | Class | One-sentence purpose |
|---|---|---|
| `com.ai.healer` | `ScenarioNameSanitizer` | The one shared filename-safe transformation applied to a Cucumber scenario name, used by both `playwright-tests`' `Hooks` and `SurefireReportReader` so a DOM snapshot filename can never drift out of sync with the failure it belongs to. |
| `com.ai.healer` | `RepoRoot` | Finds the repository root by walking up from wherever the calling class was loaded, so file paths resolve correctly regardless of the JVM's working directory. |
| `com.ai.healer` | `HealerConfig` | Resolves `ai.healer.maxRetriesPerScenario` (the per-scenario heal-attempt budget) and `playwright.timeout`, each via its own three-tier config lookup. |
| `com.ai.healer` | `HealOrchestrator` | Runs the whole heal chain end to end — reads failures, groups them by feature file, classifies, heals/patches/re-runs each scenario within its own retry budget, and (in pipeline context) branches, PRs, and comments on the result. |
| `com.ai.healer` | `TestRunAndHeal` | The one-click entry point: runs the full test suite, and only on failure hands off to `HealOrchestrator` in the same JVM. |
| `com.ai.healer.report` | `SurefireReportReader` | Reads failed testcases out of Surefire's XML reports and matches each to its captured DOM snapshot. |
| `com.ai.healer.report` | `TestFailure` | Plain data holder for one failed Surefire testcase. |
| `com.ai.healer.report` | `DomElement` | Plain data holder for one interactive element out of a captured DOM snapshot. |
| `com.ai.healer.report` | `FeatureFileResolver` | Matches a Surefire testcase's classname (a Cucumber feature's "Feature:" line) back to the real `.feature` file it came from, reporting a miss with diagnostics instead of throwing. |
| `com.ai.healer.report` | `ScenarioGroup` | One `.feature` file's path plus every scenario failure that belongs to it, and the flat branch-safe name derived from that file's name. |
| `com.ai.healer.classify` | `FailureClassifier` | Pure deterministic pattern matching that decides whether a failure looks like a broken locator (`LOCATOR_FAILURE`) or a real defect (`NOT_FIXABLE`) — no AI involved. |
| `com.ai.healer.ollama` | `LocatorHealer` | Builds the case for one broken locator (extracts it, loads the DOM snapshot, gathers file/line context) and asks Ollama for a replacement selector. |
| `com.ai.healer.ollama` | `HealerOllamaClient` | The self-contained HTTP client that actually calls Ollama's `/api/chat` endpoint with a structured-output schema. |
| `com.ai.healer.patch` | `PageObjectPatcher` | Applies one healed selector to a page object source file with a minimal, surgical string-literal swap — never runs tests or touches git. |
| `com.ai.healer.exec` | `MavenRunner` | Shared `mvn` subprocess launcher, with and without a watchdog timeout. |
| `com.ai.healer.github` | `HealerGitClient` | Creates a new branch off HEAD, stages a healed group's changed files, commits, and pushes it to origin — plain `git` subprocess calls, never touches `main` directly. |
| `com.ai.healer.github` | `HealerGitHubConfig` | Resolves the GitHub repository/API base (mirroring `ai-reviewer`'s `GitHubContext`) and a new, separate `ai.healer.github.token`/`AI_HEALER_GITHUB_TOKEN` used only by ai-healer's own pipeline git/PR flow. |
| `com.ai.healer.github` | `HealerPullRequestCreator` | Opens a GitHub PR from the branch `HealerGitClient` just pushed, targeting `main` — creation only, never merges or approves. |
| `com.ai.healer.github` | `NotFixablePrCommenter` | Posts one PR comment per `NOT_FIXABLE` failure encountered while processing a group that got a PR. |
| `com.ai.healer.output` | `HealerRunReport` | Writes a single flat JSON file summarizing one run — what was healed per feature file, `NOT_FIXABLE` entries not otherwise posted to a PR, and any unresolved-feature diagnostics. |
| `com.ai.healer.output` | `HealerRunReportHtml` | Writes the same run data as a static HTML page, with its styling in a separate `healer-run-report.css` next to it (linked, not inline, because Jenkins' artifact CSP blocks inline styles — so both files must be archived). |

---

## ScenarioNameSanitizer

**Problem it solves:** Cucumber scenario names have spaces and punctuation — "Standard User can
login successfully" — but that string needs to become part of a filename, and filenames don't like
spaces or punctuation.

**In → out:** `"Standard User can login successfully"` → `"Standard_User_can_login_successfully"`
(every character that isn't a letter, digit, underscore, or hyphen becomes an underscore).

**Why it's separate:** This exact transformation has to happen in two places that don't otherwise
know about each other: `Hooks.java` in `playwright-tests` uses it to name the DOM snapshot file it
writes, and `SurefireReportReader` here uses it to *guess* what that filename must have been,
starting only from the Surefire XML test name. A shared method makes drift between the two sides
impossible instead of just unlikely — which is also why this class must stay public and top-level
rather than folded into anything else: `Hooks.java` lives in a different module and can only reach
a public class.

---

## SurefireReportReader

**Problem it solves:** After a run, results live in `playwright-tests/target/surefire-reports/*.xml`
— mostly Maven/JUnit bookkeeping. This class finds the *failed* tests specifically, pulls out the
handful of facts that matter, and matches each to its DOM snapshot file — without caring *why*
anything failed.

**In → out:** A real Playwright timeout in the XML lands inside a JUnit `<error>` element (not
`<failure>` — worth knowing, since `SurefireReportReader` reads both the same way), and produces one
`TestFailure`: `testName`, `className`, `failureType` (`com.microsoft.playwright.TimeoutError`),
`failureMessage` (the full call-log text), `stackTrace`, and a resolved `domSnapshotPath` +
`domSnapshotFound` flag.

**Refuses to silently process nothing.** A missing `surefire-reports` directory, an empty one, or
one that's stale relative to the invocation reading it (an optional `healer.minReportTimestamp`
system property, set by `TestRunAndHeal`/`run-tests-and-heal.sh` before running tests) each log a
specific, human-readable reason and return an empty list — confirmed for real against all four
cases (missing, empty, stale, and a genuinely all-passing report), each producing its own distinct
message so a caller never mistakes "no report" for "the suite just passed."

**Why it's separate:** Zero opinion about whether a failure is fixable, zero knowledge that Ollama
exists. If Maven ever changes its report format, only this class changes.

---

## TestFailure

**Problem it solves:** A plain data holder — six or seven facts about one failure, carried together
so nothing has to pass them as separate parameters. Deliberately logic-free: `FailureClassifier`
reads `failureType`/`failureMessage`; `LocatorHealer` reads `failureMessage`, `stackTrace`,
`domSnapshotPath`, `domSnapshotFound` — all from the same object, so none of the three producing or
consuming classes need to know how the others work.

---

## DomElement

**Problem it solves:** Represents one entry from the DOM snapshot JSON `Hooks.java` writes as a Java
object, so the rest of the code works with real page elements instead of raw JSON.

**In → out:** `{"tag":"INPUT","id":"login-button","dataTest":"login-button","dataTestId":null,"className":null,"role":null,"aria":null,"text":""}`
becomes one `DomElement` with matching fields.

**Fields, and where each comes from:** `tag`/`id`/`role`/`aria`/`text` are read directly off the
matched element. `dataTest` and `dataTestId` are read independently off the element's real
`data-test`/`data-testid` attributes (`e.dataset.test||null` / `e.dataset.testid||null`) — they
used to be a single merged `testId` field (`e.dataset.test||e.dataset.testid`), which meant
`LocatorHealer` could never tell which real attribute a given value actually came from; split into
two real fields so it can. `className` is the element's raw `class` attribute, captured after a
real scan of this codebase's page-object locators found class-based selectors in genuine use
(`CartPage.cartItemName`, `InventoryPage.cartIcon`, `CheckoutStepTwoPage.itemTotalLabel`) — captured
but not yet surfaced by `LocatorHealer.formatCandidates()` or its uniqueness-checking, a deliberate
scope boundary. **One naming trap still worth knowing:** none of `dataTest`/`dataTestId`/`className`
are real HTML attribute names — they're this class's *Java* field names, chosen to match the JSON
keys `Hooks.java` writes. The actual attributes they were read from only resurface when
`LocatorHealer` builds its Ollama prompt, and getting that relabeling right mattered a lot (see
below).

**Why it's separate:** Tightly coupled to `Hooks.java`'s JSON shape on purpose — if that shape ever
changes, one class changes, not scattered code throughout `LocatorHealer`.

---

## FailureClassifier

**Problem it solves:** Not every failure is a broken locator — a real product bug, a bad assertion,
flakiness. Before spending an AI call "fixing" a locator, something has to decide, with fast,
deterministic, no-AI checks, whether a failure even looks like one. Erring too eager is the
dangerous direction: waving a real defect through as "just a broken locator" would mask it, so every
pattern below is deliberately narrow.

Six recognized `LOCATOR_FAILURE` shapes, each found chasing a real pre-existing typo (or, for
patterns 5–6, a real live-triggered case) in this codebase, not invented:

| # | Trigger | Real example found |
| --- | --- | --- |
| 1 | `TimeoutError` whose call log contains `waiting for locator` and no `resolved to N elements` with N > 0 | `LoginPage.loginButton` timing out on a broken `id` selector |
| 2 | `AssertionFailedError` containing `"expected to be visible"` — Playwright's web-first `assertThat(...).isVisible()` timing out is the same "never resolved" problem as pattern 1, just a different API | `InventoryPage.inventoryContainer` (`"#nventory_container"`, missing the `i`) |
| 3 | `PlaywrightException` containing `"is not a valid selector"` (the browser's `querySelectorAll` rejecting it) or `"while parsing selector"` (Playwright's own CSS parser rejecting it first — call log has no `locator(...)` wrapper at all in this sub-case) | `CheckoutStepOnePage.lastNameInput` (`"[data-test'lastName']"`, missing `=`) and `continueButton` (unterminated quote) |
| 4 | `AssertionFailedError` requiring **both** `"Locator expected to have text"` **and** `"Received: null"` — the `Received: null` half is what proves zero elements matched (vs. a real text mismatch, where `Received:` names the actual wrong text and the call log shows `locator resolved to <...>`) | `CheckoutCompletePage.completeHeader` (`.completeheader`, missing a hyphen) |
| 5 | `AssertionFailedError` containing `"expected to be enabled"` (`.isEnabled()`) with **no** `resolved to` text anywhere in the message — unlike patterns 4/6, a failed `isEnabled()` has no `Received:` line at all, so a *found-but-disabled* element is distinguished by the call log dumping it inline instead (`locator resolved to <input disabled ...>`); its presence means the element genuinely resolved and is genuinely disabled, so that case stays `NOT_FIXABLE` | Triggered live against `demoqa.com/radio-button`'s `"No"` option (`id="noRadio"`, disabled by the site itself): the real locator produced `resolved to <input disabled ...>` (`NOT_FIXABLE`); a typo'd `#noRadio-typo` produced the identical message text but no `resolved to` anywhere (`LOCATOR_FAILURE`) |
| 6 | `AssertionFailedError` requiring **both** `"Locator expected to have value"` **and** `"Received: null"` (`.hasValue()`) — same discriminator as pattern 4, applied to the same assertion family | Triggered live against `CheckoutStepOnePage.lastNameInput` (real value `"Doe"`): a typo'd `[data-test='lastName-typo']` produced `Received: null` with no `resolved to` line (`LOCATOR_FAILURE`); the correct locator with a deliberately wrong expected value produced `Received: Doe` plus `locator resolved to <input ... value="Doe" ...>` (`NOT_FIXABLE`, a real value mismatch, not a locator problem) |

Any of the above with `resolved to N elements`, N > 0, is treated as `NOT_FIXABLE` regardless —
the locator worked, so patching it wouldn't fix whatever's actually timing out.

**Why it's separate:** Plain string matching, no AI, so the same failure always gets the same
verdict and a human can read the `if` checks to know exactly why. Keeping this apart from
`LocatorHealer` means a change to "how do we build the prompt" can never accidentally change "what
counts as fixable."

---

## HealerOllamaClient

**Problem it solves:** Owns "how do we talk to Ollama" — building the HTTP request, POSTing to
`/api/chat`, checking the response, returning the raw structured-output text — as a self-contained
skill separate from "what do we ask it" (`LocatorHealer`'s job).

**Config resolution.** `model()` resolves three-tier, same order as `ai-reviewer`'s
`OllamaConfig.model()`: `ai-healer/config.properties`'s `ollama.model` key first, then the
`OLLAMA_MODEL` env var, then the hardcoded default `qwen2.5-coder:14b` — the resolution logic is
duplicated here rather than imported, since `ai-healer` has no dependency on `ai-reviewer` (that
sharing decision stays parked). `baseUrl()` only resolves via `OLLAMA_BASE_URL` then a hardcoded
default — no config-file tier yet, since nothing has needed to point the Healer at a non-default
Ollama server. A package-private `resolveModel()` returns both the value *and* which tier supplied
it (`ResolvedModel(value, source)`), used by `LocatorHealer`'s heal-trace logging so a run's output
says *why* a given model was used, not just which one — see `LocatorHealer` below.

**Why it's separate:** If the Healer ever needs a different AI provider, retry logic, or a changed
resolution order, exactly one class needs surgery — and it has no idea what a "broken locator" is,
so that change can't accidentally alter what gets asked.

---

## LocatorHealer

**Problem it solves:** Turns raw ingredients — a `TestFailure` with a broken locator buried in an
exception message, and a DOM snapshot on disk — into a good question for Ollama, then turns the
answer back into something usable. This is where the actual "healing" idea lives; the HTTP
mechanics are `HealerOllamaClient`'s job.

**Extraction.** The broken locator is pulled from the failure message with a regex that captures
the wrapping quote character and backreferences it for the closing delimiter, rather than a
character class that excludes both quote types — needed because a broken locator can itself
contain a quote of the *other* kind (e.g. `CartPage`'s real `"[datatest='checkout']"`, a
single-quoted attribute value, inside Playwright's double-quoted `waiting for locator(...)`
wrapper). A second fallback regex handles pattern 3's unparseable-selector shape, whose call log has
no `locator(...)` wrapper at all — just `waiting for <raw selector text>`.

**File/line resolution.** A stack frame only points at the locator's *call site*
(`click(loginButton);`), not its *field declaration*. `LocatorHealer` skips `BasePage`'s generic
wrapper frame (the same line for nearly every locator failure) in favor of the concrete page-object
frame after it, then re-reads that file and searches for the `private final String X = "...";` line
whose literal exactly matches the broken locator, falling back to the call-site line if no such
declaration is found.

**The prompt** asks for a bare selector value — `"#id"`, `"[data-test='x']"` — not a Java statement,
since page object fields hold exactly that kind of string, consumed via `BasePage`'s
`page.click(selector)`. (An earlier version asked for "one replacement locator statement" and got
back `page.locator('#login-button')`, which `PageObjectPatcher` wrote in verbatim — valid Java, but
not a valid selector. Fixed by renaming the schema field to `newSelector` and rewording the prompt;
confirmed for real, the same case now produces `newSelector = "#login-button"` and the healed test
passes.) The prompt also has an explicit rule to prefer a candidate's `data-test`/`data-testid`
value over its `id` whenever both are present, matching this codebase's own convention (see
`InventoryPage.addProductToCart()`, which builds `[data-test='...']` by hand) — **currently absent
from the live prompt**, see Known gaps below.

**Uniqueness check, with one representative example.** Before prompting, every candidate property —
`id` and `data-test`/`data-testid` included, not just `text`/`role`/`aria` — is actually counted
across the candidate list, not assumed unique by convention. A value shared by more than one
candidate is marked `[NOT UNIQUE]`; an `id`/`data-test`/`data-testid` value the code confirmed
appears on exactly one candidate is marked `[VERIFIED UNIQUE]` instead of being left unmarked, so
"unique" always means "the code counted exactly one occurrence," never an assumption (a markup bug
or a doubly-rendered template could in principle give two elements the same `id`, and a locator
built from it in that rare case would be just as ambiguous as one built from shared text). SauceDemo's
inventory page is the clean real case: three "Add to cart" buttons share identical visible text but
each has its own `data-test` value.

```
1. tag=BUTTON data-test=add-to-cart-sauce-labs-backpack [VERIFIED UNIQUE] text="Add to cart" [NOT UNIQUE]
2. tag=BUTTON data-test=add-to-cart-sauce-labs-bike-light [VERIFIED UNIQUE] text="Add to cart" [NOT UNIQUE]
3. tag=BUTTON data-test=add-to-cart-sauce-labs-bolt-t-shirt [VERIFIED UNIQUE] text="Add to cart" [NOT UNIQUE]

→ real response: newSelector = "[data-test='add-to-cart-sauce-labs-backpack']", confidence = "high"
```

(`data-test` and `data-testid` are printed as two separate labels, each with its own
`[VERIFIED UNIQUE]`/`[NOT UNIQUE]` marker, rather than one merged `data-test/data-testid` label -
see `DomElement`'s field split above.)

The model correctly reached for the `[VERIFIED UNIQUE]` `data-test` value instead of the shared,
`[NOT UNIQUE]` text. With the `data-test` values removed entirely (three identical text-only
`<div>`s, nothing unique at all), the model still answered rather than refusing, but honestly set
`confidence: "low"` with an explicit ambiguity note — the intended behavior when no candidate has a
`[VERIFIED UNIQUE]` id/data-test/data-testid to break the tie.

**Confidence now gated on verified uniqueness, not model judgment.** `system-prompt.md` now requires
`confidence: "high"` only when the matched candidate's id or data-test/data-testid is marked
`[VERIFIED UNIQUE]` — never merely because the model judges a text/role/aria value "looks" unique,
and never when the best-matching candidate's id/data-test/data-testid is itself `[NOT UNIQUE]` or
absent. This closes a gap where the model could previously call itself "high confidence" based on a
property that was never actually checked for uniqueness in code.

**One naming trap, already fixed.** The candidate list labels an element's `data-test`/`data-testid`
value as exactly that — never `testId` (`DomElement`'s Java field name, not a real HTML attribute).
An earlier version used the `testId` label and the model dutifully echoed back `[testId='...']`, a
selector that matches nothing on a real page.

**Heal-trace logging.** Every `heal()` call prints a clean, five-line narrated trace via
`System.out` (no per-line timestamp/class-name prefix, same reasoning as `HealOrchestrator`'s run
summary below):

```
[HEAL] Model: qwen3:14b (from ai-healer/config.properties)
[HEAL] System prompt loaded from: classpath:/system-prompt.md
[HEAL] DOM snapshot loaded from: .../Standard_User_can_login_successfully-dom.json (7 candidate elements)
[HEAL] Sending diagnosis request to Ollama...
[HEAL] Response: newSelector="#login-button", matchedElement="...", confidence="high"
```

The full system/user prompt text and the raw Ollama response JSON — previously dumped
unconditionally on every call, cluttering the terminal — are still available, just gated behind
`-Dhealer.verbose=true` (same system-property-flag convention as `healer.skipArtifactCleanup`).
**Confirmed for real**: a live `TestRunAndHeal` run against a broken `loginButton` printed exactly
the five lines above with no raw dumps by default, and `LocatorHealerTest` confirmed 0 dump lines
without the flag versus 12 with it.

The system prompt itself moved out of an inline Java text block into a packaged classpath resource,
`ai-healer/src/main/resources/system-prompt.md` — mirroring `ai-reviewer`'s
`OllamaReviewClient`/`system-prompt.md` pattern exactly, and making the `"System prompt loaded
from:"` trace line honest rather than aspirational.

**Why it's separate:** The "case manager" — understands the shape of the whole problem (broken
locator + context + real candidates = a good question) without knowing how to physically talk to
Ollama (`HealerOllamaClient`), read Surefire XML (`SurefireReportReader`), or decide whether a
failure was worth investigating (`FailureClassifier`). Mirrors how `ai-reviewer` splits
`OllamaReviewClient` from `LocalCodeReviewer`.

**Known gap, not yet fixed:** the "prefer `data-test`/`data-testid` over `id`" rule described above
is currently missing from the live `system-prompt.md` (removed from the working tree before a prior
session, never restored) — a candidate with both attributes may currently come back with an
`id`-based selector instead of this codebase's `data-test` convention.

---

## PageObjectPatcher

**Problem it solves:** Applies a `HealResult` to disk: opens the target file, changes *exactly* the
broken line, leaves every other byte untouched — and refuses if the given location doesn't actually
look like a locator field. Deliberately not smart: never judges whether the new locator is *good*,
only performs the edit carefully.

**In → out:** Given `filePath=LoginPage.java`, `lineNumber=13`, `newSelector="#login-button"`, it
first checks line 13 matches `private final String <name> = "...";` exactly, then swaps just that
line's literal content — confirmed byte-for-byte identical before/after apart from that one line.
Pointed at a non-locator line (e.g. a constructor), it refuses with a plain-English reason and
leaves the file untouched. It also refuses if the replacement selector itself contains a `"` that
would break the string literal.

**Why it's separate:** "Does this edit preserve everything else" is a fundamentally different
responsibility than "is this the right fix" (`LocatorHealer`'s job) or "is this fix worth keeping"
(`HealOrchestrator`'s job) — keeping them apart means this class can be trusted never to corrupt a
file, full stop.

---

## HealOrchestrator

**Problem it solves:** Runs the whole pipeline end to end: reads every failure in the Surefire
report, skips `NOT_FIXABLE` ones for a human, and for every `LOCATOR_FAILURE` asks `LocatorHealer`
for a fix. In a GitHub PR pipeline context (detected the same way `ai-reviewer`'s
`GitHubContext.isPresent()` does, duplicated rather than depended on) it only logs the suggestion —
nothing is patched or re-run, matching the "suggestion only" design CI needs. In a local checkout it
goes further: `PageObjectPatcher` applies the fix, then `HealOrchestrator` re-runs *just that one
scenario* via `MavenRunner` to verify.

**Keep-or-revert logic.** A scenario can have more than one broken locator, and Cucumber's fail-fast
only ever reveals one at a time — fixing the first can unmask a second. Rather than revert a correct
fix just because the re-run still fails (on a *different* locator), `HealOrchestrator` compares the
fresh failure's locator against the one it just patched: same locator → the fix didn't work, revert;
different locator (or no locator failure at all) → the fix was fine, keep it, and chase the
newly-unmasked failure in turn. This costs one unit of a shared `ai.healer.maxRetries` budget per
heal attempt (across *every* failure processed in the run, not per-failure) — resolved by
`HealerConfig` (see below). An earlier version reverted on *any* still-failing re-run, which meant a
second invocation could never make progress on a multi-locator scenario; fixed by the
locator-comparison logic above.

**One representative retry-budget trace**, healing a real two-locator chain
(`CartPage.checkoutButton` masking `CheckoutStepOnePage.lastNameInput`) with `maxRetries=2`:

```
==========================================
HEALER RUN SUMMARY
==========================================
Model: qwen2.5-coder:14b
Attempt 1/2:
  [HEALED]        CartPage.checkoutButton: "[datatest='checkout']" -> "#checkout"
Attempt 2/2:
  [HEALED]        CheckoutStepOnePage.lastNameInput: "[data-test'lastName']" -> "#last-name"
------------------------------------------
RESULT: 2 of 2 attempts succeeded, retry budget (maxRetries=2) reached.
If failures remain, re-run this command again to continue healing further.
Files changed (uncommitted, please review): CartPage.java, CheckoutStepOnePage.java
==========================================
```

Both locators healed and kept in one invocation — confirmed for real, file byte-for-byte identical
to its known-correct state afterward. When the budget runs out before a chain fully resolves, the
`RESULT:` line says so explicitly and tells the user to re-run the same command — the correct,
expected way to work through a chain longer than `maxRetries` (only one new failure surfaces per
re-run), not a bug. This exact chasing behavior also found and fixed several more real pre-existing
locator typos across the checkout flow in earlier sessions (`continueButton`, `finishButton`,
`completeHeader` — the last one via `FailureClassifier` pattern 4 above), each healed correctly
across one or two chained invocations. Printed via `System.out`, not the logger — no per-line
timestamp/class-name prefix, deliberately.

**One more fix worth knowing:** each re-run spawns a fresh `mvn test` subprocess, and that
subprocess's own `Hooks` used to clear `target/dom-snapshots/` on startup — wiping out *other*
not-yet-processed failures' snapshots before their turn came. Fixed by `HealOrchestrator` passing
`-Dhealer.skipArtifactCleanup=true` on every re-run subprocess, which `Hooks.setup()` checks before
clearing anything.

**Staleness guard:** an optional `healer.minReportTimestamp` system property (set by
`TestRunAndHeal`/`run-tests-and-heal.sh` before running tests) stops `HealOrchestrator` from ever
processing yesterday's failures against today's source files — see `SurefireReportReader` above.

**Why it's separate:** The only class allowed to decide "is this fix good enough to keep" and "are
we even allowed to change files here" — every other class reads, asks, or edits, but none of them
judge the *result*. That judgment needed to be separate from `PageObjectPatcher` (which must never
judge, only edit) for the same reason `FailureClassifier` stays separate from `LocatorHealer`.

---

## TestRunAndHeal

**Problem it solves:** The one-command entry point (`mvn -pl ai-healer exec:java
-Dexec.mainClass=com.ai.healer.TestRunAndHeal`): captures a timestamp, runs `mvn -pl
playwright-tests test` via `MavenRunner`, and — only if that fails — calls `HealOrchestrator.main()`
directly as a Java method call in the *same* JVM (not a second `mvn` subprocess; both classes live
in `ai-healer`), passing the captured timestamp through so the staleness guard applies exactly as it
does for `run-tests-and-heal.sh`. If the suite passes, it prints `"All tests passed - nothing to
heal"` and returns.

**Why it's separate from `HealOrchestrator`:** "Run the tests, then maybe heal" and "heal whatever's
currently on disk" are different entry points with different assumptions about freshness —
`HealOrchestrator` alone can also be pointed at an existing, already-fresh report (e.g. in a
pipeline that ran tests in an earlier stage).

`run-tests-and-heal.sh` (repo root) does the same job across two separate `mvn` invocations chained
by a shell script — kept as a documented fallback, not the primary path.

---

## MavenRunner

**Problem it solves:** The shared `mvn` subprocess-launching helper, factored out of
`HealOrchestrator`'s scenario re-run so `TestRunAndHeal` doesn't duplicate the
`ProcessBuilder`/`inheritIO()` plumbing. One overload runs to completion with no watchdog (for a
manual, attended invocation a human is watching live and can interrupt themselves); the other adds a
watchdog timeout that kills a genuinely hung subprocess (for `HealOrchestrator`'s own unattended
re-run loop).

**Why it's separate:** Pure process-management, with no idea what Cucumber, a locator, or a heal
attempt is — a change to subprocess handling can never accidentally change heal logic.

---

## RepoRoot

**Problem it solves:** Locates the repository root — the directory containing both sibling modules,
`playwright-tests` and `ai-reviewer` — by walking up from wherever the calling class was loaded, so
path resolution works regardless of the JVM's working directory. Used by six different classes for
six different reasons: `SurefireReportReader` (finding `playwright-tests/target/...`), `LocatorHealer`
(resolving a page object's absolute source path), `HealOrchestrator`/`TestRunAndHeal` (spawning
Maven subprocesses from the right directory), and `HealerConfig`/`HealerOllamaClient` (finding their
own config files).

**Why it's separate — and not folded into anything else:** It's a generic filesystem utility with no
opinion about configuration, failures, or locators; folding it into e.g. `HealerConfig` would make
"config resolution" also own "generic repo-root walking," and would force every other consumer
(`SurefireReportReader`, `LocatorHealer`, `HealOrchestrator`, `TestRunAndHeal`) to depend on a class
about config just to resolve a path. Mirrors `ai-reviewer`'s `ModuleRoot`, kept self-contained here
since `ai-healer` has no dependency on that module.

---

## HealerConfig

**Problem it solves:** Resolves the two settings `HealOrchestrator` needs, each from its own file
since one is an `ai-healer` setting and the other is a genuine Playwright one:

- **`ai.healer.maxRetries`** — the max heal-attempt-cycle budget per `run()` — resolves three-tier
  from `ai-healer/config.properties`, then `AI_HEALER_MAX_RETRIES`, then a hardcoded default of `2`.
  It used to live in `playwright-tests`' config.properties; moved here since it was never a
  Playwright test-execution setting, only ever governing how many heal cycles `HealOrchestrator`
  spends.
- **`playwright.timeout`** — Playwright's action/navigation timeout, which `HealOrchestrator`'s
  re-run subprocess watchdog derives from (`max(60000, playwright.timeout × 8)`) — resolves from
  `playwright-tests/src/test/resources/config.properties` then a hardcoded default only, no env var,
  since nothing has needed to override it that way.

Both are read directly with plain `java.util.Properties` rather than through `playwright-tests`'
`FrameworkConfig` (an Owner-library interface compiled into a different module) — `ai-healer` has no
dependency on either sibling module.

**Why it's separate:** Config resolution for two settings that happen to live in two different files
is exactly the kind of narrow, boring responsibility that shouldn't leak into `HealOrchestrator`
itself — a change to *where* a setting is read from should never risk changing *how* the retry loop
behaves.

---

## Known gaps

- **`LocatorHealer`'s "prefer `data-test`/`data-testid` over `id`" prompt rule is currently missing**
  from the live `system-prompt.md` (see `LocatorHealer` above) — not fixed by this document's most
  recent pass.
- **`FailureClassifier`'s Naming Conventions/Code Style-style deterministic judgment calls don't
  apply here** (that limitation lives in `ai-reviewer`, not `ai-healer`) — no open item to note.
- `zipcodeInput` (`CheckoutStepOnePage`) was investigated and found *not* broken: an unquoted CSS
  attribute value (`[data-test=postalCode]`) is functionally identical to a quoted one, confirmed by
  a clean, all-passing run with it in that state — worth knowing so it isn't re-investigated.
