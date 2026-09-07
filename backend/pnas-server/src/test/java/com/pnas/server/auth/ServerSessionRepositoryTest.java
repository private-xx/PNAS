package com.pnas.server.auth;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.auth.domain.ServerSession;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话仓库测试:按 tokenHash 查找 + 生命周期(isValid/revoke/过期)+ deleteByUserId 生效。
 * 类级 {@link Transactional}:每个用例独立回滚,互不污染。
 */
@Transactional
class ServerSessionRepositoryTest extends AbstractIntegrationTest {

    @Autowired ServerSessionRepository sessions;
    @Autowired UserRepository users;

    @Test
    void findsSessionByTokenHashAndTracksLifetime() {
        User bob = users.save(User.create("bob", "Bob", "hash-1", User.Role.MEMBER));
        Instant now = Instant.now();
        ServerSession saved = sessions.save(
            ServerSession.create(bob, "tok-abc", "csrf-abc", now, Duration.ofMinutes(30)));

        ServerSession found = sessions.findByTokenHash("tok-abc").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getUser().getId()).isEqualTo(bob.getId());

        // 未吊销且未过期 -> 有效
        assertThat(found.isValid(now.plusSeconds(60))).isTrue();
        // 已过期 -> 无效
        assertThat(found.isValid(now.plus(Duration.ofMinutes(31)))).isFalse();
        // 吊销后 -> 无效
        found.revoke(now);
        assertThat(found.isValid(now)).isFalse();
    }

    @Test
    void deleteByUserIdRemovesOnlyThatUsersSessions() {
        User dave = users.save(User.create("dave", "Dave", "hash-2", User.Role.MEMBER));
        User erin = users.save(User.create("erin", "Erin", "hash-3", User.Role.MEMBER));
        sessions.save(ServerSession.create(dave, "tok-d1", "csrf-1", Instant.now(), Duration.ofHours(1)));
        sessions.save(ServerSession.create(dave, "tok-d2", "csrf-2", Instant.now(), Duration.ofHours(1)));
        sessions.save(ServerSession.create(erin, "tok-e1", "csrf-3", Instant.now(), Duration.ofHours(1)));
        sessions.flush();

        sessions.deleteByUserId(dave.getId());

        assertThat(sessions.findByTokenHash("tok-d1")).isEmpty();
        assertThat(sessions.findByTokenHash("tok-d2")).isEmpty();
        // 其他用户的会话不受影响
        assertThat(sessions.findByTokenHash("tok-e1")).isPresent();
    }
}
