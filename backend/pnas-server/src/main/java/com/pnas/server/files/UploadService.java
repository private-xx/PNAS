package com.pnas.server.files;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.blobstore.BlobStore;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.common.PnasProperties;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.domain.FileVersion;
import com.pnas.server.files.domain.Node;
import com.pnas.server.files.domain.UploadSession;
import com.pnas.server.files.domain.VersionChunk;
import com.pnas.server.iam.AclService;
import com.pnas.server.iam.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 分块上传:先落块(blobstore 校验+去重),后提交元数据(版本化写入,绝不静默覆盖)。
 * 语义见架构 v0.3 §6.3 与需求 FR-FS-02/04。
 */
@Service
public class UploadService {

    private final UploadSessionRepository sessions;
    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final VersionChunkRepository chunks;
    private final BlobStore blobs;
    private final AclService acl;
    private final int chunkSize;

    public UploadService(UploadSessionRepository sessions, NodeRepository nodes,
                         FileVersionRepository versions, VersionChunkRepository chunks,
                         BlobStore blobs, AclService acl, PnasProperties props) {
        this.sessions = sessions;
        this.nodes = nodes;
        this.versions = versions;
        this.chunks = chunks;
        this.blobs = blobs;
        this.acl = acl;
        this.chunkSize = props.upload().chunkSize();
    }

    public record Completed(UUID nodeId, int versionNo, long size, boolean dedup) {}

    /** 建上传会话。调用方必须先校验对 destParent 的写权限(见 Task 7 Controller)。 */
    @Transactional
    public UUID start(User owner, UUID destParentId, String filename, long totalSize) {
        Node dir = nodes.findById(destParentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                HttpStatus.NOT_FOUND, "目标目录不存在"));
        if (dir.getKind() != Node.Kind.DIR) {
            throw new BusinessException(ErrorCode.PARENT_NOT_DIRECTORY,
                HttpStatus.BAD_REQUEST, "目标不是目录");
        }
        if (totalSize < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "文件大小不能为负: " + totalSize);
        }
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "文件名不能为空");
        }
        var s = new UploadSession();
        s.setUser(owner);
        s.setDestParent(dir);
        s.setFilename(filename);
        s.setTotalSize(totalSize);
        s.setChunkSize(chunkSize);
        s.setState("OPEN");
        s.setReceivedChunks(new ArrayList<>());
        s.setChunkHashes(new HashMap<>());
        s.setChunkSizes(new HashMap<>());
        return sessions.save(s).getId();
    }

    @Transactional
    public void acceptChunk(UUID actorId, UUID uploadId, int seq, InputStream body, String sha256) {
        UploadSession s = requireOpenFor(uploadId, actorId);
        requireWriteOnDest(s);
        if (chunkCount(s) == 0) {
            throw new BusinessException(ErrorCode.CHUNK_INVALID, HttpStatus.BAD_REQUEST,
                "空文件无需上传分块");
        }
        long expected = expectedChunkSize(s, seq);
        try {
            blobs.store(body, expected, sha256);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.CHUNK_INVALID,
                HttpStatus.BAD_REQUEST, "分块校验失败: " + e.getMessage());
        }
        if (!s.getReceivedChunks().contains(seq)) {
            s.getReceivedChunks().add(seq);
        }
        s.getChunkHashes().put(seq, sha256);
        s.getChunkSizes().put(seq, expected);
    }

    @Transactional
    public Completed complete(UUID actorId, UUID uploadId) {
        UploadSession s = requireOwned(uploadId, actorId);
        if ("COMPLETE".equals(s.getState())) {
            // 幂等:客户端在"服务端已完成但响应丢失"后重试/探测时,返回既有结果,避免重复上传或重复建版本
            Node file = nodes.findByParentIdAndNameAndTrashedAtIsNull(
                    s.getDestParent().getId(), s.getFilename())
                .orElseThrow(() -> new BusinessException(ErrorCode.UPLOAD_SESSION_INVALID,
                    HttpStatus.BAD_REQUEST, "会话已完成但目标文件不存在"));
            int existingVer = versions.findTopByNodeIdOrderByVersionNoDesc(file.getId())
                .map(FileVersion::getVersionNo).orElse(0);
            return new Completed(file.getId(), existingVer, s.getTotalSize(), true);
        }
        if (!"OPEN".equals(s.getState())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_INVALID, HttpStatus.BAD_REQUEST,
                "上传会话不在 OPEN 状态");
        }
        int count = chunkCount(s);
        if (s.getReceivedChunks().size() != count) {
            throw new BusinessException(ErrorCode.CHUNK_MISSING, HttpStatus.BAD_REQUEST,
                "分块不完整: 已收 " + s.getReceivedChunks().size() + "/" + count);
        }
        for (int i = 0; i < count; i++) {
            if (s.getChunkHashes().get(i) == null) {
                throw new BusinessException(ErrorCode.CHUNK_MISSING, HttpStatus.BAD_REQUEST,
                    "缺少第 " + i + " 块的哈希记录");
            }
        }
        var user = s.getUser();
        Node dir = s.getDestParent();
        requireWriteOnDest(s); // 权限可能在会话存续期间被回收(FR-AUTH-06):提交前再校验一次
        Node existing = nodes.findByParentIdAndNameAndTrashedAtIsNull(dir.getId(), s.getFilename())
            .orElse(null);
        if (existing != null && existing.getKind() != Node.Kind.FILE) {
            // 同名目录不能被当作文件写入(否则会给目录挂上 FileVersion,P2 评审 Critical#1)
            throw new BusinessException(ErrorCode.NAME_CONFLICT, HttpStatus.CONFLICT,
                "同名目录已存在,不能作为文件写入: " + s.getFilename());
        }
        // 同目录同名文件 → 新增版本;否则创建 FILE 节点(版本化写入,FR-FS-04)
        Node file = existing != null ? existing : nodes.save(Node.file(user, dir, s.getFilename()));
        int nextVer = versions.findTopByNodeIdOrderByVersionNoDesc(file.getId())
            .map(v -> v.getVersionNo() + 1).orElse(1);

        var manifest = new StringBuilder();
        for (int i = 0; i < count; i++) {
            manifest.append(s.getChunkHashes().get(i));
        }

        String manifestHex = Sha256.hex(manifest.toString().getBytes(StandardCharsets.UTF_8));
        // 去重语义:该内容的版本此前已存在于仓库(秒传命中)
        boolean dedup = versions.existsByManifestSha256(manifestHex);

        var fv = new FileVersion();
        fv.setNode(file);
        fv.setVersionNo(nextVer);
        fv.setSizeBytes(s.getTotalSize());
        fv.setMimeType("application/octet-stream");
        fv.setCreatedBy(user);
        fv.setManifestSha256(manifestHex);
        versions.save(fv);

        List<VersionChunk> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var c = new VersionChunk();
            c.setVersion(fv);
            c.setSeq(i);
            c.setBlobHash(s.getChunkHashes().get(i));
            c.setSizeBytes(s.getChunkSizes().getOrDefault(i, 0L));
            rows.add(c);
        }
        chunks.saveAll(rows);

        file.setSizeBytes(s.getTotalSize());
        s.setState("COMPLETE");
        return new Completed(file.getId(), nextVer, s.getTotalSize(), dedup);
    }

    @Transactional
    public void cancel(UUID actorId, UUID uploadId) {
        UploadSession s = requireOpenFor(uploadId, actorId);
        s.setState("CANCELLED");
    }

    /** 会话必须属于该主体(不限状态,供 complete 的幂等探测使用)。 */
    private UploadSession requireOwned(UUID uploadId, UUID actorId) {
        UploadSession s = sessions.findById(uploadId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UPLOAD_SESSION_INVALID,
                HttpStatus.BAD_REQUEST, "上传会话不存在"));
        if (!s.getUser().getId().equals(actorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN,
                "上传会话不属于当前用户");
        }
        return s;
    }

    /** 会话必须是 OPEN 且属于该主体(操作上传的其他端点用)。 */
    private UploadSession requireOpenFor(UUID uploadId, UUID actorId) {
        UploadSession s = requireOwned(uploadId, actorId);
        if (!"OPEN".equals(s.getState())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_INVALID,
                HttpStatus.BAD_REQUEST, "上传会话不在 OPEN 状态");
        }
        return s;
    }

    /** 提交前复检目标目录的写权限(以会话所属用户为主体)。 */
    private void requireWriteOnDest(UploadSession s) {
        User u = s.getUser();
        var principal = new SessionPrincipal(u.getId(), u.getUsername(), u.getRole(), null);
        if (!acl.hasPermission(principal, s.getDestParent().getId(), 'w')) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN,
                "目标目录写权限已被回收,上传不能提交");
        }
    }

    private int chunkCount(UploadSession s) {
        if (s.getTotalSize() <= 0) return 0; // 空文件:0 块,complete 直接建 0 块版本
        return (int) ((s.getTotalSize() + chunkSize - 1) / chunkSize);
    }

    private long expectedChunkSize(UploadSession s, int seq) {
        int count = chunkCount(s);
        if (seq < 0 || seq >= count) {
            throw new BusinessException(ErrorCode.CHUNK_INVALID,
                HttpStatus.BAD_REQUEST, "分块序号越界: " + seq);
        }
        if (seq == count - 1) {
            long remainder = s.getTotalSize() - (long) chunkSize * (count - 1);
            return remainder > 0 ? remainder : chunkSize;
        }
        return chunkSize;
    }
}
