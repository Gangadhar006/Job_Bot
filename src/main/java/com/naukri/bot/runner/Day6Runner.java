package com.naukri.bot.runner;

import com.naukri.bot.BotProcessMonitor;
import com.naukri.bot.browser.BrowserSession;
import com.naukri.bot.browser.JobApplier;
import com.naukri.bot.browser.JobScraper;
import com.naukri.bot.browser.SessionManager;
import com.naukri.bot.message.RecruiterInsightSender;
import com.naukri.bot.model.Job;
import com.naukri.bot.repository.JobRepository;
import com.naukri.bot.service.JobScoringService;
import com.naukri.bot.service.JobStorageService;
import com.naukri.bot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Profile("day6")
@Component
@RequiredArgsConstructor
public class Day6Runner implements CommandLineRunner {

    private final SessionManager sessionManager;
    private final JobScraper jobScraper;
    private final JobStorageService jobStorageService;
    private final JobScoringService jobScoringService;
    private final JobApplier jobApplier;
    private final JobRepository jobRepository;
    private final RecruiterInsightSender sender;


    @Override
    public void run(String... args) throws Exception {

        log.info("════════════════════════════════════════════════════");
        log.info("  Naukri Bot — Day 6: Full Apply Pipeline           ");
        log.info("════════════════════════════════════════════════════");

        BrowserSession session = null;
        try {
            // ── 1. Login ───────────────────────────────────────────
            session = sessionManager.getOrCreateSession();
            log.info("✅ Session ready");


            // ── 2. Scrape ──────────────────────────────────────────
            List<Job> scraped = jobScraper.scrapeAll(session);
            jobStorageService.saveNewJobs(scraped);
            log.info("🔍 Scraped: {}", scraped.size());

            // ── 3. Score → queue ───────────────────────────────────
            for (Job job : scraped)
                jobStorageService.extractAndSaveContacts(job);
            jobScoringService.scoreAndQueueAll();


            // ── 4. Load QUEUED jobs ordered by score ───────────────
            List<Job> queued = jobRepository
                    .findByStatusOrderByScoreDesc(Job.ApplicationStatus.QUEUED);

//            List<Job> queued = jobRepository
//                    .findByStatusAndExternalApplyUrlIsNotNull(Job.ApplicationStatus.FAILED);

            List<Job> jobs = jobRepository.findAll();
            // ── 5. Apply ───────────────────────────────────────────
            int applied = jobApplier.applyToAll(session, jobs);


            sender.sendRecruiterInsights(jobRepository.findAll());


            // ── 6. Summary ─────────────────────────────────────────
            log.info("");
            log.info("════════════════════════════════════════════════════");
            log.info("  DAY 6 SUMMARY");
            log.info("  Scraped      : {}", scraped.size());
            log.info("  Queued       : {}", queued.size());
            log.info("  Applied      : {} ✅", applied);
            log.info("  Failed       : {} ❌",
                    jobStorageService.totalByStatus(Job.ApplicationStatus.FAILED));
            log.info("  Needs Review : {} 👀",
                    jobStorageService.totalByStatus(Job.ApplicationStatus.NEEDS_REVIEW));
            log.info("  Total in DB  : {}", jobStorageService.totalJobsInDb());
            log.info("════════════════════════════════════════════════════");

        } finally {
            if (session != null) {
                BotProcessMonitor.killBotProcesses();
                session.close();
                log.info("Browser closed.");
            }
        }
    }
}