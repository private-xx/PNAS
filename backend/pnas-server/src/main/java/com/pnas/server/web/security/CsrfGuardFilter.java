package com.pnas.server.web.security;

import com.pnas.server.auth.ServerSessionRepository;
import com.pnas.server.common.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** 无状态会话下的 CSRF 强制校验:非安全方法必须携带与会话 csrf_hash 匹配的 X-CSRF 头。 */
public class CsrfGuardFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final ServerSessionRepository sessions;
    private final ObjectMapper mapper = new ObjectMapper();

    public CsrfGuardFilter(ServerSessionRepository sessions) { this.sessions = sessions; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (SAFE.contains(req.getMethod())) { chain.doFilter(req, res); return; }
        String cookie = SessionAuthFilter.readCookieValue(req);
        // 未携带会话 Cookie(如登录等未认证请求)无会话可防 CSRF,放行交由授权层处理。
        if (cookie == null) { chain.doFilter(req, res); return; }
        String header = req.getHeader(SecurityConfig.CSRF_HEADER);
        boolean ok = header != null
            && sessions.findByTokenHash(SessionAuthFilter.sha256(cookie))
                .map(s -> s.getCsrfHash().equals(SessionAuthFilter.sha256(header)))
                .orElse(false);
        if (!ok) {
            res.setStatus(403);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getWriter(), new ApiError("forbidden", "CSRF 校验失败", null));
            return;
        }
        chain.doFilter(req, res);
    }
}
