package com.pnas.server.job;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.job.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DB 任务队列(ADR-09)。调度器周期性调用 {@link #runDue()};测试用 {@link #claimAndRunAllNow()} 同步执行。
 * 单任务在**同一事务**内 claim→执行→落结果(M2 任务均为短任务;长任务拆分留待后续里程碑)。
 */
@Service
public class JobService {

    private static final int BACKOFF_SECONDS = 30;
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final Map<String, JobHandler> handlers = new LinkedHashMap<>();

    public JobService(JobRepository jobs, List<JobHandler> handlerList) {
        this.jobs = jobs;
        for (JobHandler h : handlerList) {
            handlers.put(h.type(), h);
        }
    }

    @Transactional
    public UUID enqueue(String type, Map<String, Object> payload) {
        return enqueue(type, payload, 0, 0);
    }

    /** 入队(可指定优先级与延迟执行秒数;优先级越大越先执行)。 */
    @Transactional
    public UUID enqueue(String type, Map<String, Object> payload, int priority, long delaySeconds) {
        if (!handlers.containsKey(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "未注册的任务类型: " + type);
        }
        var job = new Job();
        job.setType(type);
        job.setPayload(payload == null ? Map.of() : payload);
        job.setPriority(priority);
        job.setNextRunAt(Instant.now().plusSeconds(Math.max(0, delaySeconds)));
        return jobs.save(job).getId();
    }

    /** 执行一批到期任务(QUEUED 首发 + FAILED 重试);由调度器或测试钩子调用。 */
    public int runDue() {
        List<Job> due = jobs.findTop20ByStateInAndNextRunAtLessThanEqualOrderByPriorityDescNextRunAtAsc(
            List.of(Job.QUEUED, Job.FAILED), Instant.now());
        int executed = 0;
        for (Job job : due) {
            execute(job);
            executed++;
        }
        return executed;
    }

    /** 测试钩子:同步跑一批到期任务(等价于调度器一轮)。 */
    public int claimAndRunAllNow() {
        return runDue();
    }

    /** 测试钩子:忽略退避时间,立即执行指定任务一次。 */
    @Transactional
    public void forceRun(UUID id) {
        Job job = require(id);
        job.setNextRunAt(Instant.now());
        jobs.save(job);
        execute(job);
    }

    @Transactional
    public void retry(UUID id) {
        Job job = require(id);
        if (!Job.DEAD.equals(job.getState()) && !Job.FAILED.equals(job.getState())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT,
                "只有 FAILED/DEAD 任务可以重试,当前状态: " + job.getState());
        }
        job.setState(Job.QUEUED);
        job.setAttempt(0);
        job.setError(null);
        job.setNextRunAt(Instant.now());
        job.setFinishedAt(null);
    }

    @Transactional
    public void cancel(UUID id) {
        Job job = require(id);
        if (Job.SUCCESS.equals(job.getState())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "已完成的任务不能取消");
        }
        job.markDead("cancelled by admin", Instant.now());
    }

    @Transactional(readOnly = true)
    public Optional<Job> get(UUID id) {
        return jobs.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Job> list(String state) {
        return state == null || state.isBlank()
            ? jobs.findAllByOrderByNextRunAtDesc()
            : jobs.findByStateOrderByNextRunAtAsc(state.toUpperCase());
    }

    private void execute(Job job) {
        JobHandler handler = handlers.get(job.getType());
        if (handler == null) {
            job.markDead("未注册的任务类型: " + job.getType(), Instant.now());
            jobs.save(job);
            return;
        }
        job.markRunning(Instant.now());
        try {
            Map<String, Object> result = handler.run(job);
            job.markSuccess(result, Instant.now());
        } catch (Exception e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("任务失败 id={} type={} attempt={}/{}: {}", job.getId(), job.getType(),
                job.getAttempt(), job.getMaxAttempts(), message, e);
            job.markFailed(message, Instant.now(),
                Instant.now().plusSeconds((long) BACKOFF_SECONDS * Math.max(1, job.getAttempt())));
        }
        jobs.save(job);
    }

    private Job require(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "任务不存在: " + id));
    }
}
