package com.pnas.server.backup;

import com.pnas.server.backup.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    Optional<Device> findByPatHashAndRevokedAtIsNull(String patHash);
    List<Device> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Device> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 定向刷新 lastSeenAt:仅当设备未吊销且 lastSeenAt 为空或早于 {@code cutoff} 时更新。
     * 用单条 UPDATE 取代读-判断-写,避免与并发吊销竞争时把已吊销设备"复活"。
     */
    @Transactional
    @Modifying
    @Query("update Device d set d.lastSeenAt = :now " +
        "where d.id = :id and d.revokedAt is null and (d.lastSeenAt is null or d.lastSeenAt < :cutoff)")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
