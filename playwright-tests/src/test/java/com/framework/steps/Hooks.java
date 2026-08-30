package com.framework.steps;

import com.ai.healer.ScenarioNameSanitizer;
import com.framework.core.PlaywrightFactory;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import io.cucumber.java.After;
import io.cucumber.java.AfterStep;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.qameta.allure.Allure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Hooks {
    private static final Logger logger = LogManager.getLogger(Hooks.class);
    private static final String[] CLEANUP_PATHS = {
        "target/allure-results",
        "target/screenshots",
        "target/traces",
        "target/videos",
        "target/dom-snapshots"
    };
    private static boolean reportsCleared = false;
    private int stepCounter = 0;

    @Before
    public void setup(Scenario scenario) {
        // This hook runs once before the first scenario and clears stale artifacts
        // from previous Playwright / Allure executions.
        if (!reportsCleared) {
            clearPreviousRunArtifacts();
            reportsCleared = true;
        }

        stepCounter = 0;
        logger.info("==========================================================================");
        logger.info("Starting Scenario: {}", scenario.getName());
        logger.info("==========================================================================");
        
        PlaywrightFactory.initBrowser();
        
        // Start tracing for this scenario, including screenshots and DOM snapshots.
        PlaywrightFactory.getContext().tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
    }

    @AfterStep
    public void captureStepScreenshot(Scenario scenario) {
        try {
            if (PlaywrightFactory.getPage() == null) {
                return;
            }

            stepCounter++;
            String safeName = ScenarioNameSanitizer.sanitize(scenario.getName());
            Path screenshotDir = Paths.get("target/screenshots");
            if (Files.notExists(screenshotDir)) {
                Files.createDirectories(screenshotDir);
            }

            String screenshotFileName = String.format("%s-step-%02d.png", safeName, stepCounter);
            Path screenshotPath = screenshotDir.resolve(screenshotFileName);
            byte[] screenshot = PlaywrightFactory.getPage().screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));

            String attachmentName = safeName + "-step-" + stepCounter;
            Allure.getLifecycle().addAttachment(attachmentName, "image/png", "png", screenshot);
        } catch (Exception e) {
            logger.warn("Failed to capture step screenshot: {}", e.getMessage());
        }
    }

    @After
    public void tearDown(Scenario scenario) {
        String safeName = scenario.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        
        try {
            Path traceDir = Paths.get("target/traces");
            if (Files.notExists(traceDir)) {
                Files.createDirectories(traceDir);
            }

            // Save tracing output for later debugging.
            PlaywrightFactory.getContext().tracing().stop(new Tracing.StopOptions()
                    .setPath(traceDir.resolve(safeName + "-trace.zip")));
            logger.info("Trace saved for scenario.");
        } catch (Exception e) {
            logger.error("Failed to save trace: {}", e.getMessage(), e);
        }
        if (scenario.isFailed()) {
        logger.error("Scenario FAILED: {}", scenario.getName());
        captureDomSnapshot(safeName);
        }else {
            logger.info("Scenario PASSED: {}", scenario.getName());
        }
        
        PlaywrightFactory.quitBrowser();
        logger.info("==========================================================================");
    }

    private void captureDomSnapshot(String safeName) {
        try {
            Path dir = Paths.get("target/dom-snapshots");
            if (Files.notExists(dir)) Files.createDirectories(dir);
            String json = (String) PlaywrightFactory.getPage().evaluate(
                "() => JSON.stringify(Array.from(document.querySelectorAll(" +
                "'button,input,a,select,[role],[data-test],[data-testid],[onclick],[tabindex]'))" +
                ".slice(0,100).map(e => ({tag:e.tagName, id:e.id, testId:e.dataset.test||e.dataset.testid, " +
                "role:e.getAttribute('role'), aria:e.getAttribute('aria-label'), text:(e.innerText||'').slice(0,40)})))"
            );
            Files.writeString(dir.resolve(safeName + "-dom.json"), json);
        } catch (Exception e) {
            logger.warn("Failed to capture DOM snapshot: {}", e.getMessage());
        }
    }

    private void clearPreviousRunArtifacts() {
        logger.info("Clearing old Playwright and Allure artifacts before test run.");
        for (String path : CLEANUP_PATHS) {
            try {
                Path targetPath = Paths.get(path);
                if (Files.exists(targetPath)) {
                    Files.walk(targetPath)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception deleteError) {
                                    logger.debug("Unable to delete cleanup artifact path: {}", p, deleteError);
                                }
                            });
                }
            } catch (Exception e) {
                logger.warn("Failed to clear path {}: {}", path, e.getMessage());
            }
        }
    }
}
