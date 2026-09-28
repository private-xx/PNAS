package com.pnas.server.auth.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "http_sessions")
@Getter @NoArgsConstructor
public class ServerSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "csrf_hash", nullable = false)
    private String csrfHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public static ServerSession create(User user, String tokenHash, String csrfHash,
                                       Instant now, Duration ttl) {
        var s = new ServerSession();
        s.user = user;
        s.tokenHash = tokenHash;
        s.csrfHash = csrfHash;
        s.expiresAt = now.plus(ttl);
        return s;
    }

    public boolean isValid(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant now) { this.revokedAt = now; }
}
