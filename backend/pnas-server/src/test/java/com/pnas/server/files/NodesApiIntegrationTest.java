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
