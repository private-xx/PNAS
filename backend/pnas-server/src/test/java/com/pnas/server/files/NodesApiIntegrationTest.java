package com.pnas.server.files;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.iam.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** files REST API:两用户隔离、回收站往返、Range 下载(FR-FS-01/02/03、NFR-SEC-03)。 */
@AutoConfigureMockMvc
class NodesApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UploadService uploads;
    @Autowired FilesService files;

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

    /** 经 API 建目录,返回响应体 JSON(含 id 与 parentId)。 */
    private String createDirJson(Auth auth, UUID parentId, String name) throws Exception {
        String body = "{\"parentId\":" + (parentId == null ? "null" : "\"" + parentId + "\"")
            + ",\"name\":\"" + name + "\"}";
        var res = mvc.perform(post("/api/v1/nodes")
                .header("Cookie", auth.cookie()).header("X-CSRF", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
        return res.getResponse().getContentAsString();
    }

    /** 经管理员创建成员并登录,返回其会话。 */
    private Auth newMember(Auth admin) throws Exception {
        String name = "iso-bob-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"displayName\":\"Bob\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        return login(name, "secret123");
    }

    @Test
    void memberCannotMutateForeignNodes() throws Exception {
        var admin = login("admin", "admin-secret");
        String created = createDirJson(admin, null, "隔离-写-" + UUID.randomUUID());
        String adminHomeId = JsonPath.read(created, "$.parentId");
        String nodeId = JsonPath.read(created, "$.id");
        var bob = newMember(admin);

        // 在他人目录下建目录 → 403
        mvc.perform(post("/api/v1/nodes")
                .header("Cookie", bob.cookie()).header("X-CSRF", bob.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"" + adminHomeId + "\",\"name\":\"越权目录\"}"))
            .andExpect(status().isForbidden());

        // 改名 / 删除他人节点 → 403
        mvc.perform(patch("/api/v1/nodes/{id}", nodeId)
                .header("Cookie", bob.cookie()).header("X-CSRF", bob.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"越权改名\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/nodes/{id}", nodeId)
                .header("Cookie", bob.cookie()).header("X-CSRF", bob.csrf()))
            .andExpect(status().isForbidden());

        // 管理员删除后,成员也不能恢复 → 403
        mvc.perform(delete("/api/v1/nodes/{id}", nodeId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/nodes/{id}/restore", nodeId)
                .header("Cookie", bob.cookie()).header("X-CSRF", bob.csrf()))
            .andExpect(status().isForbidden());

        // 他人文件:版本查询也应 403(目录本身无版本,属 400,故这里用文件验证权限)
        var adminUser = users.findByUsername("admin").orElseThrow();
        UUID uploadId = uploads.start(adminUser, UUID.fromString(adminHomeId),
            "负向-" + UUID.randomUUID() + ".txt", 0);
        var file = uploads.complete(adminUser.getId(), uploadId);
        mvc.perform(get("/api/v1/nodes/{id}/versions", file.nodeId()).header("Cookie", bob.cookie()))
            .andExpect(status().isForbidden());
    }

    @Test
    void listedHomeIsIsolatedBetweenUsers() throws Exception {
        var admin = login("admin", "admin-secret");
        createDirJson(admin, null, "隔离-文件-" + UUID.randomUUID());

        String bobName = "iso-bob-" + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + bobName + "\",\"displayName\":\"Bob\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        var bob = login(bobName, "secret123");

        // bob 的根目录里看不到 admin 的目录
        String bobList = mvc.perform(get("/api/v1/nodes").header("Cookie", bob.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(bobList).doesNotContain("隔离-文件-");
    }

    @Test
    void trashThenRestoreRoundTrip() throws Exception {
        var admin = login("admin", "admin-secret");
        String created = createDirJson(admin, null, "回收站-目录-" + UUID.randomUUID());
        String dirId = JsonPath.read(created, "$.id");

        mvc.perform(delete("/api/v1/nodes/{id}", dirId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());

        String trash = mvc.perform(get("/api/v1/nodes").param("trash", "true")
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(trash).contains(dirId);

        mvc.perform(post("/api/v1/nodes/{id}/restore", dirId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isOk());

        String trashAfter = mvc.perform(get("/api/v1/nodes").param("trash", "true")
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(trashAfter).doesNotContain(dirId);
    }

    @Test
    void rangeDownloadReturnsPartialContent() throws Exception {
        var admin = login("admin", "admin-secret");
        var adminUser = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(adminUser);

        byte[] content = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        UUID uploadId = uploads.start(adminUser, home.getId(),
            "range-" + UUID.randomUUID() + ".txt", content.length);
        uploads.acceptChunk(adminUser.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));
        var done = uploads.complete(adminUser.getId(), uploadId);

        var res = mvc.perform(get("/api/v1/nodes/{id}/content", done.nodeId())
                .header("Cookie", admin.cookie())
                .header("Range", "bytes=4-7"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 4-7/16"))
            .andReturn();
        assertThat(res.getResponse().getContentAsByteArray())
            .isEqualTo("4567".getBytes(StandardCharsets.UTF_8));
    }
}
