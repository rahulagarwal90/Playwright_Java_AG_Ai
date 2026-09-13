package com.ai.healer.report;

import com.ai.healer.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Matches a Surefire testcase's {@code classname} attribute — for a Cucumber scenario this is the
 * enclosing feature's "Feature:" line text, not a real Java class — back to the {@code .feature}
 * file it came from. Scans every {@code .feature} file once under
 * {@code playwright-tests/src/test/resources/features} and reads each one's "Feature:" line to
 * build the lookup. Read-only: no classification or healing logic, and never throws when a
 * className can't be matched — see {@link Resolution}.
 */
public class FeatureFileResolver {

    private static final Path FEATURES_RELATIVE_PATH =
            Path.of("playwright-tests", "src", "test", "resources", "features");

    /**
     * The outcome of resolving one className. When {@code resolved()} is false, {@code filePath()}
     * is null and the remaining fields carry enough diagnostic detail — how many {@code .feature}
     * files were scanned, and every one of their paths — for a caller to log or report the miss
     * without re-scanning anything itself. The caller (HealOrchestrator) decides what to do with
     * an unresolved result; this class never throws to stop the run.
     */
    public record Resolution(boolean resolved, Path filePath, String className,
            int featureFilesScanned, List<Path> allFeatureFilePaths) {

        static Resolution resolved(Path filePath, String className, int scanned, List<Path> all) {
            return new Resolution(true, filePath, className, scanned, all);
        }

        static Resolution unresolved(String className, int scanned, List<Path> all) {
            return new Resolution(false, null, className, scanned, all);
        }
    }

    private final Map<String, Path> featureNameToPath;
    private final List<Path> allFeatureFilePaths;

    public FeatureFileResolver() {
        this(RepoRoot.resolve(FeatureFileResolver.class).resolve(FEATURES_RELATIVE_PATH));
    }

    // Package-visible-via-public-Path overload so tests can point this at a temp directory
    // instead of the real repo's feature files.
    public FeatureFileResolver(Path featuresRoot) {
        this.allFeatureFilePaths = scanFeatureFiles(featuresRoot);
        this.featureNameToPath = mapByFeatureName(this.allFeatureFilePaths);
    }

    public Resolution resolve(String className) {
        Path filePath = className == null ? null : featureNameToPath.get(className);
        return filePath != null
                ? Resolution.resolved(filePath, className, allFeatureFilePaths.size(), allFeatureFilePaths)
                : Resolution.unresolved(className, allFeatureFilePaths.size(), allFeatureFilePaths);
    }

    private static List<Path> scanFeatureFiles(Path featuresRoot) {
        if (!Files.isDirectory(featuresRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(featuresRoot)) {
            List<Path> found = new ArrayList<>();
            stream.filter(p -> p.toString().endsWith(".feature")).forEach(found::add);
            return found;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Map<String, Path> mapByFeatureName(List<Path> featureFiles) {
        Map<String, Path> map = new LinkedHashMap<>();
        for (Path path : featureFiles) {
            String featureName = readFeatureName(path);
            if (featureName != null) {
                map.put(featureName, path);
            }
        }
        return map;
    }

    private static String readFeatureName(Path featureFile) {
        try {
            for (String line : Files.readAllLines(featureFile, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("Feature:")) {
                    return trimmed.substring("Feature:".length()).strip();
                }
            }
        } catch (IOException e) {
            // Unreadable file - simply not matchable by name; resolve() reports any className
            // that would have matched it as unresolved instead.
        }
        return null;
    }
}
