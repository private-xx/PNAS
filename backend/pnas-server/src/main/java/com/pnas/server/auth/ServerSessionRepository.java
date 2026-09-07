package com.pnas.server.auth;
import com.pnas.server.auth.domain.ServerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ServerSessionRepository extends JpaRepository<ServerSession, UUID> {
    Optional<ServerSession> findByTokenHash(String tokenHash);
    void deleteByUserId(UUID userId);
}
