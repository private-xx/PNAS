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
            // 幂等:派生文件已存在。仍补写状态,避免"已生成但未来得及标 READY"时永远停在 PENDING。
            markThumb(asset, ThumbStatus.READY);
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
        markThumb(asset, ThumbStatus.READY);
        return Map.of("generated", true, "size", size);
    }

    /** 仓库方法自带只读事务,返回的实体已脱离持久化上下文,必须显式 save 才能落库。 */
    private void markThumb(MediaAsset asset, ThumbStatus status) {
        if (asset != null) {
            asset.markThumb(status);
            assets.save(asset);
        }
    }
}
