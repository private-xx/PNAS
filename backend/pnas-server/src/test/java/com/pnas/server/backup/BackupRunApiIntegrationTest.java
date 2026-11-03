package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BackupRunApiIntegrationTest extends AbstractIntegrationTest {

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

    private String deviceId(Auth auth, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", auth.cookie()).header("X-CSRF", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.device.id");
    }

    @Test
    void runLifecycleFromRunningToSuccess() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run-" + System.nanoTime());

        String started = mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"mode\":\"MANUAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(started, "$.id");

        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"SUCCESS","filesSeen":10,"filesUploaded":3,"filesSkipped":7,
                     "filesFailed":0,"bytesUploaded":1234}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.filesUploaded").value(3));

        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId)
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(runId))
            .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    void finishingTwiceOrByAnotherUserFails() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run2-" + System.nanoTime());
        String body = mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"mode\":\"MANUAL\"}"))
            .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.id");

        String finish = "{\"status\":\"SUCCESS\",\"filesSeen\":1,\"filesUploaded\":1,"
            + "\"filesSkipped\":0,\"filesFailed\":0,\"bytesUploaded\":1}";
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(finish))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(finish))
            .andExpect(status().isBadRequest());   // 已结束

        // 成员拿管理员 run:404(不泄露)
        String member = "runmember-" + System.nanoTime() % 100000;
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + member + "\",\"displayName\":\"M\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        var m = login(member, "secret123");
        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId)
                .header("Cookie", m.cookie()))
            .andExpect(status().isNotFound());
    }
}
