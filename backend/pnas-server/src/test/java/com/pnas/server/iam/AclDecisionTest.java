package com.pnas.server.iam;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.domain.Node;
import com.pnas.server.iam.domain.Group;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ACL 判定矩阵(FR-AUTH-06):默认拒绝 / owner 全权 / 继承 / 显式拒绝优先 / 组授权 / 管理权限。 */
@Transactional
class AclDecisionTest extends AbstractIntegrationTest {

    @Autowired AclService acl;
    @Autowired FilesService files;
    @Autowired UserRepository users;
    @Autowired GroupRepository groups;
    @Autowired NodeRepository nodes;
    @Autowired PasswordEncoder encoder;

    private User user(String name) {
        return users.save(User.create(name, name, encoder.encode("secret123"), User.Role.MEMBER));
    }

    private SessionPrincipal principal(User u) {
        return new SessionPrincipal(u.getId(), u.getUsername(), u.getRole(), UUID.randomUUID());
    }

    @Test
    void defaultDenyAndOwnerFullAccess() {
        var alice = user("acl-alice");
        var bob = user("acl-bob");
        Node home = files.ensureUserHome(alice);

        assertThat(acl.hasPermission(principal(alice), home.getId(), 'r')).isTrue();  // owner 全权
        assertThat(acl.hasPermission(principal(bob), home.getId(), 'r')).isFalse();  // 默认拒绝
    }

    @Test
    void grantOnParentInheritsToChild() {
        var alice = user("acl-alice2");
        var bob = user("acl-bob2");
        Node home = files.ensureUserHome(alice);
        Node sub = nodes.save(Node.dir(alice, home, "sub"));

        acl.setAcl(principal(alice), home.getId(), List.of(
            new AclService.EntryDto("USER", bob.getId(), "r", true)));

        assertThat(acl.hasPermission(principal(bob), sub.getId(), 'r')).isTrue();
        assertThat(acl.hasPermission(principal(bob), sub.getId(), 'w')).isFalse();
    }

    @Test
    void explicitDenyOnChildOverridesParentGrant() {
        var alice = user("acl-alice3");
        var bob = user("acl-bob3");
        Node home = files.ensureUserHome(alice);
        Node privateDir = nodes.save(Node.dir(alice, home, "private"));
        Node openDir = nodes.save(Node.dir(alice, home, "open"));

        acl.setAcl(principal(alice), home.getId(), List.of(
            new AclService.EntryDto("USER", bob.getId(), "rw", true)));
        acl.setAcl(principal(alice), privateDir.getId(), List.of(
            new AclService.EntryDto("USER", bob.getId(), "-", true)));

        assertThat(acl.hasPermission(principal(bob), openDir.getId(), 'r')).isTrue();
        assertThat(acl.hasPermission(principal(bob), privateDir.getId(), 'r')).isFalse(); // 拒绝优先
    }

    @Test
    void groupGrantAppliesToMembersOnly() {
        var alice = user("acl-alice4");
        var bob = user("acl-bob4");
        var carol = user("acl-carol4");
        var fam = Group.create("acl-fam4");
        fam.getUsers().add(bob);
        fam = groups.save(fam);

        Node home = files.ensureUserHome(alice);
        acl.setAcl(principal(alice), home.getId(), List.of(
            new AclService.EntryDto("GROUP", fam.getId(), "rw", true)));

        assertThat(acl.hasPermission(principal(bob), home.getId(), 'w')).isTrue();
        assertThat(acl.hasPermission(principal(carol), home.getId(), 'w')).isFalse();
    }

    @Test
    void nonOwnerCannotManageAcl() {
        var alice = user("acl-alice5");
        var bob = user("acl-bob5");
        Node home = files.ensureUserHome(alice);

        assertThatThrownBy(() -> acl.setAcl(principal(bob), home.getId(), List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权管理");
        assertThat(acl.canManage(principal(alice), home.getId())).isTrue();
    }

    @Test
    void adminBypassesAclChain() {
        var alice = user("acl-alice6");
        var admin = users.findByUsername("admin").orElseThrow();
        Node home = files.ensureUserHome(alice);

        // 无任何 ACL 条目时成员被拒,ADMIN 短路放行
        assertThat(acl.hasPermission(principal(admin), home.getId(), 'r')).isTrue();
        assertThat(acl.hasPermission(principal(admin), home.getId(), 'w')).isTrue();
        assertThat(acl.canManage(principal(admin), home.getId())).isTrue();
    }

    @Test
    void duplicatePrincipalInOneRequestIsRejected() {
        var alice = user("acl-alice7");
        var bob = user("acl-bob7");
        Node home = files.ensureUserHome(alice);

        assertThatThrownBy(() -> acl.setAcl(principal(alice), home.getId(), List.of(
            new AclService.EntryDto("USER", bob.getId(), "r", true),
            new AclService.EntryDto("USER", bob.getId(), "rw", true))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("重复");
    }

    @Test
    void nonInheritingEntryDoesNotGrantDescendant() {
        var alice = user("acl-alice9");
        var bob = user("acl-bob9");
        Node home = files.ensureUserHome(alice);
        Node sub = nodes.save(Node.dir(alice, home, "no-inherit"));

        // 在 home 上给 bob 授权,但显式 inherit=false → 子目录不应继承
        acl.setAcl(principal(alice), home.getId(), List.of(
            new AclService.EntryDto("USER", bob.getId(), "r", false)));

        assertThat(acl.hasPermission(principal(bob), home.getId(), 'r')).isTrue();  // 目标节点自身条目生效
        assertThat(acl.hasPermission(principal(bob), sub.getId(), 'r')).isFalse();  // 不向下继承
    }

    @Test
    void ancestorDenyBlocksNearerGrant() {
        var alice = user("acl-alice10");
        var bob = user("acl-bob10");
        Node home = files.ensureUserHome(alice);
        Node sub = nodes.save(Node.dir(alice, home, "sub10"));

        // 祖先 DENY + 更近层级 GRANT:显式拒绝全链优先
        acl.setAcl(principal(alice), home.getId(), List.of(
            new AclService.EntryDto("USER", bob.getId(), "-", true)));
        acl.setAcl(principal(alice), sub.getId(), List.of(
            new AclService.EntryDto("USER", bob.getId(), "r", true)));

        assertThat(acl.hasPermission(principal(bob), sub.getId(), 'r')).isFalse();
    }
}
