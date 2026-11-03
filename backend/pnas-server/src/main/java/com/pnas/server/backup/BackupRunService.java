package com.pnas.server.backup;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.BackupRun;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.backup.domain.RunMode;
import com.pnas.server.backup.domain.RunStatus;
import com.pnas.server.common.error.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 备份运行记录:开始、结束与列表查询(按设备或按用户隔离)。 */
@Service
public class BackupRunService {

    public record Finish(RunStatus status, int filesSeen, int filesUploaded, int filesSkipped,
                         int filesFailed, long bytesUploaded, String error) {}

    private final BackupRunRepository runs;
    private final DeviceRepository devices;

    public BackupRunService(BackupRunRepository runs, DeviceRepository devices) {
        this.runs = runs;
        this.devices = devices;
    }

    @Transactional
    public BackupRun start(SessionPrincipal me, UUID deviceId, RunMode mode) {
        if (deviceId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "deviceId 不能为空");
        }
        Device device = devices.findByIdAndUserId(deviceId, me.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "设备不存在"));
        if (mode == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "mode 不能为空");
        }
        return runs.save(BackupRun.start(device, device.getUser(), mode));
    }

    @Transactional
    public BackupRun finish(SessionPrincipal me, UUID runId, Finish f) {
        BackupRun run = runs.findById(runId)
            .filter(r -> r.getUser().getId().equals(me.userId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "运行记录不存在"));
        if (run.getStatus() != RunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "运行已结束,不能重复提交");
        }
        if (f.status() == null || f.status() == RunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "status 必须是 SUCCESS/PARTIAL/FAILED");
        }
        run.finish(f.status(), f.filesSeen(), f.filesUploaded(), f.filesSkipped(),
            f.filesFailed(), f.bytesUploaded(), f.error());
        return runs.save(run);
    }

    @Transactional(readOnly = true)
    public List<BackupRun> list(SessionPrincipal me, UUID deviceId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        if (deviceId != null) {
            devices.findByIdAndUserId(deviceId, me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                    "设备不存在"));
            return runs.findByDeviceIdOrderByStartedAtDesc(deviceId, PageRequest.of(0, cap));
        }
        return runs.findByUserIdOrderByStartedAtDesc(me.userId(), PageRequest.of(0, cap));
    }
}
