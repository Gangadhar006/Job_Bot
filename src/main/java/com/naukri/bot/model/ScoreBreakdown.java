package com.naukri.bot.model;

import lombok.Builder;
import lombok.Data;

/**
 * Detailed breakdown of how a job was scored.
 * Serialised to JSON and stored in Job.scoreBreakdown column.
 * Useful for debugging why a job was queued or skipped.
 */
@Data
@Builder
public class ScoreBreakdown {

    // ── Component scores (each 0–100) ─────────────────────────
    private double skillScore;        // weighted keyword match in JD
    private double experienceScore;   // how well exp range fits yours
    private double locationScore;     // preferred location match
    private double titleScore;        // title relevance to your keywords
    private double salaryScore;       // salary above your min (0 or 100)

    private double skillWeight;
    private double experienceWeight;
    private double locationWeight;
    private double titleWeight;
    private double salaryWeight;

    private double finalScore;        // weighted average
    private String verdict;           // QUEUED / SKIPPED / BLACKLISTED

    private String matchedPrimarySkills;
    private String matchedSecondarySkills;
    private String matchedBonusSkills;
    private String skipReason;        // if verdict != QUEUED
}