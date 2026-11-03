package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.auth.SessionPrincipal;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    @Autowired JdbcTemplate jdbc;

    private static final Instant MTIME = Instant.parse("2026-10-02T09:30:00Z");

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
        return createDevice("admin", "admin-secret");
    }

    private Ctx createDevice(String user, String pass) throws Exception {
        Auth auth = login(user, pass);
        String cookie = auth.cookie();
        String csrf = auth.csrf();
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", cookie).header("X-CSRF", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"man-" + UUID.randomUUID() + "\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(JsonPath.read(body, "$.device.id"), JsonPath.read(body, "$.token"));
    }

    private User admin() {
        return users.findByUsername("admin").orElseThrow();
    }

    private SessionPrincipal principal(User user) {
        return new SessionPrincipal(user.getId(), user.getUsername(), user.getRole(), UUID.randomUUID());
    }

    /** 单块上传(内容 < 4 MiB)。 */
    private UploadService.Completed upload(User owner, UUID parentId, String name, byte[] content) {
        UUID uploadId = uploads.start(owner, parentId, name, content.length, MTIME);
        if (content.length > 0) {
            uploads.acceptChunk(owner.getId(), uploadId, 0,
                new ByteArrayInputStream(content), Sha256.hex(content));
        }
        return uploads.complete(owner.getId(), uploadId);
    }

    private String manifest(String token, String deviceId, String cursor, int limit) throws Exception {
        var req = get("/api/v1/backup/manifest").param("deviceId", deviceId).param("limit", String.valueOf(limit))
            .header("Authorization", "Bearer " + token);
        if (cursor != null) req = req.param("cursor", cursor);
        return mvc.perform(req).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    /** 首次调用创建 `home/自动备份/<deviceId>` 并返回设备备份根 id。 */
    private UUID rootOf(Ctx ctx) throws Exception {
        String first = manifest(ctx.token(), ctx.deviceId(), null, 50);
        return UUID.fromString(JsonPath.read(first, "$.rootNodeId"));
    }

    @Test
    void manifestIsSortedPagedAndCarriesChunkHashes() throws Exception {
        Ctx ctx = createDevice();
        // 首次调用创建备份根并返回 rootNodeId(空清单)
        String first = manifest(ctx.token(), ctx.deviceId(), null, 50);
        String rootId = JsonPath.read(first, "$.rootNodeId");
        assertThat(JsonPath.<List<?>>read(first, "$.items")).isEmpty();

        User admin = admin();
        var me = principal(admin);
        Node root = nodes.findById(UUID.fromString(rootId)).orElseThrow();
        UUID subId = files.createDir(me, root.getId(), "sub").id();
        Node sub = nodes.findById(subId).orElseThrow();

        byte[] a = "aaaa".getBytes(StandardCharsets.UTF_8);
        byte[] b = "bbbbbb".getBytes(StandardCharsets.UTF_8);
        for (var item : List.of(new Object[]{root, "b.txt", b},
                                new Object[]{root, "a.txt", a},
                                new Object[]{sub, "c.txt", a})) {
            var dir = (Node) item[0];
            upload(admin, dir.getId(), (String) item[1], (byte[]) item[2]);
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

    /** 排序键必须是 relPath,不能是 DFS 遍历顺序:`a.txt` 先于 `a/z.txt`。 */
    @Test
    void itemsAreSortedByRelPathNotByTraversalOrder() throws Exception {
        Ctx ctx = createDevice();
        UUID rootId = rootOf(ctx);
        User admin = admin();
        UUID dirA = files.createDir(principal(admin), rootId, "a").id();
        upload(admin, dirA, "z.txt", "zz".getBytes(StandardCharsets.UTF_8));
        upload(admin, rootId, "a.txt", "aa".getBytes(StandardCharsets.UTF_8));

        String body = manifest(ctx.token(), ctx.deviceId(), null, 50);
        // DFS 顺序是 a/z.txt → a.txt;排序后必须是 a.txt → a/z.txt。
        List<String> paths = JsonPath.read(body, "$.items[*].relPath");
        assertThat(paths).containsExactly("a.txt", "a/z.txt");
    }

    /** 回收站中的文件与目录(含其子文件)都不应出现在清单里。 */
    @Test
    void trashedFilesAndDirectoriesAreExcluded() throws Exception {
        Ctx ctx = createDevice();
        UUID rootId = rootOf(ctx);
        User admin = admin();
        var me = principal(admin);
        var gone = upload(admin, rootId, "gone.txt", "g".getBytes(StandardCharsets.UTF_8));
        upload(admin, rootId, "keep.txt", "k".getBytes(StandardCharsets.UTF_8));
        UUID dir = files.createDir(me, rootId, "dir").id();
        upload(admin, dir, "child.txt", "c".getBytes(StandardCharsets.UTF_8));

        files.trash(me, gone.nodeId());
        files.trash(me, dir);

        List<String> paths = JsonPath.read(manifest(ctx.token(), ctx.deviceId(), null, 50),
            "$.items[*].relPath");
        assertThat(paths).containsExactly("keep.txt");
    }

    /** 零字节文件:0 块版本,首/末块哈希均为 null。 */
    @Test
    void zeroByteFileHasSizeZeroAndNoChunkHashes() throws Exception {
        Ctx ctx = createDevice();
        UUID rootId = rootOf(ctx);
        User admin = admin();
        UUID uploadId = uploads.start(admin, rootId, "empty-" + UUID.randomUUID(), 0, MTIME);
        uploads.complete(admin.getId(), uploadId);

        String body = manifest(ctx.token(), ctx.deviceId(), null, 50);
        assertThat(JsonPath.<List<?>>read(body, "$.items")).hasSize(1);
        assertThat(JsonPath.<Integer>read(body, "$.items[0].size")).isZero();
        assertThat(JsonPath.<String>read(body, "$.items[0].firstChunkSha")).isNull();
        assertThat(JsonPath.<String>read(body, "$.items[0].lastChunkSha")).isNull();
    }

    /** 备份根路径:`home/自动备份/<deviceId>`,且重复调用返回同一 rootNodeId。 */
    @Test
    void rootDirectoryIsDeviceDirUnderAutoBackupAndIsIdempotent() throws Exception {
        Ctx ctx = createDevice();
        String first = manifest(ctx.token(), ctx.deviceId(), null, 50);
        UUID rootId = UUID.fromString(JsonPath.<String>read(first, "$.rootNodeId"));

        Map<String, Object> root = jdbc.queryForMap(
            "select kind, name, parent_id from nodes where id = ?", rootId);
        assertThat(root.get("kind")).isEqualTo("DIR");
        assertThat(root.get("name")).isEqualTo(ctx.deviceId());
        UUID backupId = (UUID) root.get("parent_id");
        Map<String, Object> backup = jdbc.queryForMap(
            "select kind, name, parent_id from nodes where id = ?", backupId);
        assertThat(backup.get("kind")).isEqualTo("DIR");
        assertThat(backup.get("name")).isEqualTo("自动备份");
        User admin = admin();
        assertThat(backup.get("parent_id")).isEqualTo(files.homeOf(principal(admin)).getId());

        String second = manifest(ctx.token(), ctx.deviceId(), null, 50);
        assertThat(JsonPath.<String>read(second, "$.rootNodeId")).isEqualTo(rootId.toString());
    }

    /** 备份目录路径被同名 FILE 占用 → 409(不能把文件当目录根)。 */
    @Test
    void fileOccupyingAutoBackupPathIsConflict() throws Exception {
        String username = "man-f-" + UUID.randomUUID().toString().substring(0, 8);
        Auth admin = login("admin", "admin-secret");
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .content("{\"username\":\"" + username + "\",\"displayName\":\"F\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        Ctx ctx = createDevice(username, "secret123");
        User member = users.findByUsername(username).orElseThrow();
        UUID homeId = files.ensureUserHome(member).getId();
        upload(member, homeId, "自动备份", "x".getBytes(StandardCharsets.UTF_8));

        String body = mvc.perform(get("/api/v1/backup/manifest")
                .param("deviceId", ctx.deviceId())
                .header("Authorization", "Bearer " + ctx.token()))
            .andExpect(status().isConflict())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(body, "$.message"))
            .contains("备份目录路径被同名文件占用")
            .contains("自动备份");
    }

    /** 首/末块哈希必须来自 seq 最小/最大的块,而不是同一块。 */
    @Test
    void firstAndLastChunkShasFollowChunkOrder() throws Exception {
        Ctx ctx = createDevice();
        UUID rootId = rootOf(ctx);
        User admin = admin();
        int chunk = 4 * 1024 * 1024;
        byte[] c0 = new byte[chunk];
        byte[] c1 = new byte[chunk];
        Arrays.fill(c0, (byte) 'A');
        Arrays.fill(c1, (byte) 'B');
        String sha0 = Sha256.hex(c0);
        String sha1 = Sha256.hex(c1);
        assertThat(sha0).isNotEqualTo(sha1);

        UUID uploadId = uploads.start(admin, rootId, "chunked.bin", (long) chunk * 2, MTIME);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(c0), sha0);
        uploads.acceptChunk(admin.getId(), uploadId, 1, new ByteArrayInputStream(c1), sha1);
        uploads.complete(admin.getId(), uploadId);

        String body = manifest(ctx.token(), ctx.deviceId(), null, 50);
        assertThat(JsonPath.<String>read(body, "$.items[0].relPath")).isEqualTo("chunked.bin");
        assertThat(JsonPath.<String>read(body, "$.items[0].firstChunkSha")).isEqualTo(sha0);
        assertThat(JsonPath.<String>read(body, "$.items[0].lastChunkSha")).isEqualTo(sha1);
    }

    /** manifestSha256 = SHA-256(各块 hex 按 seq 拼接后的 UTF-8 字节),与 UploadService 一致。 */
    @Test
    void manifestSha256MirrorsConcatenatedChunkHashes() throws Exception {
        Ctx ctx = createDevice();
        UUID rootId = rootOf(ctx);
        User admin = admin();
        int chunk = 4 * 1024 * 1024;
        byte[] c0 = new byte[chunk];
        byte[] c1 = new byte[chunk];
        Arrays.fill(c0, (byte) 'A');
        Arrays.fill(c1, (byte) 'B');
        String sha0 = Sha256.hex(c0);
        String sha1 = Sha256.hex(c1);

        UUID uploadId = uploads.start(admin, rootId, "manifest.bin", (long) chunk * 2, MTIME);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(c0), sha0);
        uploads.acceptChunk(admin.getId(), uploadId, 1, new ByteArrayInputStream(c1), sha1);
        uploads.complete(admin.getId(), uploadId);

        String body = manifest(ctx.token(), ctx.deviceId(), null, 50);
        assertThat(JsonPath.<String>read(body, "$.items[0].manifestSha256"))
            .isEqualTo(Sha256.hex((sha0 + sha1).getBytes(StandardCharsets.UTF_8)));
    }

    /** limit 夹取:0 → 1 条;500 → 200 上限(少量数据时只断言数组正常返回)。 */
    @Test
    void limitIsClampedToOneAndTwoHundred() throws Exception {
        Ctx ctx = createDevice();
        UUID rootId = rootOf(ctx);
        User admin = admin();
        upload(admin, rootId, "p.txt", "p".getBytes(StandardCharsets.UTF_8));
        upload(admin, rootId, "q.txt", "q".getBytes(StandardCharsets.UTF_8));

        String zero = manifest(ctx.token(), ctx.deviceId(), null, 0);
        assertThat(JsonPath.<List<?>>read(zero, "$.items")).hasSize(1);

        String large = manifest(ctx.token(), ctx.deviceId(), null, 500);
        List<String> largePaths = JsonPath.read(large, "$.items[*].relPath");
        assertThat(largePaths).containsExactly("p.txt", "q.txt");
    }
}
