# Playwright Java BDD Automation & AI Reviewer Framework

The previous documentation set (`README_INSTALLATION.md`, `README_IMPLEMENTATION_AND_RUNNING.md`, `Ollama_Readme.md`, `docs/jenkins_setup.md`, `docs/framework_architecture.md`) has been preserved with an `ARCHIVE_` prefix for history and extra detail; this file is the current source of truth.

---

## 1. What this project is

This repository is a two-module Maven project. `playwright-tests` is a Cucumber/Playwright browser automation framework (Page Objects, step definitions, feature files, Allure/JUnit reporting). `ai-reviewer` is a standalone Java tool that reads a git diff and asks a local Ollama model to review it against six fixed automation-quality categories — legacy assertions, brittle locators, hardcoded waits, console logging, naming conventions, code style — defined in `ai-reviewer/src/main/resources/system-prompt.md` and enforced via Ollama's structured-output JSON schema, not free-text parsing. Wired into Jenkins, it runs automatically on every GitHub pull request, posts inline PR comments for anything it flags as `FAILED`, and blocks merge until those are resolved. It also has a `learn` mode: a human can reply to one of its comments with `@ai-learn ...`, and the reviewer converts that feedback into a standing rule it applies to every future review.

---

## 2. Daily quick-start

Run these in order. Steps 1–3 are one-time-per-session infrastructure; steps 4–6 are the commands you'll actually run repeatedly while working.

```bash
# 1. Start Ollama — serves http://localhost:11434, used by both review mode and learn mode
ollama serve

# 2. Start ngrok so GitHub can reach your local Jenkins
#    The forwarding URL changes every time ngrok restarts. When it does, update the
#    GitHub webhook's Payload URL (repo Settings > Webhooks) to match the new URL.
ngrok http 8080

# 3. Confirm Jenkins is running and reachable
curl -s -I http://localhost:8080
#    If it's not up:  brew services start jenkins-lts   (Homebrew)
#                 or:  docker start jenkins              (Docker)

# 4. Run the AI reviewer locally, standalone — reviews your current git diff
mvn -pl ai-reviewer clean compile exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer"

# 5. Run learn mode — turns @ai-learn PR comments into rules in ai-reviewer/learned-rules.json.
#    Requires four environment variables:
export GITHUB_REPOSITORY="<owner>/<repo>"          # which repo to read PR comments from
export GITHUB_PR_NUMBER="<pr-number>"               # which PR's comments to fetch
export GITHUB_TOKEN="<classic-PAT-with-repo-scope>" # auth for every GitHub REST API call
export GITHUB_BOT_USERNAME="<bot-account-login>"    # excludes the bot's own comments from being learned
mvn -pl ai-reviewer exec:java -Dexec.mainClass="com.ai.reviewer.LocalCodeReviewer" -Dexec.args="learn"

# 6. Run the Playwright test suite
mvn -pl playwright-tests clean test
```

Notes on step 4: without any GitHub PR context set, the reviewer reviews your local `git diff HEAD` and prints findings to the terminal only — that's the normal way to sanity-check changes before pushing. See [Section 4](#4-how-the-reviewer-works) for what changes when real PR context is present.

**Stopping Ollama**: if you started it with `ollama serve` in a visible terminal tab, `Ctrl+C` in that tab. If it's running in the background or you've lost track of which terminal it's in, `pkill ollama`.

---

## 3. Environment variables

Every environment variable the reviewer reads, in one table. `GitHubContext` (`com.ai.reviewer.github`) reads `GITHUB_REPOSITORY`, `GITHUB_PR_NUMBER`, `CHANGE_ID`, `GITHUB_TOKEN`, `CHANGE_URL`, and `GITHUB_API_URL`; `CommitShaResolver` reads `GIT_COMMIT`. (The remaining reviewer settings — Ollama model, base URL, context window, and the bot's GitHub username — are also overridable as environment variables, but go through `OllamaConfig`'s layered config system; see [Section 3a](#3a-local-config-configproperties) below.)

| Variable | Purpose | Required for |
| --- | --- | --- |
| `GITHUB_REPOSITORY` | Repository identifier in `owner/repo` form, used to build every GitHub API URL. | Review + Learn (or derivable from `CHANGE_URL`) |
| `GITHUB_PR_NUMBER` | The pull request number to fetch the diff/comments for and post comments to. | Review + Learn (or use `CHANGE_ID`) |
| `CHANGE_ID` | Jenkins Multibranch Pipeline's automatic PR-number variable; used as a fallback wherever `GITHUB_PR_NUMBER` isn't set. Jenkins sets this for you. | Review + Learn (fallback) |
| `GITHUB_TOKEN` | Bearer token used to authenticate every GitHub REST API call — fetching the diff, listing changed files, posting inline comments, and (in learn mode) fetching PR comments. | Review + Learn |
| `CHANGE_URL` | Jenkins Multibranch Pipeline's automatic PR URL; parsed to derive `owner/repo` as a fallback when `GITHUB_REPOSITORY` isn't set. Jenkins sets this for you. | Review + Learn (fallback) |
| `GITHUB_API_URL` | Overrides the GitHub REST API base URL, e.g. for GitHub Enterprise. Defaults to `https://api.github.com` if unset. | Review + Learn (optional) |
| `GIT_COMMIT` | Commit SHA used to anchor inline PR comments to the right diff. Falls back to `git rev-parse HEAD` locally if unset; Jenkins sets this for you. | Review only (optional) |

`GITHUB_REPOSITORY`, a PR number (`GITHUB_PR_NUMBER` or `CHANGE_ID`), and `GITHUB_TOKEN` together are what the code calls "GitHub context" — all three must be present for either mode to talk to the GitHub API at all.

### 3a. Local config: config.properties

For local development, copy `ai-reviewer/config.properties.example` to `ai-reviewer/config.properties` and edit it there — it's the personal, untracked file each developer edits (gitignored; `config.properties.example` stays tracked as the template). Jenkins/CI has no interactive session to edit a file, so it should keep setting the matching environment variables instead, exactly as before. Every setting below is resolved in the same order regardless of where you're running: `config.properties` first, then the environment variable, then the hardcoded default.

| Setting | `config.properties` key | Environment variable | Default |
| --- | --- | --- | --- |
| Ollama model | `ollama.model` | `OLLAMA_MODEL` | `qwen2.5-coder:14b` |
| Ollama base URL | `ollama.baseUrl` | `OLLAMA_BASE_URL` | `http://localhost:11434` |
| Ollama context window (`num_ctx`) | `ollama.numCtx` | `OLLAMA_NUM_CTX` | `16384` |
| Reviewer bot's GitHub login | `github.botUsername` | `GITHUB_BOT_USERNAME` | none — must be set for learn mode's self-filter to work |

```bash
export OLLAMA_MODEL="qwen3:14b"
```

The reviewer works with any Ollama model that supports structured JSON output (which is a decoding-time constraint the Ollama engine enforces, not a per-model feature, so this covers effectively all current chat models pulled through `ollama pull`). Two things to know before switching:

- **Wording varies by model.** The same prompt against a different model can produce differently-worded `problem`/`suggestedFix` text even when the underlying defect it catches is identical. Don't assume byte-for-byte identical output across models — compare on whether the right defects are caught, not on exact phrasing.
- **Match the model size to your hardware.** A `14b` model (~9GB on disk, quantized) fits comfortably on 24GB of unified memory alongside everything else running. Larger models (`32b` and up) do not have a safe margin at that RAM size — don't switch to one without more memory to spare, or you'll see Ollama fail or the machine swap heavily under load.

---

## 4. How the reviewer works

### Architecture

`ai-reviewer` is one small, focused class per job, grouped into subpackages under `com.ai.reviewer`:

- **`com.ai.reviewer`** (top level) — `LocalCodeReviewer`, the entry point that wires everything else together and runs the review sequence described below; `ReviewFinding`, a plain data holder (category/status/file/line/problem/suggestedFix) with no logic of its own.
- **`com.ai.reviewer.diff`** — `DiffFetcher` (gets the diff to review, either local `git diff` or a GitHub PR's diff) and `DiffLineAnnotator` (tags added lines with `[Line N]` so the model can report an exact line number back).
- **`com.ai.reviewer.ollama`** — `OllamaReviewClient` (builds the review prompt and calls Ollama) and `FindingParser` (turns Ollama's JSON response into `ReviewFinding` objects, and back into readable terminal text).
- **`com.ai.reviewer.github`** — `GitHubContext` (the single place that reads GitHub PR identity out of environment variables), `GitHubCommentPoster` (posts inline PR comments), and its two helpers `CommitShaResolver` (which commit to anchor a comment to) and `ChangedFilePathResolver` (matching an AI-reported file path to one of the PR's actual changed files).
- **`com.ai.reviewer.learning`** — `LearningLoop` (the `@ai-learn` workflow — see [Section 5](#5-the-learning-loop)) and `RuleStore` (reads/writes `ai-reviewer/learned-rules.json`).

### Review flow

A PR is opened or updated on GitHub, which notifies Jenkins (via the ngrok-tunneled webhook or periodic polling). Jenkins checks out the PR branch and runs the `ai-reviewer` module. `DiffFetcher` fetches the PR's diff from the GitHub API and filters it down to blocks that actually contain added or removed lines; `DiffLineAnnotator` tags added lines with their destination line numbers.

`OllamaReviewClient` sends that diff to the local Ollama model (`qwen2.5-coder:14b` by default — see [Section 3a](#3a-local-config-configproperties)) via its native `/api/chat` endpoint at the configured base URL, along with a system prompt loaded at runtime from `ai-reviewer/src/main/resources/system-prompt.md`. That prompt defines six review categories — Playwright Web Assertions, Locator Robustness, Hardcoded Configurations, Logging, Naming Conventions, Code Style — plus any rules learned from prior `@ai-learn` comments, spliced in under a `LEARNED RULES:` heading right before the prompt's output-format instructions.

The request's `format` field carries a JSON schema that puts Ollama into structured-output mode, constraining its response to exactly `{"findings": [{category, status, file, line, problem, suggestedFix}, ...]}` — one entry per category assessed, `status` either `PASSED` or `FAILED`. This is why `FindingParser` can just read the JSON directly into `ReviewFinding` objects instead of regex-parsing free text as earlier versions of this tool did. (Known limitation: the model reliably emits `FAILED` entries but tends to skip `PASSED` ones for categories with nothing to report — the schema enforces the response's structure, not its completeness.)

Every `FAILED` finding is posted as an inline comment on the corresponding PR line by `GitHubCommentPoster` via the GitHub REST API, and the Jenkins build is marked failed — which blocks merge until every category passes.

The inline-comment posting step only runs when real PR context is present: specifically, `GITHUB_REPOSITORY`, a PR number (`GITHUB_PR_NUMBER` or `CHANGE_ID`), and `GITHUB_TOKEN` must all be set, exactly as Jenkins provides them for a real PR build (`GitHubContext.isPresent()`). Without that context — for example, running the reviewer directly on your machine against uncommitted local changes — findings are printed to the terminal only and GitHub posting is skipped. That's expected and is the normal way to use the reviewer for local testing.

---

## 5. The learning loop

The reviewer can turn human feedback into a standing rule it enforces on every future review.

1. A human replies to one of the reviewer's PR comments (or leaves a new one) prefixed with `@ai-learn`, e.g. `@ai-learn Never use System.out.println, even in test setup code — always use a logger.`
2. Someone runs learn mode (see [Section 2](#2-daily-quick-start)) against that PR. It fetches every comment on the PR via the GitHub API and keeps only the ones whose body starts with `@ai-learn`.
3. It also discards any matching comment authored by the bot account itself, identified by comparing the comment author's GitHub login against `GITHUB_BOT_USERNAME`. This filter exists because the reviewer must never learn from its own output — if it ingested its own prior review comments as though they were human feedback, it would just reinforce whatever mistakes produced that output in the first place.
4. For each remaining comment, `LearningLoop` calls the model with a separate, minimal prompt ("convert this into a single imperative rule, one sentence") to extract a single rule — using the same Ollama structured-output approach as the main review (a `{"rule": "<one imperative sentence>"}` JSON schema passed via `/api/chat`'s `format` field), so the extracted rule is read straight out of parsed JSON rather than trimmed from free text — and logs both the original comment and the extracted rule.
5. Each rule is saved to `ai-reviewer/learned-rules.json` (via `RuleStore`, pretty-printed, de-duplicated by the original comment text so re-running learn mode on the same PR doesn't create duplicates).

That file is a normal tracked file — it's committed to git like any other source file, not gitignored. Once it's committed and merged, every subsequent review run (including Jenkins') loads it and, if it contains any rules, appends them to the system prompt under a `LEARNED RULES:` heading before the model sees the diff — so the next review automatically enforces every rule learned so far.

---

## 6. Jenkins setup

This is the condensed set of steps to get a working pipeline. For troubleshooting, see [Section 7](#7-troubleshooting).

1. **Install Jenkins.** Docker is the simplest option:
   ```bash
   docker run --name jenkins -p 8080:8080 -p 50000:50000 -v jenkins_home:/var/jenkins_home -d jenkins/jenkins:lts
   ```
   (Homebrew — `brew install jenkins-lts && brew services start jenkins-lts` — and the standalone WAR are also supported; see `ARCHIVE_README.md` for the Windows/WAR variants.) Open `http://localhost:8080` and unlock it with the initial admin password.

2. **Install plugins**: `GitHub Branch Source`, `Pipeline`, `Credentials`, `GitHub`, and optionally `Allure`.

3. **Configure Global Tools** (Manage Jenkins → Tools) with names matching the `tools {}` blocks in the `Jenkinsfile*` files in this repo: a Maven installation named `Maven 3.9`, and a JDK installation named `JDK 21`.

4. **Add the GitHub token credential.** Manage Jenkins → Credentials → System → Global credentials → Add Credentials → kind `Secret text`, ID `github-pr-token` — this exact ID is what `Jenkinsfile.ai-reviewer` binds to `GITHUB_TOKEN` via `credentials('github-pr-token')`. See [Section 7](#7-troubleshooting) for the token scope this needs.

5. **Install and start ngrok**, then add a GitHub webhook (repo Settings → Webhooks → Add webhook) with Payload URL `https://<your-ngrok-url>/github-webhook/`, content type `application/json`, and only the **Pull requests** event checked.

6. **Create the Multibranch Pipeline job(s).** New Item → Multibranch Pipeline → Branch Sources → GitHub, using the `github-pr-token` credential and this repo's URL, with "Discover pull requests from origin" enabled. `Jenkinsfile.ai-reviewer` is what runs the AI gate per PR; its post-action triggers a separately-configured job named `playwright-regression-suite` (running `Jenkinsfile.playwright-regression`) for the full browser regression suite.

   > **TODO: verify** — the repo ships three separate Jenkinsfiles (`Jenkinsfile`, `Jenkinsfile.ai-reviewer`, `Jenkinsfile.playwright-regression`), but a Multibranch Pipeline job only runs one script path per job (default `Jenkinsfile`). None of the docs read for this rewrite state how many Jenkins jobs exist or what each one's configured "Script Path" is set to. Confirm the actual job names and Script Path settings in your Jenkins instance before relying on this step.

---

## 7. Troubleshooting

| Problem | Fix |
| --- | --- |
| ngrok's forwarding URL rotated and the GitHub webhook stopped delivering | ngrok's free-tier URL is ephemeral and changes on every restart. Copy the new `https://...` URL from the ngrok terminal output (or `http://localhost:4040`) and update the webhook's Payload URL in GitHub → Settings → Webhooks to match. |
| GitHub API calls return `401 Bad credentials`, or `createGitHubPullRequestComment` fails to post | The token needs to be a **classic** Personal Access Token with the **`repo`** scope. A fine-grained token, or a classic token with only narrower scopes, is not sufficient for posting inline PR review comments through the `pulls/{pr}/comments` endpoint. Regenerate a classic PAT with `repo` checked and update the `github-pr-token` credential. |
| GitHub returns `422 pull_request_review_thread.path could not be resolved` | This means the commit SHA/line the reviewer is posting against doesn't match the PR's actual diff as GitHub sees it. It typically happens when `GITHUB_REPOSITORY`/`GITHUB_PR_NUMBER`/`GITHUB_TOKEN` are exported locally to test the posting path, but `GIT_COMMIT` isn't set (so the code falls back to your local `git rev-parse HEAD`), and that local commit isn't the PR's real head commit known to GitHub. It's expected when testing locally without genuine PR context — without that context findings print to the terminal instead, which is the normal local flow. |
| AI reviewer reports it can't reach Ollama | Confirm `ollama serve` is running and `http://localhost:11434` responds (`curl -s -I http://localhost:11434`). |
| No AI comments show up on a PR that should have failures | Check the Jenkins console log for the GitHub API response code/body around comment posting, and confirm the finding's `File`/`Line` fields actually resolve to a path in the PR's changed files. |

---

## 8. Roadmap

- **Automatic learning** — currently `learn` mode must be run manually against a PR; a webhook that reacts to new `@ai-learn` comments and runs it automatically is not yet built.
- **Bitbucket support** — the reviewer is GitHub-only today (GitHub REST API, GitHub-specific environment variables); Bitbucket is not supported.
- **Deployment beyond a local machine** — Ollama, Jenkins, and ngrok all currently run on a developer's local machine; running this setup on shared/hosted infrastructure is not yet done.
