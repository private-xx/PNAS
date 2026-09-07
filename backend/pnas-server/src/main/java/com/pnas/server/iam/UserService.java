package com.pnas.server.iam;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.common.PnasProperties;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户管理与管理员引导:空库时按配置创建 ADMIN,并提供成员创建。 */
@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PnasProperties props;

    public UserService(UserRepository users, PasswordEncoder encoder, PnasProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Transactional
    public void bootstrapAdmin() {
        String admin = props.bootstrap().adminUsername();
        String pass = props.bootstrap().adminPassword();
        if (admin == null || admin.isBlank() || pass == null || pass.isBlank()) return;
        if (users.existsByUsername(admin)) return;
        users.save(User.create(admin, "管理员", encoder.encode(pass), User.Role.ADMIN));
    }

    @Transactional
    public User createMember(String username, String displayName, String rawPassword) {
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN, HttpStatus.CONFLICT, "用户名已存在");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD, HttpStatus.BAD_REQUEST, "密码至少 8 位");
        }
        return users.save(User.create(username, displayName,
            encoder.encode(rawPassword), User.Role.MEMBER));
    }
}
