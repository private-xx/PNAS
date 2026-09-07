package com.pnas.server.auth;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.domain.ServerSession;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;

/** 会话生命周期:登录(创建会话)、登出(吊销)。CSRF 强校验由 {@code CsrfGuardFilter} 统一负责。 */
@Service
public class AuthService {
    private static final Duration TTL = Duration.ofDays(30);
    private final UserRepository users;
    private final ServerSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final Random random = new Random();

    public AuthService(UserRepository users, ServerSessionRepository sessions, PasswordEncoder encoder) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
    }

    @Transactional
    public String createSession(String username, String rawPassword) {
        User u = users.findByUsername(username)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!encoder.matches(rawPassword, u.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = randomToken();
        String csrf = randomToken();
        var session = ServerSession.create(u, sha(token),
            sha(csrf), Instant.now(), TTL);
        sessions.save(session);
        return token + "|" + csrf;
    }

    @Transactional
    public void logout(UUID sessionId) {
        sessions.findById(sessionId).ifPresent(s -> s.revoke(Instant.now()));
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** sha256(hex) —— 供会话令牌哈希与 CSRF 哈希使用(与 web 层过滤器实现一致)。 */
    static String sha(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
