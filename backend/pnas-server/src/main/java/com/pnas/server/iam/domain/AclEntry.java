package com.pnas.server.iam.domain;

import com.pnas.server.files.domain.Node;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 目录级访问控制条目(FR-AUTH-06)。
 * perms 语义:包含 `r`/`w`/`d` 表示授予对应权限;包含 `-` 表示显式拒绝(拒绝优先)。
 */
@Entity @Table(name = "acl_entries")
@Getter @Setter @NoArgsConstructor
public class AclEntry {

    public enum PrincipalType { USER, GROUP }

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id")
    private Node node;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false)
    private PrincipalType principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(nullable = false)
    private String perms;

    @Column(nullable = false)
    private boolean inherited = true;

    public static AclEntry of(Node node, PrincipalType type, UUID principalId, String perms, boolean inherited) {
        var e = new AclEntry();
        e.node = node;
        e.principalType = type;
        e.principalId = principalId;
        e.perms = perms;
        e.inherited = inherited;
        return e;
    }
}
