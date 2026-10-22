package com.pnas.server.media.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 媒体时间线索引(spec §4);每次覆盖写产生新版本即产生一条新记录。 */
@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    private UUID id;

    @Column(name = "file_version_id", nullable = false, unique = true)
    private UUID fileVersionId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaKind kind;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "taken_at_source", nullable = false)
    private TakenAtSource takenAtSource;

    private Integer width;
    private Integer height;

    @Column(name = "gps_lat")
    private Double gpsLat;

    @Column(name = "gps_lon")
    private Double gpsLon;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "thumb_status", nullable = false)
    private ThumbStatus thumbStatus;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    protected MediaAsset() {}

    public static MediaAsset create(UUID fileVersionId, UUID nodeId, MediaKind kind, Instant takenAt,
                                    TakenAtSource source, Integer width, Integer height,
                                    Double gpsLat, Double gpsLon, Long durationMs, ThumbStatus status) {
        var a = new MediaAsset();
        a.id = UUID.randomUUID();
        a.fileVersionId = fileVersionId;
        a.nodeId = nodeId;
        a.kind = kind;
        a.takenAt = takenAt;
        a.takenAtSource = source;
        a.width = width;
        a.height = height;
        a.gpsLat = gpsLat;
        a.gpsLon = gpsLon;
        a.durationMs = durationMs;
        a.thumbStatus = status;
        a.indexedAt = Instant.now();
        return a;
    }

    public void markThumb(ThumbStatus status) { this.thumbStatus = status; }
    public void updateMetadata(Instant takenAt, TakenAtSource source, Integer width, Integer height,
                               Double gpsLat, Double gpsLon, Long durationMs) {
        this.takenAt = takenAt;
        this.takenAtSource = source;
        this.width = width;
        this.height = height;
        this.gpsLat = gpsLat;
        this.gpsLon = gpsLon;
        this.durationMs = durationMs;
        this.indexedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getFileVersionId() { return fileVersionId; }
    public UUID getNodeId() { return nodeId; }
    public MediaKind getKind() { return kind; }
    public Instant getTakenAt() { return takenAt; }
    public TakenAtSource getTakenAtSource() { return takenAtSource; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public Double getGpsLat() { return gpsLat; }
    public Double getGpsLon() { return gpsLon; }
    public Long getDurationMs() { return durationMs; }
    public ThumbStatus getThumbStatus() { return thumbStatus; }
    public Instant getIndexedAt() { return indexedAt; }
}
