package com.naukri.bot.service;

import com.naukri.bot.model.Job;
import com.naukri.bot.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobStorageService {

    private final JobRepository jobRepository;

    /**
     * Saves a list of scraped jobs, skipping any already in DB.
     * Returns only the newly saved jobs.
     */
    public List<Job> saveNewJobs(List<Job> scraped) {

        List<Job> saved = new ArrayList<>();
        int skipped = 0;

        for (Job job : scraped) {

            if (job.getJobId() == null || job.getJobId().isBlank()) {
                log.warn("⚠️ Skipping job with no ID: {}", job.getTitle());
                skipped++;
                continue;
            }

            if (jobRepository.existsByJobId(job.getJobId())) {
                log.debug("↩ Duplicate skipped: [{}] {}", job.getJobId(), job.getTitle());
                skipped++;
                continue;
            }

            if (job.getJobDescription() == null || job.getJobDescription().isBlank()) {
                log.warn("⚠️ Skipping job with empty JD: {}", job.getTitle());
                skipped++;
                continue;
            }

            try {
                jobRepository.save(job);
                saved.add(job);
                log.info("💾 Saved: [{}] {} @ {}", job.getJobId(), job.getTitle(), job.getCompany());
            } catch (Exception e) {
                log.error("❌ DB save failed for {}: {}", job.getTitle(), e.getMessage());
            }
        }

        log.info("📊 Batch done — saved: {}, skipped (dup/invalid): {}", saved.size(), skipped);
        return saved;
    }

    public long totalJobsInDb() {
        return jobRepository.count();
    }

    public long totalByStatus(Job.ApplicationStatus status) {
        return jobRepository.countByStatus(status);
    }
}