package com.pnas.server.files;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import com.pnas.server.files.domain.FileVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SourceMtimeUploadTest extends AbstractIntegrationTest {

    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired UserRepository users;

    private FileVersion upload(String name, byte[] content, Instant mtime) {
        User admin = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(admin);
        UUID uploadId = mtime == null
            ? uploads.start(admin, home.getId(), name, content.length)
            : uploads.start(admin, home.getId(), name, content.length, mtime);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));
        var done = uploads.complete(admin.getId(), uploadId);
        return versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId()).orElseThrow();
    }

    @Test
    void sourceMtimeIsPersistedWhenProvided() {
        Instant mtime = Instant.parse("2026-10-01T08:00:00Z");
        var version = upload("mtime-" + UUID.randomUUID() + ".txt",
            "hello".getBytes(StandardCharsets.UTF_8), mtime);
        assertThat(version.getSourceMtime()).isEqualTo(mtime);
    }

    @Test
    void sourceMtimeIsNullForLegacyClients() {
        var version = upload("legacy-" + UUID.randomUUID() + ".txt",
            "hello".getBytes(StandardCharsets.UTF_8), null);
        assertThat(version.getSourceMtime()).isNull();
    }
}
