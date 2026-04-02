package com.naukri.bot.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.naukri.bot.config.NaukriProperties;
import com.naukri.bot.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobScraper {
    private final NaukriProperties config;
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
                    int freshness = config.getScraper().getJobFreshness();
                    int experience = config.getScraper().getExperienceMin();
                    List<Integer> cityList = config.getScraper().getNaukriCityId();
                    List<Job> batch = scrapeKeywordLocation(session, keyword, location, experience, freshness, cityList);
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
    private List<Job> scrapeKeywordLocation(BrowserSession session, String keyword, String location, int experience, int freshness, List<Integer> naukriCityId) throws Exception {
        List<Job> jobs = new ArrayList<>();
        Page page = session.getPage();

        for (int pageNum = 1; pageNum <= config.getScraper().getPagesPerKeyword(); pageNum++) {
            String url = buildSearchUrl(keyword, location, pageNum, experience, freshness, naukriCityId);
            log.info("🔍 Scraping page {} → {}", pageNum, url);


            page.navigate(url);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            randomDelay(1000, 2000);

            scrollNaturally(page);

            List<JobCard> cards = extractListingCards(page);
            cards.stream().forEach(job -> log.info("jobs found: {}", job));

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
//    private String buildSearchUrl(String keyword, String location, int page) {
//
////        https://www.naukri.com/data-engineer-jobs-in-bengaluru-7?cityTypeGid=97&jobAge=1&experience=3
//
//        String keywordSlug = keyword.toLowerCase().replace(" ", "-");
//        String locationSlug = location.equalsIgnoreCase("remote")
//                ? "work-from-home"
//                : location.toLowerCase().replace(" ", "-");
////
//        String base = String.format(
//                "https://www.naukri.com/%s-jobs-in-%s",
//                keywordSlug, locationSlug
//        );
//
//        log.info("   Constructed search URL: {}", base + String.format(
//                "?experience=%d&pageNo=%d&jobAge=7",
//                config.getScraper().getExperienceMin(), page
//        ));
//
//        return base + String.format(
//                "?experience=%d&pageNo=%d&jobAge=7",
//                config.getScraper().getExperienceMin(), page
//        );
//    }
    public String buildSearchUrl(String role, String location, int page, int experience, int freshness, List<Integer> naukriCityId) {
//        NAUKRI URL FORMAT
//        https://www.naukri.com/data-engineer-jobs-in-bengaluru-7?cityTypeGid=97&jobAge=1&experience=3
        String base = "https://www.naukri.com";

        String roleSlug = role.trim().toLowerCase().replace(" ", "-");

        String locationSlug = location.trim().toLowerCase().replace(" ", "-");

        String path = (page == 1)
                ? String.format("%s/%s-jobs-in-%s", base, roleSlug, locationSlug)
                : String.format("%s/%s-jobs-in-%s-%d", base, roleSlug, locationSlug, page);

        String naukriCitySlug = naukriCityId.stream()
                .map(cityId -> "cityTypeGid=" + cityId)
                .collect(Collectors.joining("&"));

        log.info("new url: {}", String.format(
                "%s?experience=%d&jobAge=%d&%s",
                path,
                experience,
                freshness,
                naukriCitySlug
        ));

        return String.format(
                "%s?experience=%d&jobAge=%d&%s",
                path,
                experience,
                freshness,
                naukriCitySlug
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
        LocalDateTime postedDate = extractPostedDate(page);

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
        job.setPostedDate(postedDate);

        log.debug("   ✔ JD fetched: [{}] @ {}", card.title(), card.company());
        return job;
    }

    private LocalDateTime extractPostedDate(Page page) {
        Locator posted = page.locator("span:has(label:text('Posted')) span");
        String postedText = posted.first().innerText().trim();
        return parsePostedDate(postedText);
    }

    public LocalDateTime parsePostedDate(String text) {

        if (text == null || text.isBlank()) {
            return null;
        }

        text = text.toLowerCase().trim();

        LocalDateTime now = LocalDateTime.now();

        try {
            if (text.contains("just now")) {
                return now;
            }

            if (text.contains("minute")) {
                int minutes = extractNumber(text);
                return now.minusMinutes(minutes);
            }

            if (text.contains("hour")) {
                int hours = extractNumber(text);
                return now.minusHours(hours);
            }

            if (text.contains("day")) {
                int days = extractNumber(text);
                return now.minusDays(days);
            }

            if (text.contains("week")) {
                int weeks = extractNumber(text);
                return now.minusWeeks(weeks);
            }

        } catch (Exception e) {
            return null;
        }

        return null;
    }

    private int extractNumber(String text) {
        String num = text.replaceAll("[^0-9]", "");
        return num.isEmpty() ? 0 : Integer.parseInt(num);
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
