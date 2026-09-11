package com.ai.healer.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared subprocess-invocation logic for every {@code mvn ...} call ai-healer spawns as a child
 * process: builds the {@code ProcessBuilder} rooted at the given repo root and inherits this
 * process's stdio so the child's output streams through live, same as running it by hand.
 * Factored out of {@link HealOrchestrator} so {@link TestRunAndHeal}'s full-suite run doesn't
 * duplicate that plumbing.
 */
public final class MavenRunner {

    private MavenRunner() {
    }

    // Runs to completion with no watchdog - for a manual, attended invocation (TestRunAndHeal's
    // full-suite run) where a human is watching the live output via inheritIO() and can interrupt
    // it themselves if something hangs, the same as running `mvn ...` directly in a terminal.
    public static int run(Path repoRoot, List<String> mavenArgs) throws IOException, InterruptedException {
        return start(repoRoot, mavenArgs).waitFor();
    }

    // Runs with a watchdog timeout - for an unattended re-run inside HealOrchestrator's retry
    // loop, where nothing but this code is watching the process, so a genuinely hung subprocess
    // (e.g. a browser that never launches) has to be killed rather than blocking forever.
    public static int run(Path repoRoot, List<String> mavenArgs, long waitTimeoutMs) throws IOException, InterruptedException {
        Process process = start(repoRoot, mavenArgs);
        boolean finished = process.waitFor(waitTimeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("`mvn " + String.join(" ", mavenArgs) + "` did not finish within "
                    + waitTimeoutMs + "ms - killed.");
        }
        return process.exitValue();
    }

    private static Process start(Path repoRoot, List<String> mavenArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.addAll(mavenArgs);
        return new ProcessBuilder(command)
                .directory(repoRoot.toFile())
                .inheritIO()
                .start();
    }
}
