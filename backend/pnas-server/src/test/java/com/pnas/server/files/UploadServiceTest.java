package com.pnas.server.files;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.BlobStore;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class UploadServiceTest extends AbstractIntegrationTest {

    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired UserRepository users;
    @Autowired BlobStore blobs;

    @Test
    void uploadTwoChunksCreatesVersionedFile() {
        var admin = users.findByUsername("admin").orElseThrow();
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
}
