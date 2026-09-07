package com.pnas.server.iam;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** IAM API:用户管理(仅 ADMIN)。 */
@RestController
@RequestMapping("/api/v1/users")
public class IamController {
    private final UserService users;

    public IamController(UserService users) { this.users = users; }

    public record CreateUser(String username, String displayName, String password) {}

    @PostMapping
    public Map<String, Object> create(@AuthenticationPrincipal SessionPrincipal me,
                                      @RequestBody CreateUser req) {
        if (me == null || !me.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "仅管理员可建用户");
        }
        User u = users.createMember(req.username(), req.displayName(), req.password());
        return Map.of("id", u.getId(), "username", u.getUsername(), "role", u.getRole().name());
    }
}
