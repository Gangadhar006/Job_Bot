package com.naukri.bot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naukri.bot.browser.BrowserSession;
import com.naukri.bot.browser.JobScraper;
import com.naukri.bot.browser.SessionManager;
import com.naukri.bot.model.Job;
import com.naukri.bot.model.ScoreBreakdown;
import com.naukri.bot.repository.JobRepository;
import com.naukri.bot.service.JobScoringService;
import com.naukri.bot.service.JobStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("day3")
@RequiredArgsConstructor
public class Day3Runner implements CommandLineRunner {

    private final SessionManager sessionManager;
    private final JobScraper jobScraper;
    private final JobStorageService jobStorageService;
    private final JobScoringService jobScoringService;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {

        log.info("════════════════════════════════════════════");
        log.info("  Naukri Bot — Day 3: Score + Queue        ");
        log.info("════════════════════════════════════════════");

        BrowserSession session = null;
        try {
            // ── Step 1: Login ──────────────────────────────────────
            session = sessionManager.getOrCreateSession();
            log.info("✅ Session ready");

            // ── Step 2: Scrape fresh jobs ──────────────────────────
            List<Job> scraped = jobScraper.scrapeAll(session);
            List<Job> saved = jobStorageService.saveNewJobs(scraped);
            log.info("🔍 Scraped: {}  |  Newly saved: {}", scraped.size(), saved.size());

            // ── Step 3: Score all DISCOVERED jobs ──────────────────
            List<Job> queued = jobScoringService.scoreAndQueueAll();

            // ── Step 4: Print score breakdown for top 5 ───────────
            log.info("");
            log.info("── TOP QUEUED JOBS ──────────────────────────────");
            queued.stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .limit(5)
                    .forEach(job -> {
                        try {
                            ScoreBreakdown bd = objectMapper.readValue(
                                    job.getScoreBreakdown(), ScoreBreakdown.class);
                            log.info("");
                            log.info("  📌 {} @ {}", job.getTitle(), job.getCompany());
                            log.info("     Score      : {:.1f}%", job.getScore());
                            log.info("     Skills     : {:.0f}% — matched: {}",
                                    bd.getSkillScore(), bd.getMatchedPrimarySkills());
                            log.info("     Experience : {:.0f}%", bd.getExperienceScore());
                            log.info("     Location   : {}", job.getLocation());
                            log.info("     URL        : {}", job.getJobUrl());
                        } catch (Exception ignored) {
                            log.info("  📌 {} @ {} — {:.1f}%",
                                    job.getTitle(), job.getCompany(), job.getScore());
                        }
                    });

            // ── Step 5: Full summary ───────────────────────────────
            long totalInDb = jobStorageService.totalJobsInDb();
            long totalQueued = jobStorageService.totalByStatus(Job.ApplicationStatus.QUEUED);
            long totalSkipped = jobStorageService.totalByStatus(Job.ApplicationStatus.SKIPPED);
            long totalApplied = jobStorageService.totalByStatus(Job.ApplicationStatus.APPLIED);

            log.info("");
            log.info("════════════════════════════════════════════");
            log.info("  DAY 3 SUMMARY");
            log.info("  Total in DB   : {}", totalInDb);
            log.info("  Queued        : {} ✅", totalQueued);
            log.info("  Skipped       : {} ⏭", totalSkipped);
            log.info("  Applied       : {} 📨", totalApplied);
            log.info("════════════════════════════════════════════");

        } finally {
            if (session != null) {
                session.close();
                log.info("Browser closed.");
            }
        }
    }
}