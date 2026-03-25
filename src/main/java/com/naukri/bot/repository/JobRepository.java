package com.naukri.bot.repository;


import com.naukri.bot.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.naukri.bot.model.Job.ApplicationStatus;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    boolean existsByJobId(String jobId);

    Optional<Job> findByJobId(String jobId);

    List<Job> findByStatus(ApplicationStatus status);

    List<Job> findByStatusOrderByScoreDesc(ApplicationStatus status);

    long countByStatus(ApplicationStatus status);
}