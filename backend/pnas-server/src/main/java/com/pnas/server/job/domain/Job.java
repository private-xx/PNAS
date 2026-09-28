package com.pnas.server.job.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 任务队列条目(架构 ADR-09:用 DB 表做队列,不引入独立 MQ)。
 * 状态机:QUEUED → RUNNING →(SUCCESS | FAILED 重试 → DEAD)。
 */
@Entity @Table(name = "jobs")
@Getter @Setter @NoArgsConstructor
public class Job {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String DEAD = "DEAD";

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(nullable = false)
    private String state = QUEUED;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(nullable = false)
    private int priority;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> result;

    public void markRunning(Instant now) {
        this.state = RUNNING;
        this.startedAt = now;
        this.attempt++;
        this.error = null;
    }

    public void markSuccess(Map<String, Object> result, Instant now) {
        this.state = SUCCESS;
        this.result = result;
        this.finishedAt = now;
    }

    /** 未达最大尝试次数 → 进入 FAILED 并设置退避时间(调度器会再次领取);否则入 DEAD。 */
    public void markFailed(String error, Instant now, Instant retryAt) {
        this.error = error;
        if (this.attempt >= this.maxAttempts) {
            this.state = DEAD;
            this.finishedAt = now;
        } else {
            this.state = FAILED;
            this.nextRunAt = retryAt;
        }
    }

    public void markDead(String error, Instant now) {
        this.state = DEAD;
        this.error = error;
        this.finishedAt = now;
    }
}
