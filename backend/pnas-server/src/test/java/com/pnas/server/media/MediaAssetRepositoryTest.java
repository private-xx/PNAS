package com.pnas.server.media;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.UploadService;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import com.pnas.server.media.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaAssetRepositoryTest extends AbstractIntegrationTest {

    @Autowired MediaAssetRepository assets;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired UserRepository users;

    private record Built(UUID nodeId, UUID versionId) {}

    /** 走真实上传流程造出 FILE 节点与 file_version(满足外键),返回两者的 id。 */
    private Built realVersion(User owner, byte[] content) {
        var home = files.ensureUserHome(owner);
        String name = "m3-asset-" + UUID.randomUUID() + ".jpg";
        UUID uploadId = uploads.start(owner, home.getId(), name, content.length);
        uploads.acceptChunk(owner.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));
        var done = uploads.complete(owner.getId(), uploadId);
        UUID versionId = versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId())
            .orElseThrow().getId();
        return new Built(done.nodeId(), versionId);
    }

    @Test
    void savesAndFindsByFileVersionId() {
        var admin = users.findByUsername("admin").orElseThrow();
        var built = realVersion(admin, "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8));

        var saved = assets.save(MediaAsset.create(built.versionId(), built.nodeId(), MediaKind.IMAGE,
            Instant.parse("2026-03-01T10:00:00Z"), TakenAtSource.EXIF, 4000, 3000,
            39.9, 116.4, null, ThumbStatus.PENDING));

        var found = assets.findByFileVersionId(built.versionId()).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getKind()).isEqualTo(MediaKind.IMAGE);
        assertThat(found.getTakenAtSource()).isEqualTo(TakenAtSource.EXIF);
        assertThat(found.getWidth()).isEqualTo(4000);
    }
}
