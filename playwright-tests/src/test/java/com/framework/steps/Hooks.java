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
        // This hook runs once before the first scenario and clears stale artifacts from previous
        // Playwright / Allure executions. HealOrchestrator sets -Dhealer.skipArtifactCleanup=true
        // on its own re-run subprocesses to skip this: that subprocess is a fresh JVM (so
        // reportsCleared starts false again there too), and clearing target/dom-snapshots/ would
        // destroy other not-yet-processed failures' DOM snapshots mid-orchestration.
        if (!reportsCleared) {
            if (Boolean.getBoolean("healer.skipArtifactCleanup")) {
                logger.info("Skipping artifact cleanup (healer.skipArtifactCleanup=true) - preserving prior findings for HealOrchestrator.");
            } else {
                clearPreviousRunArtifacts();
            }
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

    // Query selector for which elements get included in the snapshot. Broadened from the original
    // button,input,a,select,[role],[data-test],[data-testid],[onclick],[tabindex] after a real
    // scan of every locator this codebase's page objects actually use (see CLAUDE.md's "capture
    // broadening" note): real locators target id and CSS class in addition to data-test, but never
    // target a "name" attribute, so [name] was deliberately NOT added - confirmed against the real
    // scan rather than assumed.
    private static final String DOM_SNAPSHOT_SELECTOR =
            "button,input,a,select,[id],[class],[role],[data-test],[data-testid],[onclick],[tabindex]";

    private void captureDomSnapshot(String safeName) {
        try {
            Path dir = Paths.get("target/dom-snapshots");
            if (Files.notExists(dir)) Files.createDirectories(dir);

            // No slice/cap here on purpose: at capture time Hooks has no idea which locator
            // actually broke (Cucumber's Scenario doesn't expose the failing exception), so it
            // has nothing to rank candidates by - truncating in raw DOM order here risks silently
            // dropping the one element the healer actually needs (confirmed for real: a
            // class-heavy DemoQA page produced 211 matches, and the correct element sat at index
            // 199 - past a 100-cap applied at this point, it would never reach LocatorHealer at
            // all). LocatorHealer knows the broken locator string once it loads this file, so
            // ranking-then-capping to 100 happens there instead - see
            // LocatorHealer.rankBySimilarityAndCap(). DOM snapshots are small, failure-only,
            // on-disk diagnostic artifacts, so writing every match here is cheap.
            String json = (String) PlaywrightFactory.getPage().evaluate(
                "(sel) => JSON.stringify(Array.from(document.querySelectorAll(sel))" +
                ".map(e => ({tag:e.tagName, id:e.id, dataTest:e.dataset.test||null, " +
                "dataTestId:e.dataset.testid||null, className:e.getAttribute('class')||null, " +
                "role:e.getAttribute('role'), aria:e.getAttribute('aria-label'), text:(e.innerText||'').slice(0,40)})))",
                DOM_SNAPSHOT_SELECTOR
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
