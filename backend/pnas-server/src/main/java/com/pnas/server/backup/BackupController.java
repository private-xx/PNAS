package com.pnas.server.backup;

import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.BackupRun;
import com.pnas.server.backup.domain.RunMode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 备份运行记录 API(FR-BACKUP-03/04)。 */
@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    public record StartRun(UUID deviceId, RunMode mode) {}

    public record RunDto(UUID id, UUID deviceId, String mode, String status,
                         Instant startedAt, Instant finishedAt,
                         int filesSeen, int filesUploaded, int filesSkipped, int filesFailed,
                         long bytesUploaded, String error) {}

    private final BackupRunService runs;

    public BackupController(BackupRunService runs) {
        this.runs = runs;
    }

    @PostMapping("/runs")
    public RunDto start(@AuthenticationPrincipal SessionPrincipal me, @RequestBody StartRun req) {
        return toDto(runs.start(me, req.deviceId(), req.mode()));
    }

    @PatchMapping("/runs/{id}")
    public RunDto finish(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id,
                         @RequestBody BackupRunService.Finish finish) {
        return toDto(runs.finish(me, id, finish));
    }

    @GetMapping("/runs")
    public List<RunDto> list(@AuthenticationPrincipal SessionPrincipal me,
                             @RequestParam(required = false) UUID deviceId,
                             @RequestParam(defaultValue = "50") int limit) {
        return runs.list(me, deviceId, limit).stream().map(this::toDto).toList();
    }

    private RunDto toDto(BackupRun r) {
        return new RunDto(r.getId(), r.getDevice().getId(), r.getMode().name(), r.getStatus().name(),
            r.getStartedAt(), r.getFinishedAt(), r.getFilesSeen(), r.getFilesUploaded(),
            r.getFilesSkipped(), r.getFilesFailed(), r.getBytesUploaded(), r.getError());
    }
}
