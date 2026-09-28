package com.pnas.server.files;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 分块上传 REST 端点:上传→完成→下载一致;错误哈希拒绝;越权会话拒绝(FR-FS-02)。 */
@AutoConfigureMockMvc
class UploadApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private record Auth(String cookie, String csrf) {}

    private Auth login(String username, String password) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return new Auth(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }

    /** 建一个探针目录,返回 [id, parentId(=调用者家目录)]。 */
    private String[] newProbeDir(Auth auth) throws Exception {
        var res = mvc.perform(post("/api/v1/nodes")
                .header("Cookie", auth.cookie()).header("X-CSRF", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":null,\"name\":\"上传-探针-" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String body = res.getResponse().getContentAsString();
        return new String[] {JsonPath.read(body, "$.id"), JsonPath.read(body, "$.parentId")};
    }

    @Test
    void uploadThenDownloadMatchesContent() throws Exception {
        var admin = login("admin", "admin-secret");
        String homeId = newProbeDir(admin)[1];

        byte[] content = "PNAS-分块上传内容-✔".getBytes(StandardCharsets.UTF_8);
        String fileName = "api-" + UUID.randomUUID() + ".txt";

        String startBody = mvc.perform(post("/api/v1/uploads")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destParentId\":\"" + homeId + "\",\"filename\":\"" + fileName
                    + "\",\"size\":" + content.length + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(startBody, "$.uploadId");

        mvc.perform(put("/api/v1/uploads/{id}/chunks/{seq}", uploadId, 0)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .header("X-Sha256", Sha256.hex(content))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(content))
            .andExpect(status().isNoContent());

        String completeBody = mvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String nodeId = JsonPath.read(completeBody, "$.nodeId");
        assertThat((Integer) JsonPath.read(completeBody, "$.versionNo")).isEqualTo(1);

        var download = mvc.perform(get("/api/v1/nodes/{id}/content", nodeId)
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void wrongChunkHashIsRejected() throws Exception {
        var admin = login("admin", "admin-secret");
        String homeId = newProbeDir(admin)[1];
        byte[] content = "bad-hash".getBytes(StandardCharsets.UTF_8);

        String startBody = mvc.perform(post("/api/v1/uploads")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destParentId\":\"" + homeId + "\",\"filename\":\"bad-"
                    + UUID.randomUUID() + ".txt\",\"size\":" + content.length + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(startBody, "$.uploadId");

        mvc.perform(put("/api/v1/uploads/{id}/chunks/{seq}", uploadId, 0)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .header("X-Sha256", Sha256.hex("different".getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(content))
            .andExpect(status().isBadRequest());
    }

    @Test
    void foreignUserCannotUseUploadSession() throws Exception {
        var admin = login("admin", "admin-secret");
        String homeId = newProbeDir(admin)[1];
        byte[] content = "session-owner".getBytes(StandardCharsets.UTF_8);

        String startBody = mvc.perform(post("/api/v1/uploads")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destParentId\":\"" + homeId + "\",\"filename\":\"own-"
                    + UUID.randomUUID() + ".txt\",\"size\":" + content.length + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(startBody, "$.uploadId");

        String bobName = "up-bob-" + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + bobName + "\",\"displayName\":\"Bob\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        var bob = login(bobName, "secret123");

        mvc.perform(put("/api/v1/uploads/{id}/chunks/{seq}", uploadId, 0)
                .header("Cookie", bob.cookie()).header("X-CSRF", bob.csrf())
                .header("X-Sha256", Sha256.hex(content))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(content))
            .andExpect(status().isForbidden());
    }
}
