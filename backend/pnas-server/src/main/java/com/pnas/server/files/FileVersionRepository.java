package com.pnas.server.files;

import com.pnas.server.files.domain.FileVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileVersionRepository extends JpaRepository<FileVersion, UUID> {
    Optional<FileVersion> findTopByNodeIdOrderByVersionNoDesc(UUID nodeId);

    List<FileVersion> findAllByNodeIdOrderByVersionNoDesc(UUID nodeId);

    Optional<FileVersion> findByNodeIdAndVersionNo(UUID nodeId, int versionNo);

    /** 去重判定:同样内容(清单哈希)是否已存在于仓库。 */
    boolean existsByManifestSha256(String manifestSha256);
}
