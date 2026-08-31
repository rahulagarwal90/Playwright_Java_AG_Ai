package com.framework.core;

import com.framework.config.FrameworkConfigManager;
import com.framework.config.FrameworkConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;

public class PlaywrightFactory {
    private static final Logger logger = LogManager.getLogger(PlaywrightFactory.class);
    private static final FrameworkConfig config = FrameworkConfigManager.getConfig();

    private static ThreadLocal<Playwright> tlPlaywright = new ThreadLocal<>();
    private static ThreadLocal<Browser> tlBrowser = new ThreadLocal<>();
    private static ThreadLocal<BrowserContext> tlContext = new ThreadLocal<>();
    private static ThreadLocal<Page> tlPage = new ThreadLocal<>();

    public static void initBrowser() {
        logger.info("Initializing Playwright and Browser...");
        tlPlaywright.set(Playwright.create());
        
        boolean headless = config.browserHeadless();
        logger.info("Browser Headless Mode: {}", headless);
        
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless);
        tlBrowser.set(tlPlaywright.get().chromium().launch(options));
        
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();
        if (config.browserRecordVideo()) {
            contextOptions.setRecordVideoDir(Paths.get("target/videos/"));
            logger.info("Playwright video recording enabled: target/videos/");
        } else {
            logger.info("Playwright video recording disabled via configuration.");
        }
        tlContext.set(tlBrowser.get().newContext(contextOptions));

        int timeoutMs = config.playwrightTimeout();
        tlContext.get().setDefaultTimeout(timeoutMs);
        logger.info("Playwright default action/navigation timeout: {}ms", timeoutMs);

        tlPage.set(tlContext.get().newPage());
        logger.info("Browser/Page session initialized.");
    }

    public static Page getPage() {
        return tlPage.get();
    }
    
    public static BrowserContext getContext() {
        return tlContext.get();
    }

    public static void quitBrowser() {
        logger.info("Closing Browser session...");
        if (tlPage.get() != null) tlPage.get().close();
        if (tlContext.get() != null) tlContext.get().close();
        if (tlBrowser.get() != null) tlBrowser.get().close();
        if (tlPlaywright.get() != null) tlPlaywright.get().close();
        
        tlPage.remove();
        tlContext.remove();
        tlBrowser.remove();
        tlPlaywright.remove();
        logger.info("Browser session closed and threads cleaned.");
    }
}
