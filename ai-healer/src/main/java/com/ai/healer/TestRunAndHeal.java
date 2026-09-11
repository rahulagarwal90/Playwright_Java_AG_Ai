package com.ai.healer;

import com.ai.healer.exec.MavenRunner;
import com.ai.healer.report.SurefireReportReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * The "one-click" entry point: runs the full playwright-tests suite, and only if it fails,
 * follows immediately with {@link HealOrchestrator} against the report that run just produced -
 * no separate manual command needed. Runnable directly via
 * {@code mvn -pl ai-healer exec:java -Dexec.mainClass=com.ai.healer.TestRunAndHeal}.
 *
 * Mirrors {@code run-tests-and-heal.sh}'s exact behavior: same "nothing to heal" exit-early
 * shortcut when the suite passes, same {@code healer.minReportTimestamp} staleness guard on the
 * {@code HealOrchestrator} run that follows a failure. The difference is that this calls
 * {@code HealOrchestrator.main(...)} directly as a Java method in the same JVM (both live in this
 * module) rather than the shell script's two separate {@code mvn} invocations - one less process
 * to start, and the timestamp can be set as a real system property instead of a {@code -D} flag
 * on a subprocess command line. The shell script is kept as a documented fallback; see its own
 * header comment.
 */
public class TestRunAndHeal {

    private static final Logger LOGGER = Logger.getLogger(TestRunAndHeal.class.getName());

    public static void main(String[] args) throws IOException, InterruptedException {
        long testStartMs = System.currentTimeMillis();
        Path repoRoot = RepoRoot.resolve(TestRunAndHeal.class);

        LOGGER.info("=== Running playwright-tests ===");
        int exitCode = MavenRunner.run(repoRoot, List.of("-pl", "playwright-tests", "test"));

        if (exitCode == 0) {
            LOGGER.info("=== All tests passed - nothing to heal ===");
            return;
        }

        LOGGER.info("=== Test run failed - invoking HealOrchestrator on the report just produced ===");
        System.setProperty(SurefireReportReader.MIN_REPORT_TIMESTAMP_PROPERTY, String.valueOf(testStartMs));
        HealOrchestrator.main(args);
    }
}
