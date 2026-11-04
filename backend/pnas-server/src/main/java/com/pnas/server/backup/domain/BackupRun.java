package com.pnas.server.backup.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "backup_runs")
@Getter @NoArgsConstructor
public class BackupRun {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_id")
    private Device device;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private User user;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RunMode mode;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RunStatus status = RunStatus.RUNNING;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "files_seen", nullable = false) private int filesSeen;
    @Column(name = "files_uploaded", nullable = false) private int filesUploaded;
    @Column(name = "files_skipped", nullable = false) private int filesSkipped;
    @Column(name = "files_failed", nullable = false) private int filesFailed;
    @Column(name = "bytes_uploaded", nullable = false) private long bytesUploaded;
    @Column private String error;

    public static BackupRun start(Device device, User user, RunMode mode) {
        BackupRun r = new BackupRun();
        r.id = UUID.randomUUID();
        r.device = device;
        r.user = user;
        r.mode = mode;
        r.startedAt = Instant.now();
        return r;
    }
    public void finish(RunStatus status, int seen, int uploaded, int skipped, int failed, long bytes, String error) {
        this.status = status;
        this.filesSeen = seen;
        this.filesUploaded = uploaded;
        this.filesSkipped = skipped;
        this.filesFailed = failed;
        this.bytesUploaded = bytes;
        this.error = error;
        this.finishedAt = Instant.now();
    }
}
