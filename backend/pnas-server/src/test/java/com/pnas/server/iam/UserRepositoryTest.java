package com.pnas.server.iam;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.iam.domain.Group;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户与组映射测试:User/Group 实体对 V1 表 users/groups/group_members 的持久化。
 * 类级 {@link Transactional}:每个用例独立回滚,互不污染。
 */
@Transactional
class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired UserRepository users;
    @Autowired GroupRepository groups;

    @Test
    void persistsAndFindsUser() {
        users.save(User.create("alice", "Alice", "hash-1", User.Role.MEMBER));
        assertThat(users.findByUsername("alice")).isPresent();
        assertThat(users.existsByUsername("alice")).isTrue();
        // 共享单例库中已存在启动引导的 admin(以及其它集成测试提交的用户),故只断言 alice 在场而非绝对数量。
        assertThat(users.findAllByOrderByCreatedAtDesc())
            .extracting(User::getUsername)
            .contains("alice");
    }

    @Test
    void persistsGroupWithMembersViaJoinTable() {
        User alice = users.save(User.create("alice", "Alice", "hash-1", User.Role.MEMBER));
        Group devs = Group.create("devs");
        devs.getUsers().add(alice);
        groups.save(devs);

        assertThat(groups.findByName("devs")).isPresent();
        assertThat(groups.existsByName("devs")).isTrue();
        assertThat(groups.findByName("devs").orElseThrow().getUsers())
            .extracting(User::getUsername)
            .containsExactly("alice");
    }
}
