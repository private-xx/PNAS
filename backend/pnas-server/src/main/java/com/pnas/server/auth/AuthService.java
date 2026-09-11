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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 会话生命周期:登录(创建会话)、登出(吊销)。CSRF 强校验由 {@code CsrfGuardFilter} 统一负责。 */
@Service
public class AuthService {
    private static final Duration TTL = Duration.ofDays(30);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_TIME = Duration.ofMinutes(5);

    private final UserRepository users;
    private final ServerSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();
    /** 用户名 → 连续失败计数与锁定截止(内存实现;单机 M2,M5 加固时改为持久化 + IP 维度)。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Attempt> attempts =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 用于用户名不存在时也执行一次哈希比对,消除用户名枚举的时序差异。 */
    private final String timingGuardHash;

    private record Attempt(int failures, Instant lockedUntil) {}

    public AuthService(UserRepository users, ServerSessionRepository sessions, PasswordEncoder encoder) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.timingGuardHash = encoder.encode("timing-guard-not-a-real-password");
    }

    @Transactional
    public String createSession(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        Attempt attempt = attempts.get(name);
        if (attempt != null && attempt.lockedUntil() != null
            && attempt.lockedUntil().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, HttpStatus.TOO_MANY_REQUESTS,
                "登录失败次数过多,请在 " + LOCK_TIME.toMinutes() + " 分钟后重试");
        }
        User u = users.findByUsername(name).orElse(null);
        boolean ok = encoder.matches(rawPassword == null ? "" : rawPassword,
            u != null ? u.getPasswordHash() : timingGuardHash);
        if (u == null || !ok) {
            registerFailure(name, attempt);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        attempts.remove(name);
        String token = randomToken();
        String csrf = randomToken();
        var session = ServerSession.create(u, sha(token),
            sha(csrf), Instant.now(), TTL);
        sessions.save(session);
        return token + "|" + csrf;
    }

    /** 连续失败计数;达到阈值则锁定一段时间(FR-AUTH-03 失败锁定 + 退避)。 */
    private void registerFailure(String name, Attempt previous) {
        int failures = (previous == null ? 0 : previous.failures()) + 1;
        Instant lockedUntil = failures >= MAX_FAILURES ? Instant.now().plus(LOCK_TIME) : null;
        attempts.put(name, new Attempt(failures >= MAX_FAILURES ? 0 : failures, lockedUntil));
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
