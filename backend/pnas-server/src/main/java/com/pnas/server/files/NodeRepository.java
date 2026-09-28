package com.pnas.server.files;

import com.pnas.server.files.domain.Node;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NodeRepository extends JpaRepository<Node, UUID> {
    Optional<Node> findByParentIdAndNameAndTrashedAtIsNull(UUID parentId, String name);

    Optional<Node> findByParentIdIsNullAndOwnerIdAndTrashedAtIsNull(UUID ownerId);

    /** 列目录(排除回收站)。 */
    List<Node> findByParentIdAndTrashedAtIsNullOrderByNameAsc(UUID parentId);

    /** 回收站列表(本人被删除的节点,最近删除在前)。 */
    List<Node> findByOwnerIdAndTrashedAtIsNotNullOrderByTrashedAtDesc(UUID ownerId);

    boolean existsByParentIdAndNameAndTrashedAtIsNull(UUID parentId, String name);
}
