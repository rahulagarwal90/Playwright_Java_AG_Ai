package com.ai.healer.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.ai.healer.report.ScenarioGroup;
import com.ai.healer.report.TestFailure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HealerGitClientTest {

    // BUG 2 coverage: a second call must cut its branch from main, not from whatever branch a
    // prior call in the same run left HEAD on. Exercises createAndCommitBranch (the
    // checkout-main/checkout-b/add/commit portion of commitAndPushHealedGroup) directly against a
    // real scratch git repo - the push step is deliberately left out here since it needs network
    // access and real GitHub credentials; see buildAuthenticatedPushUrlEmbedsTheTokenAndRepository
    // below for BUG 1's coverage instead.
    @Test
    void secondCallCreatesItsBranchOffMainNotOffThePriorCallsBranch(@TempDir Path repoRoot) throws Exception {
        initRepoWithOneCommitOnMain(repoRoot);
        String mainSha = revParse(repoRoot, "main");

        HealerGitClient gitClient = new HealerGitClient(repoRoot);

        Path cartPageFile = repoRoot.resolve("CartPage.java");
        Files.writeString(cartPageFile, "private final String checkoutButton = \"#checkout\";\n", StandardCharsets.UTF_8);
        ScenarioGroup cartGroup = new ScenarioGroup(repoRoot.resolve("cart.feature"), List.<TestFailure>of());
        String firstBranch = gitClient.createAndCommitBranch(cartGroup, List.of(cartPageFile), "heal cart.feature");
        String firstBranchSha = revParse(repoRoot, firstBranch);

        // firstBranch genuinely diverged from main - proves the fixture is meaningful, not that
        // main and the branch just happen to already match.
        assertNotEquals(mainSha, firstBranchSha, "the first branch's commit should differ from main's");

        Path loginPageFile = repoRoot.resolve("LoginPage.java");
        Files.writeString(loginPageFile, "private final String loginButton = \"#login-button\";\n", StandardCharsets.UTF_8);
        ScenarioGroup loginGroup = new ScenarioGroup(repoRoot.resolve("login.feature"), List.<TestFailure>of());
        String secondBranch = gitClient.createAndCommitBranch(loginGroup, List.of(loginPageFile), "heal login.feature");

        String secondBranchParentSha = revParse(repoRoot, secondBranch + "^");

        assertEquals(mainSha, secondBranchParentSha,
                "the second branch must be cut from main's commit, not from the first branch's");
        assertNotEquals(firstBranchSha, secondBranchParentSha,
                "the second branch's parent must NOT be the first branch's commit");
    }

    // BUG 1 coverage: the push must use an explicit https://x-access-token:<token>@github.com/...
    // URL (reusing HealerGitHubConfig's existing token resolution, not a new config key) instead
    // of plain "origin" - asserted directly against the pure URL-building method rather than
    // running a real `git push`, so this needs no network access or real credentials.
    @Test
    void buildAuthenticatedPushUrlEmbedsTheTokenAndRepository() {
        String url = HealerGitClient.buildAuthenticatedPushUrl("some-org/some-repo", "ghs_faketoken123");

        assertEquals("https://x-access-token:ghs_faketoken123@github.com/some-org/some-repo.git", url);
    }

    // BUG 2 coverage: pushToUrl must never let the destination URL reach System.out/System.err,
    // even on a SUCCESSFUL push - git's own confirmation message echoes the destination verbatim
    // ("branch 'main' set up to track '<url>'."), which the old inheritIO()-based implementation
    // streamed straight to the console (a real token leak that already happened). The fake push
    // target's own directory name contains the literal "x-access-token:" marker (a colon is a
    // valid POSIX filename character, confirmed on this machine) so a real, local, successful
    // `git push` reproduces the exact "confirmation message echoes the destination" shape a real
    // GitHub push would, without any network access or a real token.
    @Test
    void pushToUrlNeverPrintsTheDestinationEvenOnASuccessfulPush(@TempDir Path root) throws Exception {
        Path workRepo = root.resolve("work");
        Files.createDirectory(workRepo);
        initRepoWithOneCommitOnMain(workRepo);

        Path fakeBareRepo = root.resolve("x-access-token:faketoken123-bare.git");
        run(root, "git", "init", "-q", "--bare", fakeBareRepo.toString());

        Path changedFile = workRepo.resolve("CartPage.java");
        Files.writeString(changedFile, "private final String checkoutButton = \"#checkout\";\n", StandardCharsets.UTF_8);
        run(workRepo, "git", "add", "CartPage.java");
        run(workRepo, "git", "commit", "-q", "-m", "heal cart.feature");

        HealerGitClient gitClient = new HealerGitClient(workRepo);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
            gitClient.pushToUrl(fakeBareRepo.toString(), "main");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String out = capturedOut.toString(StandardCharsets.UTF_8);
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains("x-access-token:"), "System.out must never contain the push URL/token: " + out);
        assertFalse(err.contains("x-access-token:"), "System.err must never contain the push URL/token: " + err);
    }

    private static void initRepoWithOneCommitOnMain(Path repoRoot) throws IOException, InterruptedException {
        run(repoRoot, "git", "init", "-q");
        run(repoRoot, "git", "config", "user.email", "test@example.com");
        run(repoRoot, "git", "config", "user.name", "Test");
        Files.writeString(repoRoot.resolve("README.md"), "scratch repo\n", StandardCharsets.UTF_8);
        run(repoRoot, "git", "add", "README.md");
        run(repoRoot, "git", "commit", "-q", "-m", "init");
        // Guarantees a branch literally named "main" exists, regardless of this git installation's
        // init.defaultBranch setting.
        run(repoRoot, "git", "branch", "-M", "main");
    }

    private static void run(Path repoRoot, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(repoRoot.toFile()).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("`" + String.join(" ", command) + "` failed with exit code " + exitCode);
        }
    }

    private static String revParse(Path repoRoot, String ref) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "rev-parse", ref)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("`git rev-parse " + ref + "` failed with exit code " + exitCode + ": " + output);
        }
        return output;
    }
}
