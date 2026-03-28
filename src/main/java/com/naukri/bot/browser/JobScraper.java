package com.naukri.bot.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.naukri.bot.config.NaukriConfig;
import com.naukri.bot.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobScraper {
    private final NaukriConfig config;
    private final Random random;

    /**
     * Iterates every keyword × location combination,
     * scrapes listing cards, then fetches full JD for each.
     * Returns raw Job objects — not yet saved to DB.
     */
    public List<Job> scrapeAll(BrowserSession session) {
        List<Job> collected = new ArrayList<>();

        for (String keyword : config.getScraper().getKeywords()) {
            for (String location : config.getScraper().getLocations()) {
                try {
                    List<Job> batch = scrapeKeywordLocation(session, keyword, location);
                    collected.addAll(batch);
                    log.info("📦 [{} @ {}] scraped {} jobs", keyword, location, batch.size());
                    randomDelay(1000, 3000); // cooldown between keyword searches
                } catch (Exception e) {
                    log.error("❌ Scrape failed [{} @ {}]: {}", keyword, location, e.getMessage());
                }
            }
        }
        log.info("✅ Total scraped this run: {}", collected.size());
        return collected;
    }

    /**
     * Scrapes multiple pages of results for a given keyword + location.
     * For each listing card found, fetches the full JD and constructs Job objects.
     * Implements natural delays and scrolling to mimic human behavior.
     */
    private List<Job> scrapeKeywordLocation(BrowserSession session, String keyword, String location) throws Exception {
        List<Job> jobs = new ArrayList<>();
        Page page = session.getPage();

        for (int pageNum = 1; pageNum <= config.getScraper().getPagesPerKeyword(); pageNum++) {
            String url = buildSearchUrl(keyword, location, pageNum);
            log.info("🔍 Scraping page {} → {}", pageNum, url);


            page.navigate(url);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            randomDelay(1000, 2000);

            scrollNaturally(page);

            List<JobCard> cards = extractListingCards(page);
            log.info("   Found {} cards on page {}", cards.size(), pageNum);

            if (cards.isEmpty()) {
                log.info("   No more results — stopping pagination for this keyword");
                break;
            }

            for (JobCard card : cards) {
                try {
                    Job job = fetchFullJd(session, card);
                    if (job != null) jobs.add(job);

                    randomDelay(
                            config.getScraper().getJdFetchDelayMin(),
                            config.getScraper().getJdFetchDelayMax()
                    );
                } catch (Exception e) {
                    log.warn("   ⚠️ JD fetch failed for [{}]: {}", card.title(), e.getMessage());
                }
            }
            randomDelay(1000, 2000); // between pages
        }
        return jobs;
    }

    /**
     * Constructs the search URL for a given keyword, location, and page number.
     * - Converts spaces to hyphens and lowercases the keyword and location.
     * - Handles "remote" location as a special case.
     * - Appends experience and job age filters as query parameters.
     */
    private String buildSearchUrl(String keyword, String location, int page) {

        // Naukri URL format:
        // https://www.naukri.com/java-developer-jobs-in-hyderabad?experience=2&pageNo=1
        String keywordSlug = keyword.toLowerCase().replace(" ", "-");
        String locationSlug = location.equalsIgnoreCase("remote")
                ? "work-from-home"
                : location.toLowerCase().replace(" ", "-");

        String base = String.format(
                "https://www.naukri.com/%s-jobs-in-%s",
                keywordSlug, locationSlug
        );

        log.info("   Constructed search URL: {}", base + String.format(
                "?experience=%d&pageNo=%d&jobAge=7",
                config.getScraper().getExperienceMin(), page
        ));

        return base + String.format(
                "?experience=%d&pageNo=%d&jobAge=7",
                config.getScraper().getExperienceMin(), page
        );
    }

    /**
     * Given a job listing card, navigates to the job detail page,
     * extracts the full job description, and constructs a Job object.
     * Implements error handling and returns null if JD fetch fails.
     */
    private List<JobCard> extractListingCards(Page page) {

        List<JobCard> cards = new ArrayList<>();

        // Naukri's job card selector (stable as of 2024-2025)
        Locator jobArticles = page.locator("article.jobTuple");

        // Fallback selector if Naukri changed markup
        if (jobArticles.count() == 0) {
            jobArticles = page.locator("[class*='srp-jobtuple']");
        }
        if (jobArticles.count() == 0) {
            jobArticles = page.locator(".job-tuple-wrapper");
        }

        int count = jobArticles.count();
        for (int i = 0; i < count; i++) {
            try {
                Locator article = jobArticles.nth(i);

                // ── Title + URL ───────────────────────────────────────
                Locator titleEl = article.locator("a.title");
                if (titleEl.count() == 0) titleEl = article.locator("[class*='title'] a");
                if (titleEl.count() == 0) continue;

                String title = titleEl.innerText().trim();
                String jobUrl = titleEl.getAttribute("href");
                if (jobUrl == null || jobUrl.isBlank()) continue;

                // Naukri encodes jobId in the URL: .../job-listing-title-jobid
                String jobId = extractJobIdFromUrl(jobUrl);

                // ── Company ───────────────────────────────────────────
                Locator compEl = article.locator("a.comp-name");
                if (compEl.count() == 0) compEl = article.locator("[class*='comp-name']");
                String company = compEl.count() > 0 ? compEl.innerText().trim() : "Unknown";

                // ── Location ──────────────────────────────────────────
                Locator locEl = article.locator(".locWdth");
                if (locEl.count() == 0) locEl = article.locator("[class*='location']");
                String location = locEl.count() > 0 ? locEl.innerText().trim() : "";

                // ── Experience ────────────────────────────────────────
                Locator expEl = article.locator(".expwdth");
                if (expEl.count() == 0) expEl = article.locator("[class*='experience']");
                String exp = expEl.count() > 0 ? expEl.innerText().trim() : "";

                // ── Salary ────────────────────────────────────────────
                Locator salEl = article.locator(".sal");
                if (salEl.count() == 0) salEl = article.locator("[class*='salary']");
                String salary = salEl.count() > 0 ? salEl.innerText().trim() : "Not disclosed";

                cards.add(new JobCard(jobId, title, company, location, exp, salary, jobUrl));

            } catch (Exception e) {
                log.warn("   Card parse error at index {}: {}", i, e.getMessage());
            }
        }

        return cards;
    }

    /**
     * Given a JobCard (basic info from listing), navigates to the job detail page,
     * extracts the full job description and skills, and constructs a Job object.
     * - Implements error handling and returns null if JD fetch fails.
     * - Uses natural scrolling and delays to mimic human behavior.
     * - Combines JD text and skills into a single field for easier processing later.
     */
    private Job fetchFullJd(BrowserSession session, JobCard card) throws Exception {

        Page page = session.getPage();

        page.navigate(card.jobUrl());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        randomDelay(1500, 2500);

        scrollNaturally(page); // trigger lazy sections
        String jdText = extractJdText(page);
        String skills = extractSkills(page);

        Job job = new Job();
        job.setJobId(card.jobId());
        job.setTitle(card.title());
        job.setCompany(card.company());
        job.setLocation(card.location());
        job.setExperienceRequired(card.experience());
        job.setSalaryRange(card.salary());
        job.setJobUrl(card.jobUrl());
        job.setJobDescription(jdText + (skills.isBlank() ? "" : "\n\nSkills: " + skills));
        job.setStatus(Job.ApplicationStatus.DISCOVERED);
        job.setScrapedAt(LocalDateTime.now());

        log.debug("   ✔ JD fetched: [{}] @ {}", card.title(), card.company());
        return job;
    }

    /**
     * Naukri's JD pages can have various structures. This method tries multiple selectors
     * to find the main job description text. If none are found, it returns an empty string and logs a warning.
     * The extracted text is cleaned and trimmed before being returned.
     */
    private String extractJdText(Page page) {

        String[] jdSelectors = {
                "[class*='dang-inner-html']"
        };

        for (String sel : jdSelectors) {
            Locator elements = page.locator(sel);

            int count = elements.count();

            for (int i = 0; i < count; i++) {
                Locator el = elements.nth(i);

                String text = el.innerText().trim();

                if (!text.isBlank()) {
                    return cleanText(text);
                }
            }
        }

        log.warn("JD text not found — falling back to body text");
        return page.locator("body").innerText(); // optional fallback
    }

    /**
     * Extracts skills from the JD page by looking for common "chip"/"tag" elements that Naukri uses to highlight key skills.
     * Tries multiple selectors to find these skill elements. If found, it collects their text
     * into a single comma-separated string. If no skills are found, returns an empty string.
     * This method helps capture important keywords that may not be in the main JD text but are crucial for scoring and tailoring resumes later.
     */
    private String extractSkills(Page page) {

        String[] skillSelectors = {
                ".key-skill a",
                "[class*='key-skill']",
                ".skills-section span",
                "[class*='chip']"
        };

        for (String sel : skillSelectors) {
            Locator el = page.locator(sel);
            if (el.count() > 0) {
                // Collect all chips/tags into one comma-separated string
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < el.count(); i++) {
                    String skill = el.nth(i).innerText().trim();
                    if (!skill.isBlank()) {
                        if (!sb.isEmpty()) sb.append(", ");
                        sb.append(skill);
                    }
                }
                if (!sb.isEmpty()) return cleanText(sb.toString());
            }
        }

        return "";
    }

    /**
     * Scrolls the page in natural chunks with random pauses.
     * Naukri lazy-loads job cards — this ensures all are rendered.
     */
    private void scrollNaturally(Page page) {
        int scrollSteps = random(3, 6);
        for (int i = 0; i < scrollSteps; i++) {
            page.mouse().wheel(0, random(300, 700));
            randomDelay(400, 800);
        }
        // Scroll back up slightly — real users do this
        page.mouse().wheel(0, -random(100, 300));
        randomDelay(300, 600);
    }

    /**
     * Naukri job URLs look like:
     * https://www.naukri.com/job-listings-java-developer-company-hyderabad-2-5-years-1234567890
     * The numeric suffix is the jobId.
     */
    private String extractJobIdFromUrl(String url) {
        if (url == null) return "unknown-" + System.currentTimeMillis();
        String[] parts = url.split("-");
        String last = parts[parts.length - 1].replaceAll("[^0-9]", "");
        return last.isBlank() ? "unknown-" + System.currentTimeMillis() : last;
    }

    private void randomDelay(int min, int max) {
        sleep(random(min, max));
    }

    private int random(int min, int max) {
        if (max <= min) {
            return min; // fallback instead of crashing
        }
        return random.nextInt(max - min) + min;
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private String cleanText(String text) {
        return text
                .replaceAll("\\r", "")
                .replaceAll("\\n+", "\n")     // single newline only
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private record JobCard(
            String jobId,
            String title,
            String company,
            String location,
            String experience,
            String salary,
            String jobUrl
    ) {
    }
}
