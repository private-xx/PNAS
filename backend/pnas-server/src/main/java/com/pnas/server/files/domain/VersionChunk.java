package com.pnas.server.files.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity @Table(name = "version_chunks")
@Getter @Setter @NoArgsConstructor
public class VersionChunk {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id")
    private FileVersion version;

    @Column(nullable = false)
    private int seq;

    @Column(name = "blob_hash", nullable = false)
    private String blobHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
}
