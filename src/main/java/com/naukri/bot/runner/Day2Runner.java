package com.naukri.bot.runner;

import com.naukri.bot.browser.BrowserSession;
import com.naukri.bot.browser.JobScraper;
import com.naukri.bot.browser.SessionManager;
import com.naukri.bot.model.Job;
import com.naukri.bot.service.JobStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("day2")
@RequiredArgsConstructor
public class Day2Runner implements CommandLineRunner {

    private final SessionManager sessionManager;
    private final JobScraper jobScraper;
    private final JobStorageService jobStorageService;

    @Override
    public void run(String... args) throws Exception {

        log.info("════════════════════════════════════════");
        log.info("  Naukri Bot — Day 2: Scrape + Store   ");
        log.info("════════════════════════════════════════");

        BrowserSession session = null;
        try {
            // ── Step 1: Get authenticated session ─────────────────
            session = sessionManager.getOrCreateSession();
            log.info("✅ Session ready");

            // ── Step 2: Scrape all keyword × location combos ───────
            List<Job> scraped = jobScraper.scrapeAll(session);
            log.info("🔍 Scraped {} total jobs", scraped.size());

            // ── Step 3: Dedup + save to SQLite ─────────────────────
            List<Job> saved = jobStorageService.saveNewJobs(scraped);

            // ── Step 4: Summary ────────────────────────────────────
            log.info("════════════════════════════════════════");
            log.info("  DAY 2 SUMMARY");
            log.info("  Scraped this run  : {}", scraped.size());
            log.info("  Newly saved       : {}", saved.size());
            log.info("  Total in DB       : {}", jobStorageService.totalJobsInDb());
            log.info("  Awaiting scoring  : {}",
                    jobStorageService.totalByStatus(Job.ApplicationStatus.DISCOVERED));
            log.info("════════════════════════════════════════");

        } finally {
            if (session != null) {
                session.close();
                log.info("Browser closed.");
            }
        }
    }
}