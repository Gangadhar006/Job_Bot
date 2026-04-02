package com.naukri.bot.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.naukri.bot.config.properties.NaukriProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PlaywrightConfig {
    private final NaukriProperties config;

    @Bean(destroyMethod = "close")
    public Playwright playwright() {
        log.info("Initializing Playwright with headless: {}, slowMo: {}", config.getBrowser().isHeadless(), config.getBrowser().getSlowMo());
        return Playwright.create();
    }

    @Bean
    public Browser browser(Playwright playwright) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(config.getBrowser().isHeadless())
                .setSlowMo(config.getBrowser().getSlowMo())
                .setArgs(Arrays.asList(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-infobars",
                        "--disable-dev-shm-usage",
                        "--no-sandbox",
                        "--start-maximized",
                        "--my-bot-id=naukri-bot"
//                        "--disable-setuid-sandbox"

//                        "--disable-http2",
//                        "--disable-features=IsolateOrigins,site-per-process",
//                        "--disable-background-networking",
//                        "--disable-background-timer-throttling",
//                        "--disable-renderer-backgrounding",
//                        "--disable-backgrounding-occluded-windows"
                ));
        return playwright.chromium().launch(options);
    }
}
