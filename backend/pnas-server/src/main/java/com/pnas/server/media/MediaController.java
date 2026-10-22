package com.pnas.server.media;

import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.blobstore.DerivedStore;
import com.pnas.server.common.error.BusinessException;
import com.pnas.common.error.ErrorCode;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.RangeSpec;
import com.pnas.server.job.JobService;
import com.pnas.server.media.domain.MediaAsset;
import com.pnas.server.media.domain.MediaKind;
import com.pnas.server.media.domain.ThumbStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 媒体读取端点(spec §6);所有读取都经服务端 ACL,无权一律 404。 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaTimelineService timeline;
    private final MediaAssetRepository assets;
    private final FileVersionRepository versions;
    private final NodeRepository nodes;
    private final DerivedStore derived;
    private final FilesService files;
    private final JobService jobs;

    public MediaController(MediaTimelineService timeline, MediaAssetRepository assets,
                           FileVersionRepository versions, NodeRepository nodes, DerivedStore derived,
                           FilesService files, JobService jobs) {
        this.timeline = timeline;
        this.assets = assets;
        this.versions = versions;
        this.nodes = nodes;
        this.derived = derived;
        this.files = files;
        this.jobs = jobs;
    }

    public record AssetDetail(UUID assetId, UUID nodeId, String name, String kind, java.time.Instant takenAt,
                              String takenAtSource, Integer width, Integer height, Long durationMs,
                              String thumbStatus, boolean hasGps, long sizeBytes) {}

    @GetMapping("/timeline")
    public List<MediaTimelineService.TimelineItem> timeline(
            @AuthenticationPrincipal SessionPrincipal me,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "60") int limit,
            @RequestParam(required = false) String kind) {
        return timeline.timeline(me, cursor, limit, kind == null ? null : MediaKind.valueOf(kind));
    }

    @GetMapping("/timeline/buckets")
    public List<MediaTimelineService.PeriodCount> buckets(
            @AuthenticationPrincipal SessionPrincipal me,
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(required = false) String kind) {
        int y = year == 0 ? java.time.Year.now().getValue() : year;
        return timeline.buckets(me, y, kind == null ? null : MediaKind.valueOf(kind));
    }

    @GetMapping("/albums/dates")
    public List<MediaTimelineService.YearMonths> albumsDates(@AuthenticationPrincipal SessionPrincipal me) {
        return timeline.albumsDates(me);
    }

    @GetMapping("/assets/{id}")
    public AssetDetail detail(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id) {
        MediaAsset asset = timeline.requireReadable(me, id);
        var node = nodes.findById(asset.getNodeId()).orElseThrow();
        var version = versions.findById(asset.getFileVersionId()).orElseThrow();
        return new AssetDetail(asset.getId(), asset.getNodeId(), node.getName(), asset.getKind().name(),
            asset.getTakenAt(), asset.getTakenAtSource().name(), asset.getWidth(), asset.getHeight(),
            asset.getDurationMs(), asset.getThumbStatus().name(), asset.getGpsLat() != null,
            version.getSizeBytes());
    }

    @GetMapping("/assets/{id}/thumb")
    public ResponseEntity<byte[]> thumb(@AuthenticationPrincipal SessionPrincipal me,
                                        @PathVariable UUID id,
                                        @RequestParam(defaultValue = "256") int size,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        if (size != 256 && size != 512) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "仅支持 256 或 512 缩略图");
        }
        MediaAsset asset = timeline.requireReadable(me, id);
        if (asset.getThumbStatus() != ThumbStatus.READY) {
            throw new BusinessException(ErrorCode.THUMB_UNSUPPORTED, HttpStatus.NOT_FOUND,
                "该媒体暂无可用缩略图");
        }
        var version = versions.findById(asset.getFileVersionId()).orElseThrow();
        String sha = version.getManifestSha256();
        String etag = "\"" + sha + "-" + size + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        try (InputStream in = derived.open(sha, size)) {
            return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=604800")
                .contentType(MediaType.IMAGE_JPEG)
                .body(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("读取缩略图失败", e);
        }
    }

    @GetMapping("/assets/{id}/content")
    public ResponseEntity<StreamingResponseBody> content(
            @AuthenticationPrincipal SessionPrincipal me,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        MediaAsset asset = timeline.requireReadable(me, id);
        long total = files.sizeOf(me, asset.getNodeId());
        RangeSpec spec = RangeSpec.parse(rangeHeader, total);
        var content = files.content(me, asset.getNodeId(), spec.start(), spec.length());
        StreamingResponseBody body = out -> {
            try (InputStream in = content.stream()) {
                in.transferTo(out);
            }
        };
        var builder = ResponseEntity.status(spec.ranged() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(spec.length()))
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (spec.ranged()) {
            builder.header(HttpHeaders.CONTENT_RANGE,
                "bytes " + spec.start() + "-" + (spec.start() + spec.length() - 1) + "/" + total);
        }
        return builder.body(body);
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@AuthenticationPrincipal SessionPrincipal me) {
        if (!me.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "仅管理员可重建媒体索引");
        }
        UUID jobId = jobs.enqueue("media.reindex", Map.of(), 1, 0);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId.toString()));
    }
}
