# AI Healer — Safety and Best Practices

This page explains, in plain language, how the AI Healer is kept safe to run against real
test code. It's written for anyone evaluating whether to trust this tool, not just developers.

- **The healer uses its own dedicated bot identity, never a real person's account.** When it opens
  pull requests, it authenticates with a separate GitHub token issued to its own bot identity
  (`AI_HEALER_GITHUB_TOKEN`) — not a developer's personal token, and not the same token the
  separate AI code-reviewer tool uses. No human's GitHub account is ever used to make a change on
  their behalf.

- **It only ever opens pull requests — it never merges its own changes.** In a pipeline (CI)
  context, a healed fix is pushed to its own new branch and opened as a pull request against
  `main`. The healer never merges, approves, or closes that PR itself, and it never pushes or
  commits directly to `main`. A human always reviews and merges it, exactly like any other PR.
  When run locally instead of in a pipeline, a fix isn't even committed — it's left as an
  uncommitted change on disk for a developer to look at.

- **Every suggested fix is verified with a real re-run before being kept — no exceptions for a
  "confident" fix.** The AI's suggestion is always applied, the affected test is always re-run for
  real, and only a fix that actually makes the test pass is kept — a fix that doesn't work is
  automatically reverted. This check happens the same way regardless of how confident the AI
  says it is in its own answer; a "high confidence" suggestion gets exactly the same live
  verification as a "low confidence" one, never a shortcut.

- **No credentials are ever stored in a project file — only in Jenkins' secure credential store.**
  The GitHub token the healer uses in a pipeline is read only from an environment variable that
  Jenkins injects from its own credential store at run time. There is deliberately no way to put
  that token in a config file that lives in the project.

- **A real credential-leak risk was found during development and fixed the same day.** Two
  separate issues were found and closed:
  - A developer's real token was found to have been left in a local, personal config file during
    manual testing. The fix removed the ability to configure that token via a file at all — it can
    now only come from an environment variable, so there's no file left for a token to
    accidentally end up in.
  - Separately, a successful `git push` was found to print a confirmation line that included the
    authenticated URL (and therefore the token) in full. The fix stopped that output from ever
    being shown or logged, on success or failure — only a version with the token blanked out is
    ever surfaced, and only when something goes wrong.

- **The system reports honestly when it's uncertain about a fix, rather than claiming false
  confidence.** Every fix suggestion comes with a confidence level and, when relevant, a note that
  the match was ambiguous. That information is carried through to the run's end-of-run summary and
  its written report, so a human reviewing a healed fix can immediately see whether it was a clear
  case or one worth double-checking — the tool never hides its own uncertainty to look more
  reliable than it is.
