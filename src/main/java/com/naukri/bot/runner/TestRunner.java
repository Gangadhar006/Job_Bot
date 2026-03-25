package com.naukri.bot.runner;

import com.naukri.bot.browser.BrowserSession;
import com.naukri.bot.browser.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestRunner implements CommandLineRunner {
    private final SessionManager sessionManager;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Naukri Bot — Day 1 Test ===");

        BrowserSession session = null;
        try {
            session = sessionManager.getOrCreateSession();

            log.info("Session authenticated: {}", session.isAuthenticated());
            log.info("Current page URL: {}", session.getPage().url());
            log.info("Page title: {}", session.getPage().title());

            // Keep browser open for 5 seconds so you can visually verify
            Thread.sleep(5000);

            log.info("✅ Day 1 complete — login and session management working!");

        } finally {
            if (session != null) {
                session.close();
                log.info("Browser session closed.");
            }
        }
    }
}
