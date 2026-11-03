package com.pnas.server.backup;

import com.pnas.server.backup.domain.BackupRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BackupRunRepository extends JpaRepository<BackupRun, UUID> {
    List<BackupRun> findByDeviceIdOrderByStartedAtDesc(UUID deviceId, Pageable pageable);
    List<BackupRun> findByUserIdOrderByStartedAtDesc(UUID userId, Pageable pageable);
}
