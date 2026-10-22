package com.pnas.server.media;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.domain.Node;
import com.pnas.server.iam.AclRepository;
import com.pnas.server.iam.AclService;
import com.pnas.server.iam.GroupRepository;
import com.pnas.server.iam.domain.AclEntry;
import com.pnas.server.media.domain.MediaAsset;
import com.pnas.server.media.domain.MediaKind;
import com.pnas.server.media.domain.ThumbStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 时间线读取:可见性由"我的 home + 显式授权我/我所在组的节点"向下递归展开决定(spec §5)。
 *
 * <p>本类直接注入 {@link AclService},而不是经 {@code FilesService} 转发:
 * files 已依赖 iam,若再加 iam→files 的反向调用会形成包环(ArchUnit 守护)。</p>
 */
@Service
public class MediaTimelineService {

    public record TimelineItem(UUID assetId, UUID nodeId, String name, MediaKind kind, Instant takenAt,
                               Integer width, Integer height, Long durationMs, ThumbStatus thumbStatus,
                               long sizeBytes) {}

    public record PeriodCount(String period, long count) {}

    public record YearMonths(int year, List<PeriodCount> months) {}

    private static final int MAX_LIMIT = 200;
    /** 原生查询以空串表示"不过滤 kind",避免可空参数在 PostgreSQL 下的类型推断问题(R6)。 */
    private static final String ANY_KIND = "";

    private final MediaAssetRepository assets;
    private final AclRepository acls;
    private final GroupRepository groups;
    private final NodeRepository nodes;
    private final AclService acl;

    public MediaTimelineService(MediaAssetRepository assets, AclRepository acls, GroupRepository groups,
                                NodeRepository nodes, AclService acl) {
        this.assets = assets;
        this.acls = acls;
        this.groups = groups;
        this.nodes = nodes;
        this.acl = acl;
    }

    /** 游标分页:返回按 taken_at 倒序的一页;cursor 为空表示第一页。 */
    @Transactional(readOnly = true)
    public List<TimelineItem> timeline(SessionPrincipal me, String cursor, int limit, MediaKind kindFilter) {
        int cap = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Cursor parsed = parseCursor(cursor);
        Set<UUID> roots = readableRoots(me);
        if (roots.isEmpty()) {
            return List.of();
        }
        String kind = kindParam(kindFilter);
        List<Object[]> rows = parsed == null
            ? assets.timelinePageFirst(roots, kind, cap)
            : assets.timelinePageAfter(roots, kind, parsed.takenAt().toString(),
                parsed.assetId().toString(), cap);
        List<TimelineItem> items = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            items.add(new TimelineItem((UUID) r[0], (UUID) r[1], (String) r[2],
                MediaKind.valueOf((String) r[3]), toInstant(r[4]),
                (Integer) r[5], (Integer) r[6],
                r[7] == null ? null : ((Number) r[7]).longValue(),
                ThumbStatus.valueOf((String) r[8]), ((Number) r[9]).longValue()));
        }
        return items;
    }

    /** 某一年的月份桶;只统计我可读且未进回收站的媒体。 */
    @Transactional(readOnly = true)
    public List<PeriodCount> buckets(SessionPrincipal me, int year, MediaKind kindFilter) {
        Set<UUID> roots = readableRoots(me);
        if (roots.isEmpty()) {
            return List.of();
        }
        return toPeriodCounts(assets.bucketCountsForYear(roots, year, kindParam(kindFilter)));
    }

    /** 日期相册视图:年 → 月桶(按月倒序、年倒序)。 */
    @Transactional(readOnly = true)
    public List<YearMonths> albumsDates(SessionPrincipal me) {
        Set<UUID> roots = readableRoots(me);
        if (roots.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<PeriodCount>> byYear = new LinkedHashMap<>();
        for (PeriodCount c : toPeriodCounts(assets.bucketCountsAll(roots, ANY_KIND))) {
            int year = Integer.parseInt(c.period().substring(0, 4));
            byYear.computeIfAbsent(year, y -> new ArrayList<>()).add(c);
        }
        List<YearMonths> out = new ArrayList<>(byYear.size());
        byYear.forEach((year, months) -> out.add(new YearMonths(year, List.copyOf(months))));
        return out;
    }

    /** 单条读取:不存在、已回收、无读权限一律 404(不泄露存在性)。 */
    @Transactional(readOnly = true)
    public MediaAsset requireReadable(SessionPrincipal me, UUID assetId) {
        MediaAsset asset = assets.findById(assetId).orElseThrow(MediaTimelineService::notFound);
        Node node = nodes.findById(asset.getNodeId()).orElseThrow(MediaTimelineService::notFound);
        // 关键:不能只查节点自身的 trashed_at。M2 删除目录是逐节点的,子节点仍为 null,
        // 若不沿父链判断,回收站目录下的媒体仍会经 /thumb、/content 被读出来。
        if (inTrashedSubtree(node) || !acl.hasPermission(me, node.getId(), 'r')) {
            throw notFound();
        }
        return asset;
    }

    /**
     * 我可读节点根集合(ADMIN 例外:全部未删除的顶层节点,与 {@code requireReadable} 的
     * ADMIN 短路保持一致;非 ADMIN 为 home + 显式授予我/我所在组的节点,仅含 r 且不含拒绝 -)。
     *
     * <p><b>已知边界(M3 接受,DENY 边界):</b>ACL 根是"授予即可见"的近似——这里不按
     * {@link AclService#hasPermission} 的语义重放 ancestor DENY / {@code inherited=false};
     * 若某条目因祖先显式拒绝而实际不可读,它会出现在时间线里,但单条 {@link #requireReadable}
     * 会返回 404。极少数"列表可见、打开 404"的组合接受为 M3 限制,待 M5 共享 UI 落地后统一。</p>
     */
    Set<UUID> readableRoots(SessionPrincipal me) {
        Set<UUID> roots = new LinkedHashSet<>();
        if (me.isAdmin()) {
            nodes.findByParentIdIsNullAndTrashedAtIsNull().forEach(n -> roots.add(n.getId()));
            return roots;
        }
        nodes.findByParentIdIsNullAndOwnerIdAndTrashedAtIsNull(me.userId())
            .map(Node::getId)
            .ifPresent(roots::add);
        acls.findByPrincipalTypeAndPrincipalId(AclEntry.PrincipalType.USER, me.userId()).stream()
            .filter(MediaTimelineService::isReadableAclRoot)
            .forEach(e -> roots.add(e.getNode().getId()));
        for (UUID groupId : groups.findGroupIdsByUserId(me.userId())) {
            acls.findByPrincipalTypeAndPrincipalId(AclEntry.PrincipalType.GROUP, groupId).stream()
                .filter(MediaTimelineService::isReadableAclRoot)
                .forEach(e -> roots.add(e.getNode().getId()));
        }
        return roots;
    }

    // ---------------------------------------------------------------- 内部

    private record Cursor(Instant takenAt, UUID assetId) {}

    private static Cursor parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\|", 2);
        if (parts.length != 2) {
            throw badCursor();
        }
        try {
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw badCursor();
        }
    }

    private static BusinessException badCursor() {
        return new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
            "cursor 格式不合法,应为 <ISO-8601 时间>|<assetId>");
    }

    private static String kindParam(MediaKind kind) {
        return kind == null ? ANY_KIND : kind.name();
    }

    private static List<PeriodCount> toPeriodCounts(List<Object[]> rows) {
        List<PeriodCount> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new PeriodCount((String) r[0], ((Number) r[1]).longValue()));
        }
        return out;
    }

    /** timestamptz 的 JDBC 表示可能是 Timestamp 或 OffsetDateTime,两者都归一为 Instant。 */
    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException("无法解析 taken_at: " + value);
    }

    /** 仅"含 r、不含显式拒绝 -、且祖先链未进回收站"的 ACL 条目可作为可见性根。 */
    private static boolean isReadableAclRoot(AclEntry e) {
        return grantsRead(e) && !inTrashedSubtree(e.getNode());
    }

    /** 仅"含 r 且不含显式拒绝 -"的条目可作为可见性根(祖先回收站状态另判)。 */
    private static boolean grantsRead(AclEntry e) {
        return e.getPerms().indexOf('r') >= 0 && e.getPerms().indexOf('-') < 0;
    }

    /** 节点自身或任一祖先在回收站中(M2 回收站逐节点,删除目录不会级联标记子节点)。 */
    private static boolean inTrashedSubtree(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current.getTrashedAt() != null) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "媒体不存在");
    }
}
