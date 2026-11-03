package com.pnas.server.backup;

import com.pnas.server.backup.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    Optional<Device> findByPatHashAndRevokedAtIsNull(String patHash);
    List<Device> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Device> findByIdAndUserId(UUID id, UUID userId);
}
