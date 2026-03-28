package com.naukri.bot.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "jobs")
@NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String jobId;           // Naukri's internal job ID

    private String title;
    private String company;
    private String location;
    private String experienceRequired;
    private String salaryRange;

    @Column(unique = true)
    private String externalApplyUrl;

    @Column(columnDefinition = "TEXT")
    private String jobDescription;

    private String jobUrl;

    private Double score;           // 0-100 match score

    @Column(name = "score_breakdown", columnDefinition = "TEXT")
    private String scoreBreakdown;

    private String tailoredResumePath;  // Path to the generated PDF

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status = ApplicationStatus.DISCOVERED;

    private String failureReason;       // If status = FAILED

    private LocalDateTime scrapedAt;
    private LocalDateTime appliedAt;

    @PrePersist
    protected void onCreate() {
        scrapedAt = LocalDateTime.now();
    }

    public enum ApplicationStatus {
        DISCOVERED,     // Scraped, not yet scored
        QUEUED,         // Passed score threshold, ready to apply
        APPLYING,       // Currently being processed
        APPLIED,        // Successfully submitted
        SKIPPED,        // Below score threshold or blacklisted
        FAILED,         // Apply attempt failed
        NEEDS_REVIEW    // CAPTCHA or unusual form detected
    }
}