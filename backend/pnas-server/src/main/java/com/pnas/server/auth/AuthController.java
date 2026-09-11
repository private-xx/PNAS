package com.pnas.server.auth;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.common.PnasProperties;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final UserRepository users;
    private final PnasProperties props;

    public AuthController(AuthService auth, UserRepository users, PnasProperties props) {
        this.auth = auth;
        this.users = users;
        this.props = props;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest req, HttpServletResponse res) {
        String combined = auth.createSession(req.username(), req.password());
        String[] parts = combined.split("\\|", 2);
        ResponseCookie c = ResponseCookie.from("PNAS_SESSION", parts[0])
            .httpOnly(true)
            .secure(props.security().requireTls())
            .sameSite("Lax")
            .path("/")
            .maxAge(30 * 24 * 3600)
            .build();
        res.addHeader(HttpHeaders.SET_COOKIE, c.toString());
        // 双提交 Cookie:供 SPA 在刷新后重新取得 CSRF(非 HttpOnly,同源脚本可读)
        ResponseCookie csrfCookie = ResponseCookie.from("PNAS_CSRF", parts[1])
            .httpOnly(false)
            .secure(props.security().requireTls())
            .sameSite("Lax")
            .path("/")
            .maxAge(30 * 24 * 3600)
            .build();
        res.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
        res.setHeader("X-CSRF-Token", parts[1]);
        return Map.of("status", "ok");
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal SessionPrincipal me, HttpServletResponse res) {
        auth.logout(me.sessionId());
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("PNAS_CSRF", "")
            .httpOnly(false).sameSite("Lax").path("/").maxAge(0).build().toString());
    }

    @GetMapping("/session")
    public Map<String, Object> session(@AuthenticationPrincipal SessionPrincipal me) {
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "未登录");
        }
        var u = users.findById(me.userId()).orElseThrow();
        return Map.of("username", u.getUsername(),
            "displayName", u.getDisplayName(),
            "role", u.getRole().name());
    }
}
