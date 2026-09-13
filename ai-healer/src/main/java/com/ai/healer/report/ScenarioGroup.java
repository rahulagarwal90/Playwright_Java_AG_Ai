package com.ai.healer.report;

import java.nio.file.Path;
import java.util.List;

/**
 * One {@code .feature} file's path plus every {@link TestFailure} whose Surefire classname
 * ({@link FeatureFileResolver}) resolved to it. The unit HealOrchestrator processes: each
 * scenario inside still gets its own independent per-scenario retry budget, but grouping by
 * feature file lets a single git branch/PR carry every fix made to that one file.
 */
public class ScenarioGroup {

    private final Path featureFilePath;
    private final List<TestFailure> failures;

    public ScenarioGroup(Path featureFilePath, List<TestFailure> failures) {
        this.featureFilePath = featureFilePath;
        this.failures = failures;
    }

    public Path featureFilePath() {
        return featureFilePath;
    }

    public List<TestFailure> failures() {
        return failures;
    }

    // A flat, branch-safe name derived from the feature file's own name only (not its folder
    // path) - e.g. "cart.feature" -> "cart-feature". Same narrow transformation idea as
    // ScenarioNameSanitizer (replace every disallowed character), implemented separately here
    // since a git branch segment needs lowercase + hyphens, not ScenarioNameSanitizer's
    // underscore/filename rules.
    public String flatBranchName() {
        return featureFilePath.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9]", "-");
    }
}
