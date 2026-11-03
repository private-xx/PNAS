package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.backup.domain.DevicePlatform;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Bearer 设备令牌鉴权:请求头 {@code Authorization: Bearer <token>} 应以设备所属用户身份通过鉴权,
 * 无需会话 Cookie / X-CSRF,并把命中设备注入请求属性 {@code pnas.deviceId}。
 */
@AutoConfigureMockMvc
class BearerAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired DeviceRepository devices;
    @Autowired UserRepository users;

    private record Session(String cookie, String csrf) {}

    private record Token(String token, UUID deviceId) {}

    @Test
    void bearerTokenAuthenticatesWithoutCookieAndCsrf() throws Exception {
        Token created = createToken();

        var read = mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + created.token()))
            .andExpect(status().isOk())
            .andReturn();
        // 命中设备应记录到请求属性,供上层审计/备份链路使用。
        assertThat(read.getRequest().getAttribute("pnas.deviceId")).isEqualTo(created.deviceId());

        // 写操作同样无需 CSRF(无 Cookie 时守卫放行)。
        mvc.perform(post("/api/v1/nodes")
                .header("Authorization", "Bearer " + created.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":null,\"name\":\"bearer-dir-" + System.nanoTime() + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void revokedOrUnknownTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer not-a-token"))
            .andExpect(status().isUnauthorized());

        Token created = createToken();
        // 吊销前令牌可用(保证下面的 401 来自吊销,而非令牌本身无效)。
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + created.token()))
            .andExpect(status().isOk());

        Session admin = adminSession();
        mvc.perform(delete("/api/v1/devices/{id}", created.deviceId())
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + created.token()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void lastSeenAtIsTouchedAtMostOncePerMinute() throws Exception {
        Token created = createToken();

        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + created.token()))
            .andExpect(status().isOk());
        Instant firstSeen = reload(created.deviceId()).getLastSeenAt();
        assertThat(firstSeen).isNotNull();

        // 60 秒节流窗口内的第二次请求不得再次写库。
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + created.token()))
            .andExpect(status().isOk());
        assertThat(reload(created.deviceId()).getLastSeenAt()).isEqualTo(firstSeen);
    }

    @Test
    void disabledUserTokenIsUnauthorized() throws Exception {
        var user = users.save(User.create("disabled-" + UUID.randomUUID(), "Disabled",
            "not-used-for-token-auth", User.Role.MEMBER));
        user.setStatus(User.Status.DISABLED);
        users.save(user);

        String raw = "disabled-" + UUID.randomUUID();
        devices.save(Device.create(user, "DisabledDevice", DevicePlatform.MAC,
            Sha256.hex(raw.getBytes(StandardCharsets.UTF_8))));

        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + raw))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerHeaderNeverFallsBackToCookieSession() throws Exception {
        Session admin = adminSession();
        // 有效会话 Cookie 单独可用(证明下面的 401 来自 Bearer 头覆盖,而非 Cookie 失效)。
        mvc.perform(get("/api/v1/nodes").header("Cookie", admin.cookie()))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/nodes")
                .header("Cookie", admin.cookie())
                .header("Authorization", "Bearer not-a-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void blankBearerTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer "))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void memberTokenOnlySeesOwnDevices() throws Exception {
        Session admin = adminSession();
        createDevice(admin, "admin-device-" + System.nanoTime());

        String username = "member-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username
                    + "\",\"displayName\":\"M\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());

        Session member = login(username, "secret123");
        Token memberDevice = createDevice(member, "member-device");

        String body = mvc.perform(get("/api/v1/devices")
                .header("Authorization", "Bearer " + memberDevice.token()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$[*].id");
        // 只应看到该成员自己的设备,不能看到 admin 的设备。
        assertThat(ids).containsExactly(memberDevice.deviceId().toString());
    }

    @Test
    void bearerLogoutReturnsNoContentWithoutServerSession() throws Exception {
        Token created = createToken();
        // Bearer 主体没有服务端会话(sessionId == null),登出不得因 findById(null) 而 500。
        mvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + created.token()))
            .andExpect(status().isNoContent());
    }

    private Device reload(UUID deviceId) {
        return devices.findById(deviceId).orElseThrow();
    }

    private Token createToken() throws Exception {
        return createDevice(adminSession(), "BearTest");
    }

    private Token createDevice(Session session, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", session.cookie()).header("X-CSRF", session.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Token(JsonPath.read(body, "$.token"),
            UUID.fromString(JsonPath.read(body, "$.device.id")));
    }

    private Session login(String username, String password) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return new Session(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }

    private Session adminSession() throws Exception {
        return login("admin", "admin-secret");
    }
}
