package com.pnas.server.web.security;

import com.pnas.server.auth.ServerSessionRepository;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.DeviceRepository;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** 无状态会话认证:从 PNAS_SESSION Cookie 解析会话,校验有效性并注入安全上下文。 */
public class SessionAuthFilter extends OncePerRequestFilter {

    private final ServerSessionRepository sessions;
    private final UserRepository users;
    private final DeviceRepository devices;

    public SessionAuthFilter(ServerSessionRepository sessions, UserRepository users,
                             DeviceRepository devices) {
        this.sessions = sessions;
        this.users = users;
        this.devices = devices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = req.getHeader("Authorization");
        // scheme 不区分大小写;只要 header 呈 Bearer 形态,就绝不再回退到 Cookie 认证
        // (空令牌、畸形令牌同样按未认证处理),避免两种凭证语义混淆。
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer", 0, 6)) {
            // 清掉可能残留的线程上下文,保证 Bearer 分支的认证结果只来自本次解析。
            SecurityContextHolder.clearContext();
            String raw = authorization.length() > 6 ? authorization.substring(6).trim() : "";
            // 空白令牌不查库、不认证,保持未认证 → 由入口点统一返回 401。
            if (!raw.isEmpty()) {
                devices.findByPatHashAndRevokedAtIsNull(sha256(raw)).ifPresent(device ->
                    users.findById(device.getUser().getId()).ifPresent(u -> {
                        if (u.getStatus() != User.Status.ACTIVE) return;
                        var principal = new SessionPrincipal(u.getId(), u.getUsername(),
                            u.getRole(), null);
                        var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(
                            new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        req.setAttribute(SessionPrincipal.class.getName(), principal);
                        req.setAttribute("pnas.deviceId", device.getId());
                        // 定向 UPDATE:仅在未吊销且超过 60s 节流窗口时刷新,避免读-改-写
                        // 把并发吊销的设备又"复活"。
                        devices.touch(device.getId(), Instant.now(), Instant.now().minusSeconds(60));
                    }));
            }
            chain.doFilter(req, res);
            return;
        }
        String cookieValue = readCookieValue(req);
        if (cookieValue != null) {
            String hash = sha256(cookieValue);
            sessions.findByTokenHash(hash).ifPresent(s -> {
                if (s.isValid(Instant.now())) {
                    users.findById(s.getUser().getId()).ifPresent(u -> {
                        if (u.getStatus() == User.Status.ACTIVE) {
                            var principal = new SessionPrincipal(u.getId(), u.getUsername(),
                                u.getRole(), s.getId());
                            var auth = new UsernamePasswordAuthenticationToken(
                                principal, null, List.of(
                                new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            req.setAttribute(SessionPrincipal.class.getName(), principal);
                        }
                    });
                }
            });
        }
        chain.doFilter(req, res);
    }

    public static String sha256(String value) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 读取 PNAS_SESSION Cookie 的原始值;无该 Cookie 时返回 null。 */
    public static String readCookieValue(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) if (SecurityConfig.COOKIE.equals(c.getName())) return c.getValue();
        }
        // MockMvc 以 "Cookie" 头模拟 Cookie,不填充 getCookies();兜底解析原始头。
        String header = req.getHeader("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && SecurityConfig.COOKIE.equals(p.substring(0, eq).trim())) {
                return p.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
