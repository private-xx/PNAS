package com.pnas.server.media;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.DerivedStore;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.UploadService;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.job.JobService;
import com.pnas.server.media.domain.ThumbStatus;
import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** media.scan / media.thumb / media.reindex 端到端:上传 → 入队 → 同步执行 → 资产与缩略图。 */
class MediaJobsTest extends AbstractIntegrationTest {

    @Autowired JobService jobs;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired MediaAssetRepository assets;
    @Autowired DerivedStore derived;
    @Autowired UserRepository users;

    private record Built(UUID nodeId, UUID versionId) {}

    private Built upload(String name, byte[] content) {
        var admin = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(admin);
        UUID uploadId = uploads.start(admin, home.getId(), name, content.length);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));
        var done = uploads.complete(admin.getId(), uploadId);
        UUID versionId = versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId())
            .orElseThrow().getId();
        return new Built(done.nodeId(), versionId);
    }

    /**
     * 同步跑完所有已到期任务:scan 会再入队 thumb,reindex 在满批时会自链式入队下一批,
     * 所以必须循环到队列不再有到期任务(设置上限避免任务永不收敛时死循环)。
     */
    private void drainJobs() {
        int rounds = 0;
        while (jobs.claimAndRunAllNow() > 0) {
            if (++rounds > 100) {
                throw new AssertionError("任务队列在 100 轮内未收敛");
            }
        }
    }

    private BufferedImage thumbnail(String sha, int size) throws Exception {
        try (InputStream in = derived.open(sha, size)) {
            return ImageIO.read(in);
        }
    }

    @Test
    void uploadEnqueuesScanWhichProducesAssetAndThumbs() throws Exception {
        byte[] jpeg = TestImages.jpegWithExif(
            LocalDateTime.of(2026, 3, 1, 10, 20, 30), 39.9075, 116.3972, 1, 800, 600);
        var built = upload("jobs-" + UUID.randomUUID() + ".jpg", jpeg);

        // 上传完成即已入队 media.scan;同步跑完(scan 内部会再入队 thumbs)
        drainJobs();

        var asset = assets.findByFileVersionId(built.versionId()).orElseThrow();
        assertThat(asset.getThumbStatus()).isEqualTo(ThumbStatus.READY);
        String sha = versions.findById(built.versionId()).orElseThrow().getManifestSha256();
        assertThat(derived.exists(sha, 256)).isTrue();
        assertThat(derived.exists(sha, 512)).isTrue();
    }

    @Test
    void thumbnailAppliesExifOrientationEndToEnd() throws Exception {
        // 同样 640×480 的像素,一个带 EXIF Orientation=6(顺时针 90°),一个无 EXIF
        byte[] oriented = TestImages.jpegWithExif(
            LocalDateTime.of(2026, 3, 1, 10, 20, 30), 39.9075, 116.3972, 6, 640, 480);
        byte[] plain = TestImages.plainJpeg(640, 480);
        var rotated = upload("rotated-" + UUID.randomUUID() + ".jpg", oriented);
        var upright = upload("plain-" + UUID.randomUUID() + ".jpg", plain);

        drainJobs();

        assertThat(assets.findByFileVersionId(rotated.versionId()).orElseThrow().getThumbStatus())
            .isEqualTo(ThumbStatus.READY);
        assertThat(assets.findByFileVersionId(upright.versionId()).orElseThrow().getThumbStatus())
            .isEqualTo(ThumbStatus.READY);

        String rotatedSha = versions.findById(rotated.versionId()).orElseThrow().getManifestSha256();
        String plainSha = versions.findById(upright.versionId()).orElseThrow().getManifestSha256();

        // Orientation=6 必须先旋转再按长边 256 缩放:480×640 → 192×256(宽高互换)
        BufferedImage rotatedThumb = thumbnail(rotatedSha, 256);
        assertThat(rotatedThumb.getWidth()).isEqualTo(192);
        assertThat(rotatedThumb.getHeight()).isEqualTo(256);

        // 无 EXIF:640×480 → 256×192,证明旋转来自 EXIF 而非固定行为
        BufferedImage plainThumb = thumbnail(plainSha, 256);
        assertThat(plainThumb.getWidth()).isEqualTo(256);
        assertThat(plainThumb.getHeight()).isEqualTo(192);
    }

    @Test
    void reindexIsIdempotent() throws Exception {
        var built = upload("reindex-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(120, 90));
        drainJobs();
        long before = assets.count();

        jobs.enqueue("media.reindex", Map.of());
        drainJobs();

        assertThat(assets.count()).isEqualTo(before);   // 重复扫描不新增记录
        assertThat(assets.findByFileVersionId(built.versionId())).isPresent();
    }
}
