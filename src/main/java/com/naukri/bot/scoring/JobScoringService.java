package com.naukri.bot.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naukri.bot.config.NaukriConfig;
import com.naukri.bot.model.Job;
import com.naukri.bot.model.ScoreBreakdown;
import com.naukri.bot.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobScoringService {

    // ── Scoring weights (must sum to 1.0) ─────────────────────
    private static final double W_SKILL = 0.45;
    private static final double W_EXPERIENCE = 0.25;
    private static final double W_LOCATION = 0.15;
    private static final double W_TITLE = 0.10;
    private static final double W_SALARY = 0.05;

    // ── Skill tier weights inside the skill score ──────────────
    private static final double SKILL_W_PRIMARY = 1.0;
    private static final double SKILL_W_SECONDARY = 0.6;
    private static final double SKILL_W_BONUS = 0.3;

    private final NaukriConfig config;
    private final JobRepository jobRepository;
    private final ObjectMapper mapper;

    /**
     * Fetches every DISCOVERED job, scores it, updates status to
     * QUEUED (above threshold) or SKIPPED (below threshold).
     * Returns only the jobs that made it to QUEUED.
     */
    public List<Job> scoreAndQueueAll() {

        List<Job> discovered = jobRepository.findByStatus(Job.ApplicationStatus.DISCOVERED);
        log.info("📋 Scoring {} DISCOVERED jobs...", discovered.size());

        List<Job> queued = new ArrayList<>();
        int skipped = 0;

        for (Job job : discovered) {
            try {
                ScoreBreakdown breakdown = score(job);
                applyBreakdownToJob(job, breakdown);
                jobRepository.save(job);

                if (job.getStatus() == Job.ApplicationStatus.QUEUED) {
                    queued.add(job);
                    log.info("✅ QUEUED  [{:.1f}%] {} @ {}",
                            breakdown.getFinalScore(), job.getTitle(), job.getCompany());
                } else {
                    skipped++;
                    log.info("⏭ SKIPPED [{:.1f}%] {} @ {} — {}",
                            breakdown.getFinalScore(), job.getTitle(),
                            job.getCompany(), breakdown.getSkipReason());
                }
            } catch (Exception e) {
                log.error("❌ Scoring failed for {}: {}", job.getTitle(), e.getMessage());
            }
        }

        log.info("📊 Scoring done — queued: {}, skipped: {}", queued.size(), skipped);
        return queued;
    }

    public ScoreBreakdown score(Job job) {

        NaukriConfig.Scoring cfg = config.getScoring();
        String jdLower = normalise(job.getJobDescription());
        String titleLower = normalise(job.getTitle());

        // ── Hard filters first (instant SKIP, no score needed) ─
        String blacklistReason = checkBlacklists(job, cfg);
        if (blacklistReason != null) {
            return ScoreBreakdown.builder()
                    .finalScore(0)
                    .verdict("BLACKLISTED")
                    .skipReason(blacklistReason)
                    .build();
        }

        // ── Component scores ───────────────────────────────────
        SkillMatchResult skillResult = scoreSkills(jdLower, cfg.getSkills());
        double expScore = scoreExperience(job, cfg.getYourExperienceYears());
        double locationScore = scoreLocation(job, cfg.getPreferredLocations());
        double titleScore = scoreTitle(titleLower, cfg);
        double salaryScore = scoreSalary(job, cfg.getMinSalaryLpa());

        // ── Weighted final ─────────────────────────────────────
        double finalScore =
                skillResult.score * W_SKILL +
                        expScore * W_EXPERIENCE +
                        locationScore * W_LOCATION +
                        titleScore * W_TITLE +
                        salaryScore * W_SALARY;

        finalScore = Math.min(100.0, finalScore);

        String verdict = finalScore >= cfg.getMinScoreThreshold() ? "QUEUED" : "SKIPPED";
        String skipReason = verdict.equals("SKIPPED")
                ? buildSkipReason(skillResult, expScore, locationScore, titleScore, salaryScore)
                : null;

        return ScoreBreakdown.builder()
                .skillScore(skillResult.score)
                .experienceScore(expScore)
                .locationScore(locationScore)
                .titleScore(titleScore)
                .salaryScore(salaryScore)
                .skillWeight(W_SKILL)
                .experienceWeight(W_EXPERIENCE)
                .locationWeight(W_LOCATION)
                .titleWeight(W_TITLE)
                .salaryWeight(W_SALARY)
                .finalScore(finalScore)
                .verdict(verdict)
                .matchedPrimarySkills(skillResult.matchedPrimary)
                .matchedSecondarySkills(skillResult.matchedSecondary)
                .matchedBonusSkills(skillResult.matchedBonus)
                .skipReason(skipReason)
                .build();
    }

    private String checkBlacklists(Job job, NaukriConfig.Scoring cfg) {

        // Company blacklist
        String companyLower = normalise(job.getCompany());
        for (String blocked : cfg.getBlacklistedCompanies()) {
            if (companyLower.contains(normalise(blocked))) {
                return "Blacklisted company: " + job.getCompany();
            }
        }

        // Title keyword blacklist
        String titleLower = normalise(job.getTitle());
        for (String blocked : cfg.getBlacklistedTitleKeywords()) {
            if (titleLower.contains(normalise(blocked))) {
                return "Blacklisted title keyword: " + blocked;
            }
        }

        return null; // not blacklisted
    }

    private SkillMatchResult scoreSkills(String jdLower,
                                         NaukriConfig.Scoring.Skills skills) {

        double earned = 0, maxPossible = 0;

        List<String> matchedPrimary = new ArrayList<>();
        List<String> matchedSecondary = new ArrayList<>();
        List<String> matchedBonus = new ArrayList<>();

        // Primary — weight 1.0 each
        for (String skill : skills.getPrimary()) {
            maxPossible += SKILL_W_PRIMARY;
            if (jdLower.contains(normalise(skill))) {
                earned += SKILL_W_PRIMARY;
                matchedPrimary.add(skill);
            }
        }

        // Secondary — weight 0.6 each
        for (String skill : skills.getSecondary()) {
            maxPossible += SKILL_W_SECONDARY;
            if (jdLower.contains(normalise(skill))) {
                earned += SKILL_W_SECONDARY;
                matchedSecondary.add(skill);
            }
        }

        // Bonus — weight 0.3 each
        for (String skill : skills.getBonus()) {
            maxPossible += SKILL_W_BONUS;
            if (jdLower.contains(normalise(skill))) {
                earned += SKILL_W_BONUS;
                matchedBonus.add(skill);
            }
        }

        double score = maxPossible > 0 ? (earned / maxPossible) * 100.0 : 0.0;

        return new SkillMatchResult(
                score,
                String.join(", ", matchedPrimary),
                String.join(", ", matchedSecondary),
                String.join(", ", matchedBonus)
        );
    }

    /**
     * Parses the job's experience string e.g. "2 - 5 Yrs", "3 to 7 Years"
     * and scores how well your experience fits the required range.
     * <p>
     * - Inside range                 → 100
     * - 1 year outside either end   → 70
     * - 2 years outside             → 40
     * - 3+ years outside            → 0
     */
    private double scoreExperience(Job job, float yourExp) {

        String expStr = normalise(job.getExperienceRequired());
        if (expStr == null || expStr.isBlank()) return 70.0; // unknown = neutral

        int[] range = parseExperienceRange(expStr);
        if (range == null) return 70.0;

        float minExp = range[0];
        float maxExp = range[1];

        if (yourExp >= minExp && yourExp <= maxExp) return 100.0;

        float gap = yourExp < minExp
                ? minExp - yourExp
                : yourExp - maxExp;

        if (gap <= 1.0) return 70.0;
        if (gap >= 1 && gap <= 2.0 || gap > 2.0) return 40.0;
        return 0.0;
    }

    /**
     * Parses strings like:
     * "2 - 5 yrs", "3 to 7 years", "4-6 Yrs", "5 Years", "0-1 Year"
     */
    private int[] parseExperienceRange(String expStr) {
        try {
            // Remove non-numeric except spaces and hyphens
            String cleaned = expStr.replaceAll("[^0-9\\-\\s]", "").trim();
            String[] parts = cleaned.split("[\\s\\-]+");

            if (parts.length >= 2) {
                int min = Integer.parseInt(parts[0].trim());
                int max = Integer.parseInt(parts[1].trim());
                return new int[]{min, max};
            } else if (parts.length == 1 && !parts[0].isBlank()) {
                int val = Integer.parseInt(parts[0].trim());
                return new int[]{val, val};
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private double scoreLocation(Job job, List<String> preferredLocations) {

        String locationLower = normalise(job.getLocation());
        if (locationLower == null || locationLower.isBlank()) return 50.0;

        // Remote / WFH always scores max
        if (locationLower.contains("remote") ||
                locationLower.contains("work from home") ||
                locationLower.contains("wfh")) {
            return 100.0;
        }

        for (String preferred : preferredLocations) {
            if (locationLower.contains(normalise(preferred))) return 100.0;
        }

        // Partial city match — e.g. "Hyderabad, Secunderabad"
        for (String preferred : preferredLocations) {
            String city = normalise(preferred).split("[,\\s]+")[0];
            if (!city.isBlank() && locationLower.contains(city)) return 80.0;
        }

        return 30.0; // different city
    }

    /**
     * Checks how many of your scraper keywords appear in the job title.
     * Titles are the most reliable signal for role fit.
     */
    private double scoreTitle(String titleLower, NaukriConfig.Scoring cfg) {

        List<String> allKeywords = new ArrayList<>(config.getScraper().getKeywords());

        // Also check primary skills in title (e.g. "Java Backend Engineer")
        allKeywords.addAll(cfg.getSkills().getPrimary());

        long matches = allKeywords.stream()
                .filter(k -> titleLower.contains(normalise(k)))
                .count();

        if (matches == 0) return 30.0;   // title mismatch — still possible fit
        if (matches == 1) return 70.0;
        return 100.0;                    // 2+ keyword matches in title
    }

    /**
     * Binary: if salary disclosed and above your min → 100, else 50 (unknown).
     * Naukri often hides salary so we give neutral score when not disclosed.
     */
    private double scoreSalary(Job job, int minSalaryLpa) {

        if (minSalaryLpa <= 0) return 100.0; // no filter configured

        String salaryStr = normalise(job.getSalaryRange());
        if (salaryStr == null || salaryStr.isBlank() ||
                salaryStr.contains("not disclosed") ||
                salaryStr.contains("not mentioned")) {
            return 50.0; // neutral — don't penalise undisclosed salary
        }

        // Parse first number found e.g. "12 - 18 LPA" → 12
        try {
            String firstNum = salaryStr.replaceAll("[^0-9\\.]", " ").trim().split("\\s+")[0];
            double lpa = Double.parseDouble(firstNum);
            return lpa >= minSalaryLpa ? 100.0 : 20.0;
        } catch (Exception ignored) {
        }

        return 50.0; // couldn't parse → neutral
    }

    private void applyBreakdownToJob(Job job, ScoreBreakdown breakdown) {
        job.setScore(breakdown.getFinalScore());

        // Serialise breakdown to JSON for DB storage
        try {
            job.setScoreBreakdown(mapper.writeValueAsString(breakdown));
        } catch (Exception e) {
            job.setScoreBreakdown("{\"error\":\"serialisation failed\"}");
        }

        switch (breakdown.getVerdict()) {
            case "QUEUED" -> job.setStatus(Job.ApplicationStatus.QUEUED);
            case "BLACKLISTED" -> {
                job.setStatus(Job.ApplicationStatus.SKIPPED);
                job.setFailureReason(breakdown.getSkipReason());
            }
            default -> {
                job.setStatus(Job.ApplicationStatus.SKIPPED);
                job.setFailureReason(breakdown.getSkipReason());
            }
        }
    }

    private String buildSkipReason(SkillMatchResult skillResult,
                                   double expScore,
                                   double locationScore,
                                   double titleScore,
                                   double salaryScore) {
        List<String> reasons = new ArrayList<>();

        if (skillResult.score < 40) reasons.add(String.format("low skill match (%.0f%%)", skillResult.score));
        if (expScore < 40) reasons.add(String.format("exp mismatch (%.0f%%)", expScore));
        if (locationScore < 40) reasons.add(String.format("location mismatch (%.0f%%)", locationScore));
        if (titleScore < 40) reasons.add(String.format("title mismatch (%.0f%%)", titleScore));
        if (salaryScore < 40) reasons.add(String.format("salary below min (%.0f%%)", salaryScore));

        return reasons.isEmpty() ? "below threshold" : String.join("; ", reasons);
    }

    private String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[^a-z0-9\\s+#]", " ")  // keep +, # for C++, C#
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record SkillMatchResult(
            double score,
            String matchedPrimary,
            String matchedSecondary,
            String matchedBonus
    ) {
    }
}