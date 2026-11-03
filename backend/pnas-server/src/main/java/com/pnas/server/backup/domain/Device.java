package com.pnas.server.backup.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "devices")
@Getter @NoArgsConstructor
public class Device {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private User user;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DevicePlatform platform;
    @Column(name = "pat_hash", nullable = false, unique = true) private String patHash;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    public static Device create(User user, String name, DevicePlatform platform, String patHash) {
        Device d = new Device();
        d.id = UUID.randomUUID();
        d.user = user;
        d.name = name;
        d.platform = platform;
        d.patHash = patHash;
        return d;
    }
    public void touch() { this.lastSeenAt = Instant.now(); }
    public void markRevoked() { this.revokedAt = Instant.now(); }
    public boolean isRevoked() { return revokedAt != null; }
}
