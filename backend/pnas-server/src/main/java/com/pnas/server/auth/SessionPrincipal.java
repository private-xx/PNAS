package com.pnas.server.auth;

import com.pnas.server.iam.domain.User;
import java.security.Principal;
import java.util.UUID;

/** 当前会话认证主体:由 {@code SessionAuthFilter} 从会话 Cookie 解析后注入安全上下文。 */
public record SessionPrincipal(UUID userId, String username, User.Role role, UUID sessionId)
        implements Principal {
    @Override public String getName() { return username; }
    public boolean isAdmin() { return role == User.Role.ADMIN; }
}
