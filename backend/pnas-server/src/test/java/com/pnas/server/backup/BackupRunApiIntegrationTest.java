package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 备份运行记录 API(FR-BACKUP-03/04)契约:开始/结束字段、跨用户隔离、按 startedAt 倒序的列表、
 * limit 夹取、以及各条负向守卫(状态机、空字段、未知 id)。
 */
@AutoConfigureMockMvc
class BackupRunApiIntegrationTest extends AbstractIntegrationTest {

    private static final String MEMBER_PASSWORD = "secret123";

    private static final String FINISH_BODY = """
        {"status":"SUCCESS","filesSeen":10,"filesUploaded":3,"filesSkipped":7,
         "filesFailed":0,"bytesUploaded":1234}
        """;

    @Autowired MockMvc mvc;

    private record Auth(String cookie, String csrf) {}

    private record Member(String username, Auth auth) {}

    @Test
    void runLifecycleFromRunningToSuccessExposesFields() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run-" + System.nanoTime());

        String started = mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"mode\":\"MANUAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.startedAt").isNotEmpty())
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.mode").value("MANUAL"))
            .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(started, "$.id");

        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(FINISH_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.startedAt").isNotEmpty())
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.mode").value("MANUAL"))
            .andExpect(jsonPath("$.filesUploaded").value(3));

        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId)
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(runId))
            .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    void memberCannotFinishAnotherUsersRun() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run-x-" + System.nanoTime());
        String runId = startRun(admin, deviceId, "MANUAL");
        Member member = newMember(admin);

        // 跨用户结束:有效 SUCCESS 体也必须 404(不泄露他人 run 是否存在)。
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", member.auth().cookie()).header("X-CSRF", member.auth().csrf())
                .contentType(MediaType.APPLICATION_JSON).content(FINISH_BODY))
            .andExpect(status().isNotFound());

        // 记录未被成员的 PATCH 改动:管理员仍能正常结束。
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(FINISH_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void memberCannotStartRunOnAnotherUsersDevice() throws Exception {
        var admin = login("admin", "admin-secret");
        String adminDevice = deviceId(admin, "run-x-" + System.nanoTime());
        Member member = newMember(admin);

        // 跨用户开始:用管理员的 deviceId 必须 404。
        mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", member.auth().cookie()).header("X-CSRF", member.auth().csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + adminDevice + "\",\"mode\":\"MANUAL\"}"))
            .andExpect(status().isNotFound());

        // 成员自己的设备仍可开始(证明 404 来自设备归属,而非端点不可用)。
        String ownDevice = deviceId(member.auth(), "run-own-" + System.nanoTime());
        mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", member.auth().cookie()).header("X-CSRF", member.auth().csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + ownDevice + "\",\"mode\":\"MANUAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deviceId").value(ownDevice));
    }

    @Test
    void listIsNewestFirstScopedByUserAndClampsLimit() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run-list-" + System.nanoTime());

        String first = startRun(admin, deviceId, "MANUAL");
        finishRun(admin, first);
        // 两条记录的 startedAt 需严格递增,排序断言才有确定含义。
        Thread.sleep(20);
        String second = startRun(admin, deviceId, "SCHEDULED");
        finishRun(admin, second);

        String listed = mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId)
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].id").value(second))
            .andExpect(jsonPath("$[0].mode").value("SCHEDULED"))
            .andExpect(jsonPath("$[1].id").value(first))
            .andExpect(jsonPath("$[1].status").value("SUCCESS"))
            .andReturn().getResponse().getContentAsString();
        assertThat(((List<String>) JsonPath.read(listed, "$[*].id")))
            .containsExactly(second, first);

        // 成员不带 deviceId 的列表只能看到自己的 run:管理员的两条都不能出现。
        Member member = newMember(admin);
        String memberBody = mvc.perform(get("/api/v1/backup/runs")
                .header("Cookie", member.auth().cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)))
            .andReturn().getResponse().getContentAsString();
        assertThat(memberBody).doesNotContain(first).doesNotContain(second);

        // 成员用管理员的 deviceId 过滤:设备不存在 → 404。
        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId)
                .header("Cookie", member.auth().cookie()))
            .andExpect(status().isNotFound());

        // limit=0 被夹到 1(不能变成 0 行或 500)。
        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId).param("limit", "0")
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));

        // limit=201 被夹到上限 200,仍正常返回数组。
        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId).param("limit", "201")
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void negativeGuardsRejectInvalidTransitionsAndInput() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run-neg-" + System.nanoTime());
        String runId = startRun(admin, deviceId, "MANUAL");

        // 已结束是终态:重复提交 → 400。
        finishRun(admin, runId);
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(FINISH_BODY))
            .andExpect(status().isBadRequest());

        // 状态守卫用一条新的 RUNNING 记录(上面的已进入终态)。
        String running = startRun(admin, deviceId, "MANUAL");
        mvc.perform(patch("/api/v1/backup/runs/{id}", running)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RUNNING\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/backup/runs/{id}", running)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":null,\"filesSeen\":0,\"filesUploaded\":0,"
                    + "\"filesSkipped\":0,\"filesFailed\":0,\"bytesUploaded\":0}"))
            .andExpect(status().isBadRequest());

        // 开始守卫:deviceId / mode 为空 → 400。
        mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":null,\"mode\":\"MANUAL\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"mode\":null}"))
            .andExpect(status().isBadRequest());

        // 未知 run id → 404。
        mvc.perform(patch("/api/v1/backup/runs/{id}", UUID.randomUUID())
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(FINISH_BODY))
            .andExpect(status().isNotFound());
    }

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

    private String startRun(Auth auth, String deviceId, String mode) throws Exception {
        String body = mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", auth.cookie()).header("X-CSRF", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"mode\":\"" + mode + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.deviceId").value(deviceId))
            .andExpect(jsonPath("$.mode").value(mode))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void finishRun(Auth auth, String runId) throws Exception {
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", auth.cookie()).header("X-CSRF", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(FINISH_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    private Member newMember(Auth admin) throws Exception {
        String username = "runmember-" + UUID.randomUUID().toString().substring(0, 8);
        String body = mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username
                    + "\",\"displayName\":\"M\",\"password\":\"" + MEMBER_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.role")).isEqualTo("MEMBER");
        return new Member(username, login(username, MEMBER_PASSWORD));
    }
}
