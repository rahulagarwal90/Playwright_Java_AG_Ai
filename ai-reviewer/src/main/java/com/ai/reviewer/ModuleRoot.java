package com.ai.reviewer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This class only knows how to find the ai-reviewer module's root directory — the folder
 * containing this module's own pom.xml — regardless of the JVM's working directory (which
 * varies across `mvn exec:java` run from the module root, from the repository root, or from
 * whatever directory a Jenkins pipeline step happens to be in). RuleStore and OllamaConfig both
 * ask this one place to find where their own file (learned-rules.json / config.properties)
 * should live, instead of each walking the directory tree themselves.
 */
public class ModuleRoot {

    // Locates the module root by walking up from wherever the given class was loaded from
    // (target/classes during a normal Maven build, or the jar file itself if packaged) until a
    // directory containing this module's own pom.xml is found.
    public static Path resolve(Class<?> anchorClass) {
        try {
            Path codeSource = Path.of(
                    anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            while (candidate != null) {
                if (Files.exists(candidate.resolve("pom.xml"))) {
                    return candidate;
                }
                candidate = candidate.getParent();
            }
        } catch (Exception e) {
            // Fall through to the working-directory fallback below.
        }
        // Last-resort fallback for environments where the code source can't be resolved (e.g.
        // an unusual classloader setup): assume the JVM's working directory is already correct.
        return Path.of("").toAbsolutePath();
    }
}
