package com.pnas.server.files;

import com.pnas.server.files.domain.VersionChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VersionChunkRepository extends JpaRepository<VersionChunk, UUID> {
    List<VersionChunk> findAllByVersionIdOrderBySeqAsc(UUID versionId);

    /** 备份清单:首块哈希。 */
    Optional<VersionChunk> findFirstByVersionIdOrderBySeqAsc(UUID versionId);

    /** 备份清单:末块哈希。 */
    Optional<VersionChunk> findFirstByVersionIdOrderBySeqDesc(UUID versionId);
}
