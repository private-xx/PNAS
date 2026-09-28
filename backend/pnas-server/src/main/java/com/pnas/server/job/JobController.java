package com.pnas.server.job;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.job.domain.Job;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 任务管理接口(FR-SYS-01):仅 ADMIN 可见/可操作。 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public List<Job> list(@AuthenticationPrincipal SessionPrincipal me,
                          @RequestParam(required = false) String state) {
        requireAdmin(me);
        return jobs.list(state);
    }

    @PostMapping("/{id}/retry")
    public Job retry(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id) {
        requireAdmin(me);
        jobs.retry(id);
        return jobs.get(id).orElseThrow();
    }

    @PostMapping("/{id}/cancel")
    public Job cancel(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id) {
        requireAdmin(me);
        jobs.cancel(id);
        return jobs.get(id).orElseThrow();
    }

    private void requireAdmin(SessionPrincipal me) {
        if (!me.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "仅管理员可管理任务");
        }
    }
}
