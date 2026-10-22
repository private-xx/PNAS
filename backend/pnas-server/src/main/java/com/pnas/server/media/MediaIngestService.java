package com.pnas.server.media;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.domain.FileVersion;
import com.pnas.server.files.domain.Node;
import com.pnas.server.media.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 幂等入库:判定类型 → 提取元数据 → upsert media_assets(spec §5)。 */
@Service
public class MediaIngestService {

    public record IngestResult(UUID assetId, MediaKind kind, ThumbStatus thumbStatus) {}

    private final MediaAssetRepository assets;
    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final FilesService files;
    private final ExifReader exif;
    private final Mp4DurationReader mp4;

    public MediaIngestService(MediaAssetRepository assets, NodeRepository nodes,
                              FileVersionRepository versions, FilesService files,
                              ExifReader exif, Mp4DurationReader mp4) {
        this.assets = assets;
        this.nodes = nodes;
        this.versions = versions;
        this.files = files;
        this.exif = exif;
        this.mp4 = mp4;
    }

    @Transactional
    public Optional<IngestResult> ingest(UUID nodeId, UUID versionId) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "节点不存在: " + nodeId));
        FileVersion version = versions.findById(versionId).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文件版本不存在: " + versionId));

        MediaKind kind = MediaTypes.classify(node.getName(), version.getMimeType());
        if (kind == null) {
            return Optional.empty();
        }
        ThumbStatus status = MediaTypes.canThumbnail(kind, node.getName())
            ? ThumbStatus.PENDING
            : ThumbStatus.SKIPPED;

        Metadata md = kind == MediaKind.IMAGE ? readImage(versionId) : readVideo(versionId);
        // 无 EXIF 拍摄时间时回退到文件版本写入时间(R13)
        var takenAt = md.takenAt() != null ? md.takenAt() : version.getCreatedAt();
        var source = md.takenAt() != null ? TakenAtSource.EXIF : TakenAtSource.MTIME;

        MediaAsset asset = assets.findByFileVersionId(versionId).orElse(null);
        if (asset == null) {
            asset = assets.save(MediaAsset.create(versionId, nodeId, kind, takenAt, source,
                md.width(), md.height(), md.gpsLat(), md.gpsLon(), md.durationMs(), status));
        } else {
            asset.updateMetadata(takenAt, source, md.width(), md.height(), md.gpsLat(), md.gpsLon(),
                md.durationMs());
            asset = assets.save(asset);
        }
        return Optional.of(new IngestResult(asset.getId(), kind, asset.getThumbStatus()));
    }

    /** 记录元数据的中间载体。 */
    private record Metadata(Instant takenAt, Integer width, Integer height,
                            Double gpsLat, Double gpsLon, Long durationMs) {}

    private Metadata readImage(UUID versionId) {
        try (InputStream in = files.openVersion(versionId)) {
            var data = exif.read(in);
            return new Metadata(data.takenAt(), data.width(), data.height(), data.gpsLat(),
                data.gpsLon(), null);
        } catch (IOException e) {
            throw new UncheckedIOException("读取图片内容失败", e);
        }
    }

    private Metadata readVideo(UUID versionId) {
        try (InputStream in = files.openVersion(versionId)) {
            return new Metadata(null, null, null, null, null, mp4.readDurationMs(in));
        } catch (IOException e) {
            throw new UncheckedIOException("读取视频内容失败", e);
        }
    }
}
