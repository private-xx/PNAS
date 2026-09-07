package com.pnas.server.auth;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
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

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest req, HttpServletResponse res) {
        String combined = auth.createSession(req.username(), req.password());
        String[] parts = combined.split("\\|", 2);
        Cookie c = new Cookie("PNAS_SESSION", parts[0]);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(30 * 24 * 3600);
        res.addCookie(c);
        res.setHeader("X-CSRF-Token", parts[1]);
        return Map.of("status", "ok");
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal SessionPrincipal me) {
        auth.logout(me.sessionId());
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
