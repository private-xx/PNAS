package com.pnas.server.media;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.auth.SessionPrincipal;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/** media.scan / media.thumb / media.reindex 端到端:上传 → 入队 → 同步执行 → 资产与缩略图。 */
class MediaJobsTest extends AbstractIntegrationTest {

    @SpyBean JobService jobs;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired MediaAssetRepository assets;
    @Autowired DerivedStore derived;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager txManager;

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
        UUID assetIdBefore = assets.findByFileVersionId(built.versionId()).orElseThrow().getId();

        jobs.enqueue("media.reindex", Map.of());
        drainJobs();

        // 作用域收敛到本 node/version:重复扫描不新增同版本记录,也不换 asset
        var assetAfter = assets.findByFileVersionId(built.versionId()).orElseThrow();
        assertThat(assetAfter.getId()).isEqualTo(assetIdBefore);
        assertThat(assets.findAll().stream()
            .filter(a -> a.getNodeId().equals(built.nodeId()))
            .count()).isEqualTo(1);
    }

    @Test
    void reindexBackfillsMissingThumbnails() throws Exception {
        var built = upload("backfill-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(640, 480));
        drainJobs();
        String sha = versions.findById(built.versionId()).orElseThrow().getManifestSha256();
        // 模拟 pre-M3 / 派生目录丢失:asset 仍在但 PENDING,且两个尺寸的派生文件都不存在
        Files.deleteIfExists(derived.thumbPath(sha, 256));
        Files.deleteIfExists(derived.thumbPath(sha, 512));
        var asset = assets.findByFileVersionId(built.versionId()).orElseThrow();
        asset.markThumb(ThumbStatus.PENDING);
        assets.save(asset);

        jobs.enqueue("media.reindex", Map.of());
        drainJobs();

        assertThat(assets.findByFileVersionId(built.versionId()).orElseThrow().getThumbStatus())
            .isEqualTo(ThumbStatus.READY);
        assertThat(derived.exists(sha, 256)).isTrue();
        assertThat(derived.exists(sha, 512)).isTrue();
    }

    @Test
    void completeEnqueuesScanOnlyAfterCommitAndOnceOnRetry() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(admin);
        byte[] jpeg = TestImages.plainJpeg(64, 48);
        UUID uploadId = uploads.start(admin, home.getId(),
            "once-" + UUID.randomUUID() + ".jpg", jpeg.length);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(jpeg),
            Sha256.hex(jpeg));

        // 外层事务包住 complete:提交前任务必须不可见(证明不是"事务内入队")
        var outerTx = new TransactionTemplate(txManager);
        var versionId = new AtomicReference<UUID>();
        outerTx.executeWithoutResult(status -> {
            var done = uploads.complete(admin.getId(), uploadId);
            versionId.set(versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId())
                .orElseThrow().getId());
            assertThat(scanJobsFor(versionId.get())).isZero();
        });
        // 外层事务提交后 afterCommit 才入队
        assertThat(scanJobsFor(versionId.get())).isEqualTo(1);

        // 幂等分支(会话已 COMPLETE):返回既有结果,不重复入队
        uploads.complete(admin.getId(), uploadId);
        assertThat(scanJobsFor(versionId.get())).isEqualTo(1);
    }

    @Test
    void scanEnqueueFailureDoesNotFailUpload() throws Exception {
        doThrow(new RuntimeException("boom"))
            .when(jobs).enqueue(eq("media.scan"), any(), anyInt(), anyLong());

        var admin = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(admin);
        byte[] jpeg = TestImages.plainJpeg(64, 48);
        UUID uploadId = uploads.start(admin, home.getId(),
            "enqueue-fail-" + UUID.randomUUID() + ".jpg", jpeg.length);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(jpeg),
            Sha256.hex(jpeg));

        // 入队抛异常只 WARN:上传仍成功,版本行仍提交,只是没有 media.scan 任务
        var done = uploads.complete(admin.getId(), uploadId);
        UUID versionId = versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId())
            .orElseThrow().getId();

        assertThat(done.versionNo()).isEqualTo(1);
        assertThat(versions.findById(versionId)).isPresent();
        assertThat(scanJobsFor(versionId)).isZero();
        // 确认确实尝试过入队(否则本用例可能因"根本没调用"而假通过)
        verify(jobs).enqueue(eq("media.scan"), any(), anyInt(), anyLong());
    }

    private long scanJobsFor(UUID versionId) {
        return jobs.list(null).stream()
            .filter(j -> "media.scan".equals(j.getType()))
            .filter(j -> versionId.toString().equals(j.getPayload().get("versionId")))
            .count();
    }

    @Test
    void thumbReadyRequiresBothSizes() throws Exception {
        var built = upload("ready-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(640, 480));
        drainJobs();
        String sha = versions.findById(built.versionId()).orElseThrow().getManifestSha256();
        assertThat(assets.findByFileVersionId(built.versionId()).orElseThrow().getThumbStatus())
            .isEqualTo(ThumbStatus.READY);

        // 模拟 512 派生文件缺失而状态仍是 READY:再跑一次 256,必须回退 PENDING
        Files.deleteIfExists(derived.thumbPath(sha, 512));
        jobs.enqueue("media.thumb", Map.of(
            "nodeId", built.nodeId().toString(),
            "versionId", built.versionId().toString(),
            "size", 256));
        drainJobs();

        assertThat(derived.exists(sha, 256)).isTrue();
        assertThat(derived.exists(sha, 512)).isFalse();
        assertThat(assets.findByFileVersionId(built.versionId()).orElseThrow().getThumbStatus())
            .isEqualTo(ThumbStatus.PENDING);
    }

    @Test
    void reindexSkipsTrashedNodes() throws Exception {
        var built = upload("trashed-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(120, 90));
        drainJobs();
        // 造出"已入库的媒体版本,但节点已进回收站且 asset 被清掉"的场景
        assets.findByFileVersionId(built.versionId()).ifPresent(assets::delete);
        var admin = users.findByUsername("admin").orElseThrow();
        files.trash(new SessionPrincipal(admin.getId(), admin.getUsername(), admin.getRole(),
            UUID.randomUUID()), built.nodeId());

        jobs.enqueue("media.reindex", Map.of());
        drainJobs();

        assertThat(assets.findByFileVersionId(built.versionId())).isEmpty();
    }
}
