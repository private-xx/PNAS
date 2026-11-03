package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class DeviceApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private record Auth(String cookie, String csrf) {}

    private Auth login(String user, String pass) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return new Auth(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
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

        mvc.perform(get("/api/v1/devices").header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItem("MacBook")));

        // 列表不返回明文 token
        String list = mvc.perform(get("/api/v1/devices").header("Cookie", admin.cookie()))
            .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(token);

        mvc.perform(delete("/api/v1/devices/{id}", deviceId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    void invalidCreateInputIs400() throws Exception {
        var admin = login("admin", "admin-secret");
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"platform\":\"MAC\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"platform\":\"LINUX\"}"))
            .andExpect(status().isBadRequest());
    }
}
