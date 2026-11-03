package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class DeviceApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private record Auth(String cookie, String csrf) {}

    private record Created(String id, String token) {}

    private Auth login(String user, String pass) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return new Auth(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }

    private Created createDevice(Auth auth, String name, String platform) throws Exception {
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", auth.cookie()).header("X-CSRF", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"platform\":\"" + platform + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Created(JsonPath.read(body, "$.device.id"), JsonPath.read(body, "$.token"));
    }

    @Test
    void createListAndRevokeDevice() throws Exception {
        var admin = login("admin", "admin-secret");
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"MacBook\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.device.platform").value("MAC"))
            .andExpect(jsonPath("$.device.createdAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        String deviceId = JsonPath.read(body, "$.device.id");
        String token = JsonPath.read(body, "$.token");
        UUID deviceUuid = UUID.fromString(deviceId);

        // 令牌形态:32 字节随机数的 base64url(无填充)= 43 个 URL 安全字符。
        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");

        // 哈希落盘:库中只存 SHA-256 hex,绝无明文。
        String patHash = jdbc.queryForObject(
            "select pat_hash from devices where id = ?", String.class, deviceUuid);
        assertThat(patHash).isNotEqualTo(token);
        assertThat(patHash).matches("^[0-9a-f]{64}$");
        assertThat(patHash).isEqualTo(Sha256.hex(token.getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/v1/devices").header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItem("MacBook")));

        // 列表不返回明文 token
        String list = mvc.perform(get("/api/v1/devices").header("Cookie", admin.cookie()))
            .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(token);

        // 吊销后列表须反映 revoked=true。
        mvc.perform(delete("/api/v1/devices/{id}", deviceId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());
        String revokedList = mvc.perform(get("/api/v1/devices").header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Boolean> revokedFlags = JsonPath.read(revokedList,
            "$[?(@.id == '" + deviceId + "')].revoked");
        assertThat(revokedFlags).containsExactly(true);

        Timestamp firstRevokedAt = jdbc.queryForObject(
            "select revoked_at from devices where id = ?", Timestamp.class, deviceUuid);
        assertThat(firstRevokedAt).isNotNull();

        // 重复吊销幂等:仍 204,且不重写 revoked_at。
        mvc.perform(delete("/api/v1/devices/{id}", deviceId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());
        Timestamp secondRevokedAt = jdbc.queryForObject(
            "select revoked_at from devices where id = ?", Timestamp.class, deviceUuid);
        assertThat(secondRevokedAt).isEqualTo(firstRevokedAt);
    }

    @Test
    void tokenIsUrlSafeAndUniquePerCreate() throws Exception {
        var admin = login("admin", "admin-secret");
        Created first = createDevice(admin, "token-a-" + System.nanoTime(), "MAC");
        Created second = createDevice(admin, "token-b-" + System.nanoTime(), "MAC");

        assertThat(first.token()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(second.token()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(second.token()).isNotEqualTo(first.token());
    }

    @Test
    void memberCannotRevokeAnotherUsersDevice() throws Exception {
        var admin = login("admin", "admin-secret");
        Created device = createDevice(admin, "admin-device-" + System.nanoTime(), "MAC");

        String username = "member-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username
                    + "\",\"displayName\":\"M\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MEMBER"));

        var member = login(username, "secret123");
        mvc.perform(delete("/api/v1/devices/{id}", device.id())
                .header("Cookie", member.cookie()).header("X-CSRF", member.csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void invalidCreateInputIs400() throws Exception {
        var admin = login("admin", "admin-secret");
        // 空白设备名
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"platform\":\"MAC\"}"))
            .andExpect(status().isBadRequest());
        // name 缺失(null)
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"MAC\"}"))
            .andExpect(status().isBadRequest());
        // 未知 platform
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"platform\":\"LINUX\"}"))
            .andExpect(status().isBadRequest());
        // platform 仅空白
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"win\",\"platform\":\"   \"}"))
            .andExpect(status().isBadRequest());
        // 小写 platform 归一化为大写
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"win\",\"platform\":\"windows\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.device.platform").value("WINDOWS"));
    }
}
