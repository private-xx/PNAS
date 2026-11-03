package com.pnas.server.backup;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.VersionChunkRepository;
import com.pnas.server.files.domain.Node;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 备份清单:每次调用按设备返回备份根下当前版本的相对路径、大小、源 mtime 与首末块哈希。
 *
 * <p>清单根 {@code /我的文件/<user>/自动备份/<deviceId>/} 在每次调用时幂等创建;
 * 条目按 relPath 升序,游标分页(limit 1..200)。</p>
 */
@Service
public class ManifestService {

    public record Entry(String relPath, UUID nodeId, long size, Instant sourceMtime,
                        String firstChunkSha, String lastChunkSha, String manifestSha256,
                        int versionNo) {}

    public record Page(UUID rootNodeId, List<Entry> items, String nextCursor) {}

    private final DeviceRepository devices;
    private final FilesService files;
    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final VersionChunkRepository chunks;

    public ManifestService(DeviceRepository devices, FilesService files, NodeRepository nodes,
                           FileVersionRepository versions, VersionChunkRepository chunks) {
        this.devices = devices;
        this.files = files;
        this.nodes = nodes;
        this.versions = versions;
        this.chunks = chunks;
    }

    @Transactional
    public Page page(SessionPrincipal me, UUID deviceId, String cursor, int limit) {
        Device device = devices.findByIdAndUserId(deviceId, me.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "设备不存在"));
        int cap = Math.min(Math.max(limit, 1), 200);

        Node backup = ensureDir(me, files.homeOf(me), "自动备份");
        Node root = ensureDir(me, backup, device.getId().toString());

        List<Entry> all = new ArrayList<>();
        walk(root, "", all);
        all.sort(Comparator.comparing(Entry::relPath));
        if (cursor != null && !cursor.isBlank()) {
            all = all.stream().filter(e -> e.relPath().compareTo(cursor) > 0).toList();
        }
        List<Entry> page = all.stream().limit(cap).toList();
        String next = all.size() > cap ? page.get(page.size() - 1).relPath() : null;
        return new Page(root.getId(), page, next);
    }

    /** 目录不存在则创建;重复调用返回既有目录(幂等)。 */
    private Node ensureDir(SessionPrincipal me, Node parent, String name) {
        return nodes.findByParentIdAndNameAndTrashedAtIsNull(parent.getId(), name)
            .orElseGet(() -> nodes.save(Node.dir(files.homeOf(me).getOwner(), parent, name)));
    }

    private void walk(Node dir, String prefix, List<Entry> out) {
        for (Node child : nodes.findByParentIdAndTrashedAtIsNullOrderByNameAsc(dir.getId())) {
            String rel = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.getKind() == Node.Kind.DIR) {
                walk(child, rel, out);
                continue;
            }
            var version = versions.findTopByNodeIdOrderByVersionNoDesc(child.getId()).orElse(null);
            if (version == null) {
                continue;
            }
            String first = chunks.findFirstByVersionIdOrderBySeqAsc(version.getId())
                .map(c -> c.getBlobHash()).orElse(null);
            String last = chunks.findFirstByVersionIdOrderBySeqDesc(version.getId())
                .map(c -> c.getBlobHash()).orElse(null);
            out.add(new Entry(rel, child.getId(), version.getSizeBytes(),
                version.getSourceMtime(), first, last, version.getManifestSha256(),
                version.getVersionNo()));
        }
    }
}
