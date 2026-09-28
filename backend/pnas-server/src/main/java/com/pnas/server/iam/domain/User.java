package com.pnas.server.iam.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "users")
@Getter @NoArgsConstructor
public class User {
    public enum Role { ADMIN, MEMBER }
    public enum Status { ACTIVE, DISABLED }

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public static User create(String username, String displayName, String passwordHash, Role role) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username 不能为空");
        var u = new User();
        u.username = username.trim();
        u.displayName = displayName;
        u.passwordHash = passwordHash;
        u.role = role;
        return u;
    }
}
