package com.pnas.server.files;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.BlobStore;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadServiceTest extends AbstractIntegrationTest {

    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired UserRepository users;
    @Autowired BlobStore blobs;

    private User admin() {
        return users.findByUsername("admin").orElseThrow();
    }

    /** 走完整上传流程(单块即可,内容 < 4MiB)。 */
    private UploadService.Completed upload(User owner, UUID parentId, String name, byte[] content) {
        UUID uploadId = uploads.start(owner, parentId, name, content.length);
        uploads.acceptChunk(owner.getId(), uploadId, 0, new ByteArrayInputStream(content), Sha256.hex(content));
        return uploads.complete(owner.getId(), uploadId);
    }

    @Test
    void uploadTwoChunksCreatesVersionedFile() {
        var admin = admin();
        var home = files.ensureUserHome(admin);
        // 服务端分块固定 4MiB(全局约束);两个整块 = 8MiB
        int chunk = 4 * 1024 * 1024;
        byte[] a = new byte[chunk];
        byte[] b = new byte[chunk];
        java.util.Arrays.fill(a, (byte) 'A');
        java.util.Arrays.fill(b, (byte) 'B');

        var up = uploads.start(admin, home.getId(), "report.txt", a.length + b.length);
        String shaA = Sha256.hex(a);
        String shaB = Sha256.hex(b);
        uploads.acceptChunk(admin.getId(), up, 0, new ByteArrayInputStream(a), shaA);
        uploads.acceptChunk(admin.getId(), up, 1, new ByteArrayInputStream(b), shaB);
        var done = uploads.complete(admin.getId(), up);

        assertThat(done.nodeId()).isNotNull();
        assertThat(done.versionNo()).isEqualTo(1);
        assertThat(blobs.exists(shaA)).isTrue();
        assertThat(blobs.exists(shaB)).isTrue();
    }

    @Test
    void zeroByteFileCreatesVersionWithoutChunks() {
        var admin = admin();
        var home = files.ensureUserHome(admin);
        UUID uploadId = uploads.start(admin, home.getId(), "empty-" + UUID.randomUUID() + ".txt", 0);

        assertThatThrownBy(() -> uploads.acceptChunk(admin.getId(), uploadId, 0,
            new ByteArrayInputStream(new byte[0]), Sha256.hex(new byte[0])))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("空文件");

        var done = uploads.complete(admin.getId(), uploadId);
        assertThat(done.versionNo()).isEqualTo(1);
        assertThat(done.size()).isZero();
    }

    @Test
    void reuploadSameNameCreatesNewVersionAndDetectsDuplicateContent() {
        var admin = admin();
        var home = files.ensureUserHome(admin);
        String name = "versioned-" + UUID.randomUUID() + ".txt";
        byte[] first = "v1-content".getBytes(StandardCharsets.UTF_8);
        byte[] second = "v2-content".getBytes(StandardCharsets.UTF_8);

        var v1 = upload(admin, home.getId(), name, first);
        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v1.dedup()).isFalse();

        // FR-FS-04:同名再传 = 新版本,绝不覆盖
        var v2 = upload(admin, home.getId(), name, second);
        assertThat(v2.nodeId()).isEqualTo(v1.nodeId());
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.dedup()).isFalse();

        // 相同内容再次上传 → 去重命中(清单哈希已存在)
        var v3 = upload(admin, home.getId(), name, second);
        assertThat(v3.versionNo()).isEqualTo(3);
        assertThat(v3.dedup()).isTrue();
    }

    @Test
    void sameNameDirectoryBlocksFileWrite() {
        var admin = admin();
        var home = files.ensureUserHome(admin);
        String name = "clash-" + UUID.randomUUID();
        files.createDir(new com.pnas.server.auth.SessionPrincipal(
            admin.getId(), admin.getUsername(), admin.getRole(), UUID.randomUUID()), home.getId(), name);

        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        UUID uploadId = uploads.start(admin, home.getId(), name, content.length);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));

        // 同名目录存在时,complete 必须拒绝(P2 评审 Critical#1:否则会给目录挂上文件版本)
        assertThatThrownBy(() -> uploads.complete(admin.getId(), uploadId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("同名目录");
    }

    @Test
    void negativeSizeIsRejected() {
        var admin = admin();
        var home = files.ensureUserHome(admin);
        assertThatThrownBy(() -> uploads.start(admin, home.getId(), "bad.txt", -1))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能为负");
    }
}
