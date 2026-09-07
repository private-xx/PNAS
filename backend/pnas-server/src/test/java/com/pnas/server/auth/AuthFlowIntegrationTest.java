package com.pnas.server.auth;

import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
            .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
            .andExpect(header().string("Set-Cookie", not(containsString("Secure"))))
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

    @Test
    void successiveLoginTokensDifferAndAre64Hex() throws Exception {
        String body = """
            {"username":"admin","password":"admin-secret"}
            """;
        String t1 = loginToken(body);
        String t2 = loginToken(body);
        assertThat(t1).matches("[0-9a-f]{64}");
        assertThat(t2).matches("[0-9a-f]{64}");
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void nonSafeRequestWithoutCsrfHeaderIsForbidden() throws Exception {
        String body = """
            {"username":"admin","password":"admin-secret"}
            """;
        var login = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
        String cookie = login.getResponse().getHeader("Set-Cookie").split(";")[0];

        // 携带有效会话 Cookie 但缺少 X-CSRF 头 → CsrfGuardFilter 拒绝(403)
        mvc.perform(post("/api/v1/users")
                .header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"username":"bob-csrf","displayName":"Bob","password":"secret123","role":"MEMBER"}
                  """))
            .andExpect(status().isForbidden());
    }

    private String loginToken(String body) throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
        return login.getResponse().getHeader("Set-Cookie").split(";")[0]
            .substring("PNAS_SESSION=".length());
    }
}
