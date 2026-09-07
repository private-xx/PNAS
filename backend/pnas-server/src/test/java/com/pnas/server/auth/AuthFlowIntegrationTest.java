package com.pnas.server.auth;

import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void loginGrantsAccessToProtectedResourceAndLogoutRevokes() throws Exception {
        // 引导管理员(bootstrapAdmin 由测试属性提供:admin / admin-secret)
        String body = """
            {"username":"admin","password":"admin-secret"}
            """;
        var login = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(header().exists("Set-Cookie"))
            .andReturn();
        String cookie = login.getResponse().getHeader("Set-Cookie").split(";")[0];
        String csrf = login.getResponse().getHeader("X-CSRF-Token");

        mvc.perform(get("/api/v1/auth/session").header("Cookie", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"));

        mvc.perform(post("/api/v1/users")
                .header("Cookie", cookie)
                .header("X-CSRF", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"username":"bob","displayName":"Bob","password":"secret123","role":"MEMBER"}
                  """))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").header("Cookie", cookie).header("X-CSRF", csrf))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/session").header("Cookie", cookie))
            .andExpect(status().isUnauthorized());
    }
}
