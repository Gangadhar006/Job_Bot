package com.naukri.bot.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.naukri.bot.config.NaukriConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManager {
    private static final String NAUKRI_HOME = "https://www.naukri.com/";
    private static final String NAUKRI_LOGIN = "https://www.naukri.com/nlogin/login";
    private static final String LOGIN_SUCCESS_INDICATOR = "naukri.com/mnjuser/homepage";

    private final Browser browser;
    private final NaukriConfig config;
    private final ObjectMapper mapper;

    /**
     * Creates a new browser session.
     * If valid cookies exist → restores session (no login prompt).
     * Otherwise → performs full login and saves cookies.
     */
    public BrowserSession getOrCreateSession() throws Exception {
        BrowserContext context = createBrowserContext();
        Page page = context.newPage();

        // Inject stealth script on every page load
        applyStealthPatches(context);

        BrowserSession session = new BrowserSession(context, page);

        if (hasSavedCookies()) {
            log.info("Found saved cookies. Attempting to restore session...");
            boolean restored = restoreSession(session);
            if (restored) {
                log.info("✅ Session restored from cookies. No login needed.");
                return session;
            }
            log.warn("❌ Failed to restore session from cookies. Proceeding with login.");
        }
        performLogin(session);
        return session;
    }

    private BrowserContext createBrowserContext() {
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                .setViewportSize(1366, 768)
                .setUserAgent(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/122.0.0.0 Safari/537.36"
                )
                .setLocale("en-IN")
                .setTimezoneId("Asia/Kolkata")
                .setJavaScriptEnabled(true)
                .setIgnoreHTTPSErrors(true);

        return browser.newContext(contextOptions);
    }

    private void applyStealthPatches(BrowserContext context) {
        // Remove webdriver flag — the single most important stealth patch
        context.addInitScript(
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
        );
        // Spoof plugins array (empty in headless, populated in real browser)
        context.addInitScript(
                "Object.defineProperty(navigator, 'plugins', {" +
                        "  get: () => [1, 2, 3, 4, 5]" +
                        "});"
        );
        // Spoof languages
        context.addInitScript(
                "Object.defineProperty(navigator, 'languages', {" +
                        "  get: () => ['en-IN', 'en-US', 'en']" +
                        "});"
        );
    }

    private boolean hasSavedCookies() {
        return new File(config.getSession().getCookieFile()).exists();
    }

    private void saveCookies(BrowserContext context) throws IOException {
        List<Cookie> cookies = context.cookies();
        Path cookiePath = Paths.get(config.getSession().getCookieFile());
        Files.createDirectories(cookiePath.getParent());
        mapper.writeValue(cookiePath.toFile(), cookies);
        log.info("💾 Saved {} cookies to {}", cookies.size(), cookiePath);
    }

    private void loadCookies(BrowserContext context) throws Exception {
        List<Cookie> cookies = mapper.readValue(
                new File(config.getSession().getCookieFile()),
                new TypeReference<List<Cookie>>() {
                }
        );
        context.addCookies(cookies);
        log.info("🍪 Loaded {} cookies into context", cookies.size());
    }

    private boolean restoreSession(BrowserSession session) throws Exception {
        try {
            loadCookies(session.getContext());

            Page page = session.getPage();
            page.navigate(NAUKRI_HOME);
            page.waitForLoadState(LoadState.NETWORKIDLE);

            randomDelay(1000, 2000);

            // Check if we're actually logged in
            boolean loggedIn = isLoggedIn(page);
            if (loggedIn) {
                session.setAuthenticated(true);
            }
            return loggedIn;

        } catch (Exception e) {
            log.error("Session restore failed: {}", e.getMessage());
            return false;
        }
    }

    private void performLogin(BrowserSession session) throws Exception {
        Page page = session.getPage();
        String email = config.getCredentials().getEmail();
        String password = config.getCredentials().getPassword();

        log.info("🔐 Performing full login for {}...", email);

        page.navigate(NAUKRI_LOGIN);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        randomDelay(1500, 2500);

        // Fill email
        waitAndFill(page, "input[placeholder*='Email ID']", email);
        randomDelay(500, 1000);

        // Fill password
        waitAndFill(page, "input[type='password']", password);

        randomDelay(800, 1500);

        // Click login button
        page.click("button[type='submit']");

        // Wait for navigation to complete
        page.waitForURL("**/mnjuser/homepage**", new Page.WaitForURLOptions().setTimeout(15_000));

                page.waitForLoadState(LoadState.NETWORKIDLE);
        randomDelay(2000, 3000);

        // Verify login success
        if (!isLoggedIn(page)) {
            // Check for captcha
            if (isCaptchaPresent(page)) {
                log.error("🤖 CAPTCHA detected! Manual intervention required.");
                log.info("Please solve the CAPTCHA in the browser window, then press ENTER...");
                System.in.read(); // Pause and wait for manual solve
                page.waitForLoadState(LoadState.NETWORKIDLE);
            } else {
                throw new RuntimeException(
                        "Login failed. Check credentials in application.yml. " +
                                "Current URL: " + page.url()
                );
            }
        }

        log.info("✅ Login successful! Current URL: {}", page.url());
        session.setAuthenticated(true);

        // Save cookies for future runs
        saveCookies(session.getContext());
    }

    private boolean isLoggedIn(Page page) {
        try {
            // Naukri shows user icon / name when logged in
            // Check URL isn't login page AND a user-specific element is present
            String url = page.url();
            boolean notOnLoginPage = !url.contains("/nlogin/");

            // Look for the profile/avatar element that appears when logged in
            boolean hasUserElement = page.locator(".nI-gNb-drawer__icon").count() > 0
                    || page.locator("[class*='user-name']").count() > 0
                    || page.locator(".view-profile-wrapper").count() > 0;

            log.debug("Login check — URL ok: {}, userElement: {}", notOnLoginPage, hasUserElement);
            return notOnLoginPage && hasUserElement;

        } catch (Exception e) {
            log.warn("Login check threw exception: {}", e.getMessage());
            return false;
        }
    }

    private boolean isCaptchaPresent(Page page) {
        return page.locator("iframe[src*='recaptcha']").count() > 0
                || page.locator(".g-recaptcha").count() > 0
                || page.locator("[class*='captcha']").count() > 0;
    }

    private void waitAndFill(Page page, String selector, String value) {
        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(10_000));
        page.click(selector);
        randomDelay(200, 500);
        // Type character by character with random delays — mimics human typing
        for (char c : value.toCharArray()) {
            page.keyboard().type(String.valueOf(c));
            try {
                Thread.sleep((long) (Math.random() * 80 + 40));
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void randomDelay(int minMs, int maxMs) {
        try {
            Thread.sleep((long) (Math.random() * (maxMs - minMs) + minMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
