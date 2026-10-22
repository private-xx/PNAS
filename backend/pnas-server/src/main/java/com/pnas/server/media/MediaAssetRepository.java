package com.pnas.server.media;

import com.pnas.server.media.domain.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 媒体索引读写。时间线的可见性由"可读根节点"递归展开后在 SQL 内过滤(spec §5),
 * 游标为 {@code (taken_at, id)} 倒序元组,避免 offset 深分页。
 *
 * <p>所有原生查询的 {@code kind} 参数都**非空**:空串表示不过滤。这样既避免
 * 可空参数在 PostgreSQL 下的类型推断问题,也不必为"带/不带 kind"各写一份 SQL。</p>
 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {
    Optional<MediaAsset> findByFileVersionId(UUID fileVersionId);

    /** 时间线第一页:递归展开可读子树后按 (taken_at, id) 倒序取一页。 */
    @Query(value = """
        WITH RECURSIVE readable AS (
            SELECT n.id FROM nodes n WHERE n.id IN (:rootIds)
            UNION
            SELECT c.id FROM nodes c JOIN readable r ON c.parent_id = r.id
        )
        SELECT ma.id, ma.node_id, n.name, ma.kind, ma.taken_at, ma.width, ma.height,
               ma.duration_ms, ma.thumb_status, v.size_bytes
        FROM media_assets ma
        JOIN readable r ON r.id = ma.node_id
        JOIN nodes n ON n.id = ma.node_id
        JOIN file_versions v ON v.id = ma.file_version_id
        WHERE n.trashed_at IS NULL
          AND (CAST(:kind AS text) = '' OR ma.kind = CAST(:kind AS text))
        ORDER BY ma.taken_at DESC, ma.id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> timelinePageFirst(@Param("rootIds") Collection<UUID> rootIds,
                                     @Param("kind") String kind,
                                     @Param("limit") int limit);

    /** 时间线后续页:游标元组 {@code (taken_at, id)} 严格小于上一页最后一条。 */
    @Query(value = """
        WITH RECURSIVE readable AS (
            SELECT n.id FROM nodes n WHERE n.id IN (:rootIds)
            UNION
            SELECT c.id FROM nodes c JOIN readable r ON c.parent_id = r.id
        )
        SELECT ma.id, ma.node_id, n.name, ma.kind, ma.taken_at, ma.width, ma.height,
               ma.duration_ms, ma.thumb_status, v.size_bytes
        FROM media_assets ma
        JOIN readable r ON r.id = ma.node_id
        JOIN nodes n ON n.id = ma.node_id
        JOIN file_versions v ON v.id = ma.file_version_id
        WHERE n.trashed_at IS NULL
          AND (CAST(:kind AS text) = '' OR ma.kind = CAST(:kind AS text))
          AND (ma.taken_at, ma.id) < (CAST(:cursorTakenAt AS timestamptz), CAST(:cursorId AS uuid))
        ORDER BY ma.taken_at DESC, ma.id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> timelinePageAfter(@Param("rootIds") Collection<UUID> rootIds,
                                     @Param("kind") String kind,
                                     @Param("cursorTakenAt") String cursorTakenAt,
                                     @Param("cursorId") String cursorId,
                                     @Param("limit") int limit);

    /** 某一年的月份桶计数(受可读子树与回收站约束)。 */
    @Query(value = """
        WITH RECURSIVE readable AS (
            SELECT n.id FROM nodes n WHERE n.id IN (:rootIds)
            UNION
            SELECT c.id FROM nodes c JOIN readable r ON c.parent_id = r.id
        )
        SELECT to_char(ma.taken_at, 'YYYY-MM') AS period, count(*) AS cnt
        FROM media_assets ma
        JOIN readable r ON r.id = ma.node_id
        JOIN nodes n ON n.id = ma.node_id
        WHERE n.trashed_at IS NULL
          AND date_part('year', ma.taken_at) = :year
          AND (CAST(:kind AS text) = '' OR ma.kind = CAST(:kind AS text))
        GROUP BY period
        ORDER BY period DESC
        """, nativeQuery = true)
    List<Object[]> bucketCountsForYear(@Param("rootIds") Collection<UUID> rootIds,
                                       @Param("year") int year,
                                       @Param("kind") String kind);

    /** 全部年份的月份桶计数(日期相册视图)。 */
    @Query(value = """
        WITH RECURSIVE readable AS (
            SELECT n.id FROM nodes n WHERE n.id IN (:rootIds)
            UNION
            SELECT c.id FROM nodes c JOIN readable r ON c.parent_id = r.id
        )
        SELECT to_char(ma.taken_at, 'YYYY-MM') AS period, count(*) AS cnt
        FROM media_assets ma
        JOIN readable r ON r.id = ma.node_id
        JOIN nodes n ON n.id = ma.node_id
        WHERE n.trashed_at IS NULL
          AND (CAST(:kind AS text) = '' OR ma.kind = CAST(:kind AS text))
        GROUP BY period
        ORDER BY period DESC
        """, nativeQuery = true)
    List<Object[]> bucketCountsAll(@Param("rootIds") Collection<UUID> rootIds,
                                   @Param("kind") String kind);
}
