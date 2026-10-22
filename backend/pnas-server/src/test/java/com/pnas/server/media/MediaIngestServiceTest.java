package com.pnas.server.media;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.Streams;
import com.pnas.server.files.UploadService;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import com.pnas.server.media.domain.MediaKind;
import com.pnas.server.media.domain.ThumbStatus;
import com.pnas.server.media.domain.TakenAtSource;
import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaIngestServiceTest extends AbstractIntegrationTest {

    @Autowired MediaIngestService ingest;
    @Autowired MediaAssetRepository assets;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired UserRepository users;

    private TimeZone originalZone;

    /**
     * EXIF DateTimeOriginal 是无时区本地墙钟,按 JVM 默认时区解释。宿主 JVM 若是 UTC,
     * "墙钟进/UTC 出"会让固定 UTC 的实现也通过;钉死 Asia/Shanghai 才能验证本地墙钟语义。
     */
    @BeforeEach
    void pinTimezone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @AfterEach
    void restoreTimezone() {
        TimeZone.setDefault(originalZone);
    }

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
        // EXIF DateTimeOriginal 是无时区本地墙钟时间(R13):Asia/Shanghai 下 10:20:30 = 02:20:30Z。
        // 期望值写死具体瞬间,固定 UTC 解释会得到 10:20:30Z 而立即失败。
        LocalDateTime wallClock = LocalDateTime.parse("2026-03-01T10:20:30");
        Instant expectedTakenAt = Instant.parse("2026-03-01T02:20:30Z");

        // 有 EXIF:拍摄时间来自 EXIF,状态 PENDING(可生成缩略图)
        var withExif = upload(admin, "exif-" + UUID.randomUUID() + ".jpg",
            TestImages.jpegWithExif(wallClock, 39.9075, 116.3972, 1, 800, 600));
        var r1 = ingest.ingest(withExif.nodeId(), withExif.versionId()).orElseThrow();
        var a1 = assets.findByFileVersionId(withExif.versionId()).orElseThrow();
        assertThat(r1.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(a1.getTakenAtSource()).isEqualTo(TakenAtSource.EXIF);
        assertThat(a1.getTakenAt()).isEqualTo(expectedTakenAt);
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
        long afterFirst = assets.count();                 // 共享测试库:用增量而非绝对值
        var second = ingest.ingest(built.nodeId(), built.versionId()).orElseThrow();

        assertThat(second.assetId()).isEqualTo(first.assetId());
        assertThat(assets.count()).isEqualTo(afterFirst);  // 二次调用不新增行
        assertThat(assets.findByFileVersionId(built.versionId())).isPresent();
    }

    @Test
    void ingestsVideoWithMvhdDurationAndSkipsThumbnail() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        // timescale=600 / duration=1200 → 2000ms
        var built = upload(admin, "clip-" + UUID.randomUUID() + ".mp4", minimalMp4(600, 1200));

        var result = ingest.ingest(built.nodeId(), built.versionId()).orElseThrow();
        var asset = assets.findByFileVersionId(built.versionId()).orElseThrow();

        assertThat(result.kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(asset.getThumbStatus()).isEqualTo(ThumbStatus.SKIPPED);
        assertThat(asset.getTakenAtSource()).isEqualTo(TakenAtSource.MTIME);
        assertThat(asset.getDurationMs()).isEqualTo(2000L);
    }

    @Test
    void rejectsVersionThatDoesNotBelongToNode() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        var a = upload(admin, "guard-a-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(20, 20));
        var b = upload(admin, "guard-b-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(20, 20));

        assertThatThrownBy(() -> ingest.ingest(a.nodeId(), b.versionId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不属于该节点");
        assertThat(assets.findByFileVersionId(b.versionId())).isEmpty();
    }

    /** R19:惰性拼接只在需要时开块、读尽即关,且不吞掉 Opener 的 IOException。 */
    @Test
    void lazyConcatOpensOnDemandAndClosesEachPart() throws IOException {
        List<InputStream> opened = new ArrayList<>();
        int[] closed = {0};
        List<Streams.Opener> parts = new ArrayList<>();
        for (String chunk : List.of("ab", "cd", "ef")) {
            parts.add(() -> {
                var in = new ByteArrayInputStream(chunk.getBytes()) {
                    @Override
                    public void close() throws IOException {
                        closed[0]++;
                        super.close();
                    }
                };
                opened.add(in);
                return in;
            });
        }

        try (InputStream in = Streams.lazyConcat(parts)) {
            assertThat(in.read()).isEqualTo('a');
            assertThat(opened).hasSize(1);                 // 首读只打开第一块
            assertThat(new String(in.readAllBytes())).isEqualTo("bcdef");
            assertThat(opened).hasSize(3);
            assertThat(closed[0]).isEqualTo(3);            // 每块读尽立即关闭
        }

        List<Streams.Opener> failing = List.of(() -> {
            throw new IOException("boom");
        });
        assertThatThrownBy(() -> Streams.lazyConcat(failing).read())
            .isInstanceOf(IOException.class)
            .hasMessage("boom");
    }

    /** ftyp + moov/mvhd(version 0)最小 MP4;与 Mp4DurationReaderTest 的构造方式一致。 */
    private static byte[] minimalMp4(int timescale, int duration) {
        ByteBuffer mvhd = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN);
        mvhd.putInt(100).put("mvhd".getBytes()).putInt(0).putInt(0).putInt(0)
            .putInt(timescale).putInt(duration);
        while (mvhd.position() < 100) {
            mvhd.put((byte) 0);
        }
        ByteBuffer moov = ByteBuffer.allocate(108).order(ByteOrder.BIG_ENDIAN);
        moov.putInt(108).put("moov".getBytes()).put(mvhd.array());
        ByteBuffer ftyp = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        ftyp.putInt(24).put("ftyp".getBytes()).put("isom".getBytes()).putInt(0)
            .put("isommp42".getBytes());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ftyp.array());
        out.writeBytes(moov.array());
        return out.toByteArray();
    }
}
