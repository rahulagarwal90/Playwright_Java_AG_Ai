package com.ai.healer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root — the directory containing both sibling modules, playwright-tests
 * and ai-reviewer — by walking up from wherever the given class was loaded from, so this works
 * regardless of the JVM's working directory. Shared by SurefireReportReader (to find
 * playwright-tests/target/...) and LocatorHealer (to resolve a page object's absolute source
 * path) instead of each duplicating the walk-up logic. Mirrors ai-reviewer's ModuleRoot, kept
 * self-contained here since ai-healer has no dependency on the ai-reviewer module.
 */
public final class RepoRoot {

    private RepoRoot() {
    }

    public static Path resolve(Class<?> anchorClass) {
        try {
            Path codeSource = Path.of(anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            while (candidate != null) {
                if (Files.isDirectory(candidate.resolve("playwright-tests"))
                        && Files.isDirectory(candidate.resolve("ai-reviewer"))) {
                    return candidate;
                }
                candidate = candidate.getParent();
            }
        } catch (Exception e) {
            // Fall through to the working-directory fallback below.
        }
        return Path.of("").toAbsolutePath();
    }
}
