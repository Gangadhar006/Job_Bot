package com.naukri.bot.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import lombok.Data;

/**
 * Holds a live Playwright browser context + page.
 * Passed around between services so they share the same session.
 */
@Data
public class BrowserSession {
    private final BrowserContext context;
    private final Page page;
    private boolean authenticated = false;

    public void close() {
        if (page != null && !page.isClosed())
            page.close();
        if (context != null)
            context.close();
    }
}
