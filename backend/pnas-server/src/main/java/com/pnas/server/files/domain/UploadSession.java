package com.pnas.server.files.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "upload_sessions")
@Getter @Setter @NoArgsConstructor
public class UploadSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dest_parent_id")
    private Node destParent;

    @Column(nullable = false)
    private String filename;

    @Column(name = "total_size", nullable = false)
    private long totalSize;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(nullable = false)
    private String state = "OPEN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "received_chunks", nullable = false)
    private List<Integer> receivedChunks = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chunk_hashes", nullable = false)
    private Map<Integer, String> chunkHashes = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chunk_sizes", nullable = false)
    private Map<Integer, Long> chunkSizes = new HashMap<>();

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
