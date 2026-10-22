package com.pnas.server.media;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.UploadService;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import com.pnas.server.media.domain.MediaKind;
import com.pnas.server.media.domain.ThumbStatus;
import com.pnas.server.media.domain.TakenAtSource;
import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaIngestServiceTest extends AbstractIntegrationTest {

    @Autowired MediaIngestService ingest;
    @Autowired MediaAssetRepository assets;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired UserRepository users;

    private record Built(UUID nodeId, UUID versionId) {}

    private Built upload(User owner, String name, byte[] content) {
        var home = files.ensureUserHome(owner);
        UUID uploadId = uploads.start(owner, home.getId(), name, content.length);
        uploads.acceptChunk(owner.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));
        var done = uploads.complete(owner.getId(), uploadId);
        UUID versionId = versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId())
            .orElseThrow().getId();
        return new Built(done.nodeId(), versionId);
    }

    @Test
    void ingestsJpegWithExifAndFallsBackToMtimeWhenMissing() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        Instant taken = Instant.parse("2026-03-01T10:20:30Z");
        // EXIF DateTimeOriginal 是无时区本地墙钟时间(R13):按 JVM 默认时区解释后应还原为 taken
        LocalDateTime wallClock = LocalDateTime.ofInstant(taken, ZoneId.systemDefault());

        // 有 EXIF:拍摄时间来自 EXIF,状态 PENDING(可生成缩略图)
        var withExif = upload(admin, "exif-" + UUID.randomUUID() + ".jpg",
            TestImages.jpegWithExif(wallClock, 39.9075, 116.3972, 1, 800, 600));
        var r1 = ingest.ingest(withExif.nodeId(), withExif.versionId()).orElseThrow();
        var a1 = assets.findByFileVersionId(withExif.versionId()).orElseThrow();
        assertThat(r1.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(a1.getTakenAtSource()).isEqualTo(TakenAtSource.EXIF);
        assertThat(a1.getTakenAt()).isEqualTo(taken);
        assertThat(a1.getWidth()).isEqualTo(800);
        assertThat(a1.getThumbStatus()).isEqualTo(ThumbStatus.PENDING);

        // 无 EXIF:退回 mtime
        var noExif = upload(admin, "plain-" + UUID.randomUUID() + ".jpg",
            TestImages.plainJpeg(320, 240));
        ingest.ingest(noExif.nodeId(), noExif.versionId()).orElseThrow();
        var a2 = assets.findByFileVersionId(noExif.versionId()).orElseThrow();
        assertThat(a2.getTakenAtSource()).isEqualTo(TakenAtSource.MTIME);
        assertThat(a2.getWidth()).isEqualTo(320);
    }

    @Test
    void unsupportedImageIsIngestedAsSkipped() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        var built = upload(admin, "photo-" + UUID.randomUUID() + ".heic",
            TestImages.plainJpeg(64, 64));

        var result = ingest.ingest(built.nodeId(), built.versionId()).orElseThrow();

        assertThat(result.thumbStatus()).isEqualTo(ThumbStatus.SKIPPED);
    }

    @Test
    void nonMediaIsNotIngested() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        var built = upload(admin, "notes-" + UUID.randomUUID() + ".txt",
            "hello".getBytes());

        assertThat(ingest.ingest(built.nodeId(), built.versionId())).isEmpty();
        assertThat(assets.findByFileVersionId(built.versionId())).isEmpty();
    }

    @Test
    void ingestIsIdempotentPerVersion() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        var built = upload(admin, "idem-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(50, 50));

        var first = ingest.ingest(built.nodeId(), built.versionId()).orElseThrow();
        var second = ingest.ingest(built.nodeId(), built.versionId()).orElseThrow();

        assertThat(second.assetId()).isEqualTo(first.assetId());
        assertThat(assets.count()).isEqualTo(assets.count());   // 二次调用不新增行
        assertThat(assets.findByFileVersionId(built.versionId())).isPresent();
    }
}
