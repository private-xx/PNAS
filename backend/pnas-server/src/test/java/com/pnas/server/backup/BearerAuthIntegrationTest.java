package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.backup.domain.Device;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
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

    private Device reload(UUID deviceId) {
        return devices.findById(deviceId).orElseThrow();
    }

    private Token createToken() throws Exception {
        Session admin = adminSession();
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"BearTest\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Token(JsonPath.read(body, "$.token"), UUID.fromString(JsonPath.read(body, "$.device.id")));
    }

    private Session adminSession() throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
            .andExpect(status().isOk()).andReturn();
        return new Session(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }
}
