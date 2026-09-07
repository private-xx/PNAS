package com.pnas.server.auth;

import com.pnas.server.auth.domain.ServerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ServerSessionRepository extends JpaRepository<ServerSession, UUID> {
    /**
     * 按 token 哈希查会话,并 join fetch 关联用户。
     *
     * <p>会话的 {@code user} 是 LAZY 关联;安全过滤器在事务外(无 OSIV,
     * {@code spring.jpa.open-in-view=false})调用本方法后需读取 {@code user.getId()},
     * 必须在此处 fetch join 以避免 LazyInitializationException。</p>
     */
    @Query("select s from ServerSession s join fetch s.user where s.tokenHash = :tokenHash")
    Optional<ServerSession> findByTokenHash(@Param("tokenHash") String tokenHash);

    void deleteByUserId(UUID userId);
}
