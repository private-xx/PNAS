package com.pnas.server.files;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.blobstore.BlobStore;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.domain.FileVersion;
import com.pnas.server.files.domain.Node;
import com.pnas.server.files.domain.VersionChunk;
import com.pnas.server.iam.AclService;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 节点树与文件内容编排。所有对外操作都在**服务端**先做 ACL 判定(FR-AUTH-06 / NFR-SEC-03)。
 */
@Service
public class FilesService {

    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final VersionChunkRepository chunks;
    private final BlobStore blobs;
    private final AclService acl;
    private final UserRepository users;

    public FilesService(NodeRepository nodes, FileVersionRepository versions,
                        VersionChunkRepository chunks, BlobStore blobs,
                        AclService acl, UserRepository users) {
        this.nodes = nodes;
        this.versions = versions;
        this.chunks = chunks;
        this.blobs = blobs;
        this.acl = acl;
        this.users = users;
    }

    public record NodeDto(UUID id, UUID parentId, String name, String kind, long sizeBytes,
                          Instant updatedAt, Instant trashedAt) {}

    public record VersionDto(int versionNo, long sizeBytes, String mimeType,
                             String manifestSha256, Instant createdAt) {}

    public record ContentStream(InputStream stream, long totalSize, long start, long length) {}

    // ---------------------------------------------------------------- 查询

    /** 每个用户一个私有根(父节点为 null,名称:我的文件-<username>)。 */
    @Transactional
    public Node ensureUserHome(User owner) {
        return nodes.findByParentIdIsNullAndOwnerIdAndTrashedAtIsNull(owner.getId())
            .orElseGet(() -> nodes.save(Node.dir(owner, null, "我的文件-" + owner.getUsername())));
    }

    @Transactional
    public Node homeOf(SessionPrincipal me) {
        User user = users.findById(me.userId()).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在"));
        return ensureUserHome(user);
    }

    /** 列出目录内容;parentId 为空时返回调用者私有根的内容。 */
    @Transactional
    public List<NodeDto> list(SessionPrincipal me, UUID parentId) {
        Node parent = parentId == null ? homeOf(me) : requireNode(parentId);
        require(parent, me, 'r');
        return nodes.findByParentIdAndTrashedAtIsNullOrderByNameAsc(parent.getId())
            .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<NodeDto> listTrash(SessionPrincipal me) {
        return nodes.findByOwnerIdAndTrashedAtIsNotNullOrderByTrashedAtDesc(me.userId())
            .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<VersionDto> listVersions(SessionPrincipal me, UUID nodeId) {
        Node node = requireFile(nodeId);
        require(node, me, 'r');
        return versions.findAllByNodeIdOrderByVersionNoDesc(nodeId).stream()
            .map(v -> new VersionDto(v.getVersionNo(), v.getSizeBytes(), v.getMimeType(),
                v.getManifestSha256(), v.getCreatedAt()))
            .toList();
    }

    // ---------------------------------------------------------------- 变更

    @Transactional
    public NodeDto createDir(SessionPrincipal me, UUID parentId, String name) {
        Node parent = parentId == null ? homeOf(me) : requireNode(parentId);
        require(parent, me, 'w');
        if (parent.getKind() != Node.Kind.DIR) {
            throw new BusinessException(ErrorCode.PARENT_NOT_DIRECTORY,
                HttpStatus.BAD_REQUEST, "父节点不是目录");
        }
        if (nodes.existsByParentIdAndNameAndTrashedAtIsNull(parent.getId(), name)) {
            throw new BusinessException(ErrorCode.NAME_CONFLICT,
                HttpStatus.CONFLICT, "同目录下已存在同名节点: " + name);
        }
        User owner = users.findById(me.userId()).orElseThrow();
        return toDto(nodes.save(Node.dir(owner, parent, name)));
    }

    @Transactional
    public NodeDto rename(SessionPrincipal me, UUID nodeId, String newName) {
        Node node = requireNode(nodeId);
        if (node.getParent() != null) {
            require(node.getParent(), me, 'w');
            if (nodes.existsByParentIdAndNameAndTrashedAtIsNull(node.getParent().getId(), newName)) {
                throw new BusinessException(ErrorCode.NAME_CONFLICT,
                    HttpStatus.CONFLICT, "同目录下已存在同名节点: " + newName);
            }
        } else {
            require(node, me, 'w');
        }
        node.setName(newName);
        return toDto(node);
    }

    /** 删除 = 进回收站(软删除,可恢复,FR-FS-03)。 */
    @Transactional
    public void trash(SessionPrincipal me, UUID nodeId) {
        Node node = requireNode(nodeId);
        require(node, me, 'd');
        if (node.getTrashedAt() == null) {
            node.setTrashedAt(Instant.now());
        }
    }

    @Transactional
    public NodeDto restore(SessionPrincipal me, UUID nodeId) {
        Node node = requireNode(nodeId);
        if (node.getTrashedAt() == null) {
            throw new BusinessException(ErrorCode.TRASH_RESTORE_CONFLICT,
                HttpStatus.CONFLICT, "节点不在回收站中");
        }
        Node parent = node.getParent();
        if (parent == null) {
            throw new BusinessException(ErrorCode.TRASH_RESTORE_CONFLICT,
                HttpStatus.CONFLICT, "根节点不能从回收站恢复");
        }
        require(parent, me, 'w');
        if (nodes.existsByParentIdAndNameAndTrashedAtIsNull(parent.getId(), node.getName())) {
            node.setName(node.getName() + " (restored)");
        }
        node.setTrashedAt(null);
        return toDto(node);
    }

    // ---------------------------------------------------------------- 内容读取

    /**
     * 读取文件内容的指定区间(Range 下载)。start 从 0 计,length 为字节数。
     * 只打开需要的分块,避免大文件全量加载。
     */
    @Transactional(readOnly = true)
    public ContentStream content(SessionPrincipal me, UUID nodeId, long start, long length) {
        Node node = requireFile(nodeId);
        require(node, me, 'r');
        FileVersion version = versions.findTopByNodeIdOrderByVersionNoDesc(nodeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                HttpStatus.NOT_FOUND, "文件没有可用版本"));
        long total = version.getSizeBytes();
        if (start < 0 || start > total) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Range 起点越界: " + start);
        }
        long len = Math.min(length, total - start);
        List<VersionChunk> all = chunks.findAllByVersionIdOrderBySeqAsc(version.getId());
        int chunkSize = all.isEmpty() ? 1 : (int) all.get(0).getSizeBytes();
        int firstSeq = chunkSize <= 0 ? 0 : (int) (start / chunkSize);
        long skipInFirst = chunkSize <= 0 ? 0 : start % chunkSize;

        List<InputStream> parts = new ArrayList<>();
        for (int i = firstSeq; i < all.size(); i++) {
            VersionChunk c = all.get(i);
            try {
                parts.add(blobs.open(c.getBlobHash()));
            } catch (IOException e) {
                throw new UncheckedIOException("读取分块失败: " + c.getBlobHash(), e);
            }
        }
        InputStream joined = Streams.concat(parts);
        try {
            long toSkip = skipInFirst;
            while (toSkip > 0) {
                long skipped = joined.skip(toSkip);
                if (skipped <= 0) {
                    break;
                }
                toSkip -= skipped;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("定位读取起点失败", e);
        }
        return new ContentStream(Streams.limit(joined, len), total, start, len);
    }

    // ---------------------------------------------------------------- 内部

    /** 当前版本大小(用于 Range 计算)。 */
    @Transactional(readOnly = true)
    public long sizeOf(SessionPrincipal me, UUID nodeId) {
        Node node = requireFile(nodeId);
        require(node, me, 'r');
        return versions.findTopByNodeIdOrderByVersionNoDesc(nodeId)
            .map(FileVersion::getSizeBytes)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                HttpStatus.NOT_FOUND, "文件没有可用版本"));
    }

    /** 断言当前主体对节点拥有指定权限(供上传等跨服务入口调用)。 */
    @Transactional(readOnly = true)
    public void assertCan(SessionPrincipal me, UUID nodeId, char perm) {
        require(requireNode(nodeId), me, perm);
    }

    @Transactional(readOnly = true)
    public Node requireNode(UUID nodeId) {
        return nodes.findById(nodeId).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "节点不存在: " + nodeId));
    }

    private Node requireFile(UUID nodeId) {
        Node node = requireNode(nodeId);
        if (node.getKind() != Node.Kind.FILE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "目标不是文件");
        }
        return node;
    }

    private void require(Node node, SessionPrincipal me, char perm) {
        if (!acl.hasPermission(me, node.getId(), perm)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN,
                "无权限(" + perm + ")访问: " + node.getName());
        }
    }

    private NodeDto toDto(Node n) {
        return new NodeDto(n.getId(), n.getParent() == null ? null : n.getParent().getId(),
            n.getName(), n.getKind().name(), n.getSizeBytes(), n.getUpdatedAt(), n.getTrashedAt());
    }
}
