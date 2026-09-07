package com.pnas.server.files.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "file_versions")
@Getter @Setter @NoArgsConstructor
public class FileVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id")
    private Node node;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "mime_type", nullable = false)
    private String mimeType = "application/octet-stream";

    @Column(name = "manifest_sha256", nullable = false)
    private String manifestSha256;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
