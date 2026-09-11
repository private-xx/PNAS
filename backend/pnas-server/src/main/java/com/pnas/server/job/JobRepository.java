package com.pnas.server.job;

import com.pnas.server.job.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
    List<Job> findTop20ByStateAndNextRunAtLessThanEqualOrderByPriorityDescNextRunAtAsc(String state, Instant at);

    List<Job> findByStateOrderByNextRunAtAsc(String state);

    List<Job> findAllByOrderByNextRunAtDesc();
}
