package com.framework.config;

import org.aeonbits.owner.Config;

@Config.LoadPolicy(Config.LoadType.MERGE)
@Config.Sources({
    "system:properties",
    "system:env",
    "file:${user.dir}/src/test/resources/config.properties"
})
public interface FrameworkConfig extends Config {

    @Key("base.url")
    String baseUrl();

    @Key("demoqa.text.box.path")
    String demoQaTextBoxPath();

    @Key("demoqa.webtables.path")
    String demoQaWebTablesPath();

    @Key("demoqa.select.menu.path")
    String demoQaSelectMenuPath();

    @Key("saucedemo.url")
    String saucedemoUrl();

    @Key("saucedemo.user")
    String saucedemoUser();

    @Key("saucedemo.pass")
    String saucedemoPass();

    @Key("browser.headless")
    @DefaultValue("false")
    boolean browserHeadless();

    @Key("browser.recordvideo")
    @DefaultValue("true")
    boolean browserRecordVideo();

    // Default Playwright action/navigation timeout (ms), applied to the whole BrowserContext in
    // PlaywrightFactory. Playwright's own built-in default is 30000ms.
    @Key("playwright.timeout")
    @DefaultValue("10000")
    int playwrightTimeout();
}
