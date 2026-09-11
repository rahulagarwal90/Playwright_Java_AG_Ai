package com.ai.healer.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FeatureFileResolverTest {

    @Test
    void resolvesAClassNameToItsFeatureFileByReadingTheFeatureLine(@TempDir Path featuresRoot) throws Exception {
        Path loginFeature = featuresRoot.resolve("saucedemo/login/login.feature");
        Files.createDirectories(loginFeature.getParent());
        Files.writeString(loginFeature,
                "@UI @SauceDemo\nFeature: User Login Flow\n\n  Scenario: Standard User can login successfully\n",
                StandardCharsets.UTF_8);

        FeatureFileResolver resolver = new FeatureFileResolver(featuresRoot);
        FeatureFileResolver.Resolution resolution = resolver.resolve("User Login Flow");

        assertTrue(resolution.resolved());
        assertEquals(loginFeature, resolution.filePath());
        assertEquals(1, resolution.featureFilesScanned());
    }

    @Test
    void reportsAnUnresolvedClassNameWithDiagnosticsInsteadOfThrowing(@TempDir Path featuresRoot) throws Exception {
        Path cartFeature = featuresRoot.resolve("cart.feature");
        Files.writeString(cartFeature, "Feature: Shopping Cart\n", StandardCharsets.UTF_8);

        FeatureFileResolver resolver = new FeatureFileResolver(featuresRoot);
        FeatureFileResolver.Resolution resolution = resolver.resolve("Some Nonexistent Feature");

        assertFalse(resolution.resolved());
        assertNull(resolution.filePath());
        assertEquals("Some Nonexistent Feature", resolution.className());
        assertEquals(1, resolution.featureFilesScanned());
        assertEquals(1, resolution.allFeatureFilePaths().size());
        assertEquals(cartFeature, resolution.allFeatureFilePaths().get(0));
    }

    @Test
    void treatsAMissingFeaturesDirectoryAsZeroFilesScannedRatherThanThrowing(@TempDir Path tempDir) {
        FeatureFileResolver resolver = new FeatureFileResolver(tempDir.resolve("does-not-exist"));

        FeatureFileResolver.Resolution resolution = resolver.resolve("Anything");

        assertFalse(resolution.resolved());
        assertEquals(0, resolution.featureFilesScanned());
        assertTrue(resolution.allFeatureFilePaths().isEmpty());
    }

    @Test
    void aNullClassNameIsAlwaysUnresolved(@TempDir Path featuresRoot) throws Exception {
        Files.writeString(featuresRoot.resolve("cart.feature"), "Feature: Shopping Cart\n", StandardCharsets.UTF_8);

        FeatureFileResolver resolver = new FeatureFileResolver(featuresRoot);
        FeatureFileResolver.Resolution resolution = resolver.resolve(null);

        assertFalse(resolution.resolved());
    }
}
