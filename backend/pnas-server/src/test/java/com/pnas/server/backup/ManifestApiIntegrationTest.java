package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.files.UploadService;
import com.pnas.server.files.domain.Node;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ManifestApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired NodeRepository nodes;
    @Autowired UserRepository users;

    private record Ctx(String deviceId, String token) {}

    private record Auth(String cookie, String csrf) {}

    private Auth login(String user, String pass) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
            .andReturn();
        return new Auth(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }

    private Ctx createDevice() throws Exception {
        Auth auth = login("admin", "admin-secret");
        String cookie = auth.cookie();
        String csrf = auth.csrf();
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", cookie).header("X-CSRF", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"man-" + UUID.randomUUID() + "\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(JsonPath.read(body, "$.device.id"), JsonPath.read(body, "$.token"));
    }

    private String manifest(String token, String deviceId, String cursor, int limit) throws Exception {
        var req = get("/api/v1/backup/manifest").param("deviceId", deviceId).param("limit", String.valueOf(limit))
            .header("Authorization", "Bearer " + token);
        if (cursor != null) req = req.param("cursor", cursor);
        return mvc.perform(req).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void manifestIsSortedPagedAndCarriesChunkHashes() throws Exception {
        Ctx ctx = createDevice();
        // 首次调用创建备份根并返回 rootNodeId(空清单)
        String first = manifest(ctx.token(), ctx.deviceId(), null, 50);
        String rootId = JsonPath.read(first, "$.rootNodeId");
        assertThat(JsonPath.<List<?>>read(first, "$.items")).isEmpty();

        User admin = users.findByUsername("admin").orElseThrow();
        var me = new com.pnas.server.auth.SessionPrincipal(admin.getId(), admin.getUsername(),
            admin.getRole(), UUID.randomUUID());
        Node root = nodes.findById(UUID.fromString(rootId)).orElseThrow();
        UUID subId = files.createDir(me, root.getId(), "sub").id();
        Node sub = nodes.findById(subId).orElseThrow();

        byte[] a = "aaaa".getBytes(StandardCharsets.UTF_8);
        byte[] b = "bbbbbb".getBytes(StandardCharsets.UTF_8);
        Instant mtime = Instant.parse("2026-10-02T09:30:00Z");
        for (var item : List.of(new Object[]{root, "b.txt", b, mtime},
                                new Object[]{root, "a.txt", a, mtime},
                                new Object[]{sub, "c.txt", a, mtime})) {
            var dir = (Node) item[0];
            byte[] content = (byte[]) item[2];
            UUID up = uploads.start(admin, dir.getId(), (String) item[1], content.length, (Instant) item[3]);
            uploads.acceptChunk(admin.getId(), up, 0, new ByteArrayInputStream(content), Sha256.hex(content));
            uploads.complete(admin.getId(), up);
        }

        String page1 = manifest(ctx.token(), ctx.deviceId(), null, 2);
        List<String> paths1 = JsonPath.read(page1, "$.items[*].relPath");
        assertThat(paths1).containsExactly("a.txt", "b.txt");
        String cursor = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor).isEqualTo("b.txt");

        String page2 = manifest(ctx.token(), ctx.deviceId(), cursor, 2);
        List<String> paths2 = JsonPath.read(page2, "$.items[*].relPath");
        assertThat(paths2).containsExactly("sub/c.txt");
        assertThat(JsonPath.<String>read(page2, "$.nextCursor")).isNull();

        assertThat(JsonPath.<String>read(page1, "$.items[0].sourceMtime"))
            .startsWith("2026-10-02T09:30:00");
        assertThat(JsonPath.<String>read(page1, "$.items[0].firstChunkSha"))
            .isEqualTo(Sha256.hex(a));
        assertThat(JsonPath.<String>read(page1, "$.items[0].lastChunkSha")).isNotNull();
        assertThat(JsonPath.<Integer>read(page1, "$.items[0].versionNo")).isEqualTo(1);
        assertThat(JsonPath.<String>read(page1, "$.items[0].manifestSha256")).isNotBlank();
    }

    @Test
    void otherUsersDeviceIs404() throws Exception {
        Ctx ctx = createDevice();
        String member = "man-u-" + UUID.randomUUID().toString().substring(0, 8);
        Auth admin = login("admin", "admin-secret");
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .content("{\"username\":\"" + member + "\",\"displayName\":\"M\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        var mlogin = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + member + "\",\"password\":\"secret123\"}"))
            .andReturn();
        String cookie = mlogin.getResponse().getHeader("Set-Cookie").split(";")[0];
        mvc.perform(get("/api/v1/backup/manifest").param("deviceId", ctx.deviceId())
                .header("Cookie", cookie))
            .andExpect(status().isNotFound());
    }
}
