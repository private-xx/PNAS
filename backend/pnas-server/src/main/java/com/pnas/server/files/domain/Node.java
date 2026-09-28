package com.pnas.server.files.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "nodes")
@Getter @Setter @NoArgsConstructor
public class Node {
    public enum Kind { DIR, FILE }

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Node parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String name;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "trashed_at")
    private Instant trashedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public static Node dir(User owner, Node parent, String name) {
        var n = new Node();
        n.owner = owner;
        n.parent = parent;
        n.kind = Kind.DIR;
        n.name = name;
        n.sizeBytes = 0;
        return n;
    }

    public static Node file(User owner, Node parent, String name) {
        var n = new Node();
        n.owner = owner;
        n.parent = parent;
        n.kind = Kind.FILE;
        n.name = name;
        n.sizeBytes = 0;
        return n;
    }
}
