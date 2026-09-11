package com.pnas.server.job;

import com.pnas.server.job.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
    /** 领取到期任务:QUEUED(首次)与 FAILED(重试)都按 nextRunAt 调度。 */
    List<Job> findTop20ByStateInAndNextRunAtLessThanEqualOrderByPriorityDescNextRunAtAsc(
        Collection<String> states, Instant at);

    List<Job> findByStateOrderByNextRunAtAsc(String state);

    List<Job> findAllByOrderByNextRunAtDesc();
}
