package com.pnas.server.media.job;

import com.pnas.server.blobstore.DerivedStore;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.domain.FileVersion;
import com.pnas.server.job.domain.Job;
import com.pnas.server.job.JobHandler;
import com.pnas.server.media.ExifReader;
import com.pnas.server.media.MediaAssetRepository;
import com.pnas.server.media.ThumbnailService;
import com.pnas.server.media.domain.MediaAsset;
import com.pnas.server.media.domain.ThumbStatus;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/**
 * media.thumb:生成指定尺寸缩略图并更新 thumb_status。
 *
 * <p>R17:渲染前必须先读取该版本的 EXIF Orientation(手机竖拍照片常为 6/8,
 * 否则缩略图会横躺);EXIF 读取与渲染各开一次内容流,避免前者消费掉后者。</p>
 */
@Component
public class MediaThumbHandler implements JobHandler {

    /** READY 要求两个尺寸都已有派生文件(scan 只入队这两个尺寸)。 */
    private static final int[] REQUIRED_SIZES = {256, 512};

    private final ThumbnailService thumbs;
    private final DerivedStore derived;
    private final FilesService files;
    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final MediaAssetRepository assets;
    private final ExifReader exifReader;

    public MediaThumbHandler(ThumbnailService thumbs, DerivedStore derived, FilesService files,
                             NodeRepository nodes, FileVersionRepository versions,
                             MediaAssetRepository assets, ExifReader exifReader) {
        this.thumbs = thumbs;
        this.derived = derived;
        this.files = files;
        this.nodes = nodes;
        this.versions = versions;
        this.assets = assets;
        this.exifReader = exifReader;
    }

    @Override
    public String type() {
        return "media.thumb";
    }

    @Override
    public Map<String, Object> run(Job job) throws Exception {
        UUID nodeId = UUID.fromString((String) job.getPayload().get("nodeId"));
        UUID versionId = UUID.fromString((String) job.getPayload().get("versionId"));
        int size = ((Number) job.getPayload().get("size")).intValue();

        FileVersion version = versions.findById(versionId).orElseThrow();
        String name = nodes.findById(nodeId).orElseThrow().getName();
        String sha = version.getManifestSha256();
        MediaAsset asset = assets.findByFileVersionId(versionId).orElse(null);
        if (derived.exists(sha, size)) {
            // 幂等:派生文件已存在。仅当两个尺寸都齐备才 READY(修复旧状态/补写状态)
            markReadyIfAllSizes(asset, sha);
            return Map.of("generated", false, "size", size);
        }
        // R17:先单独读一次 EXIF(流会被消费),再开新流做渲染
        Integer orientation;
        try (InputStream meta = files.openVersion(versionId)) {
            var exif = exifReader.read(meta);
            orientation = exif == null ? null : exif.orientation();
        }
        try (InputStream in = files.openVersion(versionId)) {
            thumbs.generate(sha, name, in, size, orientation);
        } catch (UnsupportedOperationException e) {
            markThumb(asset, ThumbStatus.SKIPPED);
            return Map.of("generated", false, "skipped", true);
        }
        markReadyIfAllSizes(asset, sha);
        return Map.of("generated", true, "size", size);
    }

    /**
     * READY 的语义是"256 与 512 两个派生文件都存在":只生成一个尺寸时保持 PENDING,
     * 避免 512 失败而状态已 READY。若发现历史 READY 状态与实际派生文件不一致,回退 PENDING。
     */
    private void markReadyIfAllSizes(MediaAsset asset, String sha) {
        if (asset == null) {
            return;
        }
        boolean allSizes = true;
        for (int required : REQUIRED_SIZES) {
            if (!derived.exists(sha, required)) {
                allSizes = false;
                break;
            }
        }
        ThumbStatus next = allSizes ? ThumbStatus.READY : ThumbStatus.PENDING;
        if (asset.getThumbStatus() != next) {
            markThumb(asset, next);
        }
    }

    /** 仓库方法自带只读事务,返回的实体已脱离持久化上下文,必须显式 save 才能落库。 */
    private void markThumb(MediaAsset asset, ThumbStatus status) {
        if (asset != null) {
            asset.markThumb(status);
            assets.save(asset);
        }
    }
}
