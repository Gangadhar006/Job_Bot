package com.naukri.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naukri.bot.config.NaukriProperties;
import com.naukri.bot.model.Job;
import com.naukri.bot.model.ScoreBreakdown;
import com.naukri.bot.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobScoringService {

    // ── Weights ─────────────────────────────
    private static final double W_SKILL = 0.40;
    private static final double W_EXPERIENCE = 0.20;
    private static final double W_LOCATION = 0.15;
    private static final double W_TITLE = 0.10;
    private static final double W_SALARY = 0.05;
    private static final double W_RECENCY = 0.10;

    private final NaukriProperties config;
    private final JobRepository jobRepository;
    private final ObjectMapper mapper;
    private final JobClassifierService classifier;

    @Transactional
    public List<Job> scoreAndQueueAll() {

        List<Job> test = jobRepository.findByStatus(Job.ApplicationStatus.SKIPPED);
        List<Job> failed = jobRepository.findByStatus(Job.ApplicationStatus.FAILED);
        List<Job> applied = jobRepository.findByStatus(Job.ApplicationStatus.APPLIED);

        List<Job> discovered = Stream.of(test, failed, applied).flatMap(List::stream).toList();

        log.info("📋 Scoring {} DISCOVERED jobs...", discovered.size());

        List<Job> queued = new ArrayList<>();
        int skipped = 0;

        for (Job job : discovered) {
            try {
                ScoreBreakdown breakdown = score(job);
                applyBreakdownToJob(job, breakdown);

                job.setCategory(breakdown.getCategory());

                log.info("saved category: {}", job.getCategory());
                jobRepository.save(job);

                if (job.getStatus() == Job.ApplicationStatus.QUEUED) {
                    queued.add(job);
                    log.info("✅ QUEUED [{}%] {} @ {}",
                            String.format("%.1f", breakdown.getFinalScore()),
                            job.getTitle(),
                            job.getCompany());
                } else {
                    skipped++;
                    log.info("⏭ SKIPPED [{}%] {} @ {}",
                            String.format("%.1f", breakdown.getFinalScore()),
                            job.getTitle(),
                            job.getCompany());
                }
            } catch (Exception e) {
                log.error("❌ Scoring failed for {}: {}", job.getTitle(), e.getMessage());
            }
        }

        log.info("📊 Scoring done — queued: {}, skipped: {}", queued.size(), skipped);
        return queued;
    }

    public ScoreBreakdown score(Job job) {
        String jd = normalise(job.getJobDescription());
        String title = normalise(job.getTitle());

        String category = classifier.classify(job.getJobDescription());
        SkillMatchResult skillResult = scoreSkills(jd, category);

        double expScore = scoreExperience(job, config.getScoring().getYourExperienceYears());
        double locationScore = scoreLocation(job, config.getScoring().getPreferredLocations());
        double titleScore = scoreTitle(title);
        double salaryScore = scoreSalary(job);
        double recencyScore = scoreRecency(job);

        double finalScore =
                skillResult.score * W_SKILL +
                        expScore * W_EXPERIENCE +
                        locationScore * W_LOCATION +
                        titleScore * W_TITLE +
                        salaryScore * W_SALARY +
                        recencyScore * W_RECENCY;

        finalScore = Math.min(100, finalScore);

        String verdict = finalScore >= config.getScoring().getMinScoreThreshold()
                ? Job.ApplicationStatus.QUEUED.name() : Job.ApplicationStatus.SKIPPED.name();

        return ScoreBreakdown.builder()
                .finalScore(finalScore)
                .verdict(verdict)
                .skillScore(skillResult.score)
                .experienceScore(expScore)
                .locationScore(locationScore)
                .titleScore(titleScore)
                .salaryScore(salaryScore)
                .matchedPrimarySkills(skillResult.primary)
                .matchedSecondarySkills(skillResult.secondary)
                .matchedBonusSkills(skillResult.bonus)
                .category(category)
                .priority(getPriority(finalScore))
                .build();
    }

    // =========================
    // 🧠 CATEGORY-AWARE SKILL SCORING
    // =========================
    private SkillMatchResult scoreSkills(String jd, String category) {

        var skills = config.getScoring().getSkills();

        double earned = 0, max = 0;

        List<String> primary = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        List<String> bonus = new ArrayList<>();

        for (String s : skills.getPrimary()) {
            max += 1.0;
            if (jd.contains(normalise(s))) {
                earned += 1.0;
                primary.add(s);
            }
        }

        for (String s : skills.getSecondary()) {
            max += 0.6;
            if (jd.contains(normalise(s))) {

                double weight = 0.6;

                // 🔥 CATEGORY BOOST
                if (category.equals("EVENT_DRIVEN") &&
                        (s.equalsIgnoreCase("kafka") || s.equalsIgnoreCase("redis"))) {
                    weight = 1.2;
                }

                earned += weight;
                secondary.add(s);
            }
        }

        for (String s : skills.getBonus()) {
            max += 0.3;
            if (jd.contains(normalise(s))) {
                earned += 0.3;
                bonus.add(s);
            }
        }

        double score = max > 0 ? (earned / max) * 100 : 0;

        return new SkillMatchResult(score,
                String.join(",", primary),
                String.join(",", secondary),
                String.join(",", bonus));
    }

    // =========================
    // 🧠 EXPERIENCE (FIXED)
    // =========================
    private double scoreExperience(Job job, float yourExp) {

        int[] range = parseExperience(job.getExperienceRequired());
        if (range == null) return 70;

        float min = range[0], max = range[1];

        if (yourExp >= min && yourExp <= max) return 100;

        float gap = yourExp < min ? min - yourExp : yourExp - max;

        if (gap <= 1) return 70;
        if (gap <= 2) return 40;
        return 0;
    }

    private int[] parseExperience(String exp) {
        try {
            String cleaned = exp.replaceAll("[^0-9\\-]", " ");
            String[] parts = cleaned.trim().split("\\s+");

            if (parts.length >= 2) {
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =========================
    // 📍 LOCATION
    // =========================
    private double scoreLocation(Job job, List<String> preferred) {

        String loc = normalise(job.getLocation());

        if (loc.contains("remote") || loc.contains("wfh")) return 100;

        for (String p : preferred) {
            if (loc.contains(normalise(p))) return 100;
        }

        return 30;
    }

    private double scoreTitle(String title) {

        if (title.contains("senior")) return 50;
        if (title.contains("lead")) return 20;

        if (title.contains("java") || title.contains("backend")) return 100;

        return 50;
    }

    private double scoreSalary(Job job) {

        String salary = normalise(job.getSalaryRange());

        if (salary.contains("not disclosed")) return 50;

        try {
            double val = Double.parseDouble(salary.replaceAll("[^0-9]", ""));
            return val >= config.getScoring().getMinSalaryLpa() ? 100 : 20;
        } catch (Exception e) {
            return 50;
        }
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
        jobRepository.save(job);
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

    private double scoreRecency(Job job) {

        if (job.getPostedDate() == null) return 50;

        long days = ChronoUnit.DAYS.between(job.getPostedDate().toLocalDate(), LocalDate.now());

        if (days == 0) return 100;
        if (days <= 2) return 80;
        if (days <= 7) return 50;

        return 20;
    }

    private boolean containsEmail(String jd) {
        return jd.contains("@");
    }

    private String getPriority(double score) {
        if (score >= 85) return "HIGH";
        if (score >= 70) return "MEDIUM";
        return "LOW";
    }

    private String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9\\s+#]", " ");
    }

    private record SkillMatchResult(
            double score,
            String primary,
            String secondary,
            String bonus
    ) {
    }
}