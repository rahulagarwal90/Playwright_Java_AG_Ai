package com.ai.healer.github;

import com.ai.healer.RepoRoot;
import com.ai.healer.report.ScenarioGroup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Plain {@code ProcessBuilder} git commands (mirrors {@code MavenRunner}'s subprocess style - no
 * git library) for the one thing HealOrchestrator needs in GitHub-pipeline context: get a healed
 * {@link ScenarioGroup}'s changed files onto a brand-new branch, pushed authenticated as the
 * AiHealer bot. Never runs {@code git merge}, a force-push, or anything else that would touch
 * {@code main} directly - creates and pushes a new branch, full stop. Every new branch is cut
 * from {@code main} explicitly (never from whatever HEAD happens to be) so that processing more
 * than one {@link ScenarioGroup} in a single run never bases one group's branch off a PREVIOUS
 * group's just-created branch.
 */
public class HealerGitClient {

    private static final Logger LOGGER = Logger.getLogger(HealerGitClient.class.getName());
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String RUN_NUMBER_ENV_VAR = "BUILD_NUMBER";

    private final Path repoRoot;

    public HealerGitClient() {
        this(RepoRoot.resolve(HealerGitClient.class));
    }

    // Package-visible-via-public-Path overload so tests can point this at a scratch git repo
    // instead of the real one.
    public HealerGitClient(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public record BranchResult(String branchName) {
    }

    // Creates "heal/<flat-branch-name>-run<BUILD_NUMBER>-<timestamp>" (or, with no BUILD_NUMBER -
    // a local/manual run - the timestamp-only "heal/<flat-branch-name>-<timestamp>") off main,
    // stages only the given changed files, commits with the given message, and pushes it
    // authenticated as the AiHealer bot.
    public BranchResult commitAndPushHealedGroup(ScenarioGroup group, List<Path> changedFiles, String commitMessage)
            throws IOException, InterruptedException {
        String branchName = createAndCommitBranch(group, changedFiles, commitMessage);
        push(branchName);
        return new BranchResult(branchName);
    }

    // Everything except the push - checkout main explicitly (never trust whatever HEAD already
    // is; a prior call in the same run may have left HEAD on a DIFFERENT group's just-created
    // branch), cut the new branch from it, stage the given files, and commit. Package-private so
    // it can be exercised directly against a real scratch git repo without a network-dependent
    // push - see HealerGitClientTest.
    //
    // A plain "checkout main" resolves the LOCAL "main" ref, which git fetch never updates on its
    // own and which can go stale across many builds in a reused Jenkins workspace - confirmed for
    // real via GitHub: several recent heal PRs' branches all shared the same days-old parent
    // commit despite being created back-to-back, because every one of them forked from a frozen
    // local "main" snapshot regardless of how much had since landed on origin/main. When an
    // "origin" remote exists, fetch it and reset the branching point to origin/main's real tip
    // instead. Standalone/local/unit-test scratch repos (this class's own tests) have no "origin"
    // remote, so they fall back to the original plain "checkout main" behavior unchanged.
    String createAndCommitBranch(ScenarioGroup group, List<Path> changedFiles, String commitMessage)
            throws IOException, InterruptedException {
        if (hasOriginRemote()) {
            runGit("fetch", "origin", "main");
            runGit("checkout", "-B", "main", "origin/main");
        } else {
            runGit("checkout", "main");
        }

        String branchName = buildBranchName(group.flatBranchName());
        runGit("checkout", "-b", branchName);

        List<String> addCommand = new ArrayList<>(List.of("add", "--"));
        for (Path file : changedFiles) {
            addCommand.add(file.toString());
        }
        runGit(addCommand.toArray(String[]::new));

        runGit("commit", "-m", commitMessage);
        return branchName;
    }

    // Pushes authenticated as the AiHealer bot via an explicit https://x-access-token:<token>@...
    // URL (see buildAuthenticatedPushUrl) rather than plain "origin", reusing the same GitHub
    // token HealerGitHubConfig already resolves elsewhere - no new config key. The URL is built
    // only here, held in a local variable, never returned or logged.
    private void push(String branchName) throws IOException, InterruptedException {
        String pushUrl = buildAuthenticatedPushUrl(HealerGitHubConfig.repository(), HealerGitHubConfig.token());
        pushToUrl(pushUrl, branchName);
    }

    // Package-private so a test can push to a fake local target instead of a real GitHub URL -
    // see HealerGitClientTest. This is the one git command ever given an argument containing the
    // token (buildAuthenticatedPushUrl), so unlike every other command in this class it must NOT
    // use inheritIO(): git prints a confirmation line on a SUCCESSFUL push that echoes the
    // destination verbatim (e.g. "branch '<name>' set up to track '<url-with-token>'."), which
    // inheritIO() would stream straight to the console - a real token leak that already happened
    // once, since redact()/runGit's failure-message redaction never runs on that success path at
    // all. Captures stdout+stderr into a single buffer instead and never prints it, under any
    // circumstances - only a redacted version of it ever surfaces, and only on failure.
    void pushToUrl(String pushUrl, String branchName) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "push", "-u", pushUrl, branchName)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git push failed: " + redactText(output));
        }
        LOGGER.info("Pushed branch " + branchName + ".");
    }

    // Package-private and pure (no I/O) specifically so the URL format can be asserted on
    // directly in a test without ever constructing a real token or attempting a network push.
    static String buildAuthenticatedPushUrl(String repository, String token) {
        return "https://x-access-token:" + token + "@github.com/" + repository + ".git";
    }

    // Same redaction intent as redact(String[]) below, but over arbitrary captured git output
    // text rather than a fixed argument array - used only by pushToUrl's failure path, so a real
    // auth/network error can still be surfaced with useful detail without ever including the
    // credential-bearing URL verbatim.
    private static String redactText(String text) {
        return text.replaceAll("\\S*x-access-token:\\S*", "<redacted>");
    }

    private static String buildBranchName(String flatBranchName) {
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        String runNumber = System.getenv(RUN_NUMBER_ENV_VAR);
        return (runNumber != null && !runNumber.isBlank())
                ? "heal/" + flatBranchName + "-run" + runNumber + "-" + timestamp
                : "heal/" + flatBranchName + "-" + timestamp;
    }

    // Package-private so it can be asserted indirectly via createAndCommitBranch's behavior in
    // tests. Uses "git remote" (not e.g. relying on a caught exception from a failed fetch) so a
    // repo with no "origin" at all takes the plain-checkout fallback path deliberately, not as a
    // side effect of a failed command.
    boolean hasOriginRemote() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "remote")
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            return false;
        }
        return output.lines().anyMatch(line -> line.trim().equals("origin"));
    }

    private void runGit(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(repoRoot.toFile())
                .inheritIO()
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("`git " + redact(args) + "` failed with exit code " + exitCode);
        }
    }

    // A credential-bearing push URL (see buildAuthenticatedPushUrl) must never end up in an
    // exception message - HealOrchestrator's [GIT_PR_ERROR] logging would otherwise print the
    // token straight into the console/log on a failed push.
    private static String redact(String[] args) {
        List<String> redacted = new ArrayList<>();
        for (String arg : args) {
            redacted.add(arg.contains("x-access-token:") ? "<redacted>" : arg);
        }
        return String.join(" ", redacted);
    }
}
