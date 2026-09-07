package com.pnas.server.files;

import com.pnas.server.files.domain.FileVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FileVersionRepository extends JpaRepository<FileVersion, UUID> {
    Optional<FileVersion> findTopByNodeIdOrderByVersionNoDesc(UUID nodeId);
}
