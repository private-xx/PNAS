package com.pnas.server.files;

import com.pnas.server.files.domain.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NodeRepository extends JpaRepository<Node, UUID> {
    Optional<Node> findByParentIdAndNameAndTrashedAtIsNull(UUID parentId, String name);

    /**
     * 备份根并发首建:原子插入目录,已存在(未删除)则什么都不做。
     * 冲突目标与 {@code ux_nodes_parent_name} 的部分唯一索引谓词一致。
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "insert into nodes(id, parent_id, owner_id, kind, name, size_bytes) " +
        "values (gen_random_uuid(), :parentId, :ownerId, 'DIR', :name, 0) " +
        "on conflict (parent_id, name) where trashed_at is null do nothing", nativeQuery = true)
    int insertDirIfAbsent(@Param("parentId") UUID parentId, @Param("ownerId") UUID ownerId,
                          @Param("name") String name);

    Optional<Node> findByParentIdIsNullAndOwnerIdAndTrashedAtIsNull(UUID ownerId);

    /** 全部未删除的顶层节点(ADMIN 时间线可见性根:每个人的 home 都是顶层)。 */
    List<Node> findByParentIdIsNullAndTrashedAtIsNull();

    /** 列目录(排除回收站)。 */
    List<Node> findByParentIdAndTrashedAtIsNullOrderByNameAsc(UUID parentId);

    /** 回收站列表(本人被删除的节点,最近删除在前)。 */
    List<Node> findByOwnerIdAndTrashedAtIsNotNullOrderByTrashedAtDesc(UUID ownerId);

    boolean existsByParentIdAndNameAndTrashedAtIsNull(UUID parentId, String name);

    /** 重建索引用:按 id 顺序分页遍历全部节点。 */
    List<Node> findTop200ByOrderByIdAsc();

    List<Node> findTop200ByIdGreaterThanOrderByIdAsc(UUID id);
}
