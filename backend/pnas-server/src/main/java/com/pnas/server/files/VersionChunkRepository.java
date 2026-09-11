package com.pnas.server.files;

import com.pnas.server.files.domain.VersionChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VersionChunkRepository extends JpaRepository<VersionChunk, UUID> {
    List<VersionChunk> findAllByVersionIdOrderBySeqAsc(UUID versionId);
}
