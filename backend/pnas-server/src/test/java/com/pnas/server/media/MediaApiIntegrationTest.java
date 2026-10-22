package com.pnas.server.media;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.UploadService;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.UserService;
import com.pnas.server.job.JobService;
import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 媒体 REST 契约:时间线、缩略图 ETag、单条 ACL、重建与内容 Range(spec §6)。 */
@AutoConfigureMockMvc
class MediaApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired JobService jobs;
    @Autowired UserRepository users;
    @Autowired UserService userService;

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

    /**
     * 同步跑完所有已到期任务:scan 会再入队 thumb,reindex 满批时会自链式入队下一批,
     * 因此必须循环到队列不再有到期任务(与 {@code MediaJobsTest} 的口径一致)。
     */
    private void drainJobs() {
        int rounds = 0;
        while (jobs.claimAndRunAllNow() > 0) {
            if (++rounds > 100) {
                throw new AssertionError("媒体任务队列在 100 轮内未收敛");
            }
        }
    }

    /** 上传一份内容到 admin home 并跑完媒体任务,返回节点名(用于在时间线中定位资产)。 */
    private String uploadAndIndex(String name, byte[] content) throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(admin);
        UUID uploadId = uploads.start(admin, home.getId(), name, content.length);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));
        uploads.complete(admin.getId(), uploadId);
        drainJobs();
        return name;
    }

    /** 管理员上传一张带 EXIF 的照片并跑完媒体任务,返回 assetId。 */
    private String uploadPhotoAndIndex(Instant taken) throws Exception {
        byte[] jpeg = TestImages.jpegWithExif(LocalDateTime.ofInstant(taken, ZoneOffset.UTC),
            39.9, 116.4, 1, 640, 480);
        String name = "api-" + UUID.randomUUID() + ".jpg";
        return assetIdByName(login("admin", "admin-secret"), uploadAndIndex(name, jpeg));
    }

    /** 按文件名在时间线里定位 assetId(时间线按 taken_at 排序,不能假定新上传就在首尾)。 */
    private String assetIdByName(Auth auth, String name) throws Exception {
        String timeline = mvc.perform(get("/api/v1/media/timeline")
                .header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(timeline, "$[?(@.name == '" + name + "')].assetId");
        assertThat(ids).as("时间线中应能按文件名找到刚上传的媒体").hasSize(1);
        return ids.get(0);
    }

    /**
     * 异步流式响应在 MockMvc 里由 {@code AsyncTaskExecutor} 线程完成 dispatch,与初次
     * dispatch 中 Spring Security 写响应头存在竞态({@code MockHttpServletResponse}
     * 非线程安全)。这是 MockMvc 的已知限制(M2 的 {@code NodesApiIntegrationTest}
     * 也会偶发),仅对会产生流式响应的请求做有限重试,避免测试基础设施竞态掩盖端点契约。
     */
    private MockHttpServletResponse performStreaming(RequestBuilder request, int expectedStatus,
                                                     byte[] expectedBody) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
                if (response.getStatus() == expectedStatus
                    && java.util.Arrays.equals(response.getContentAsByteArray(), expectedBody)) {
                    return response;
                }
                if (attempt >= 5) {
                    assertThat(response.getStatus()).isEqualTo(expectedStatus);
                    assertThat(response.getContentAsByteArray()).isEqualTo(expectedBody);
                    return response;
                }
            } catch (Exception e) {
                if (attempt >= 5 || !causedByConcurrentModification(e)) {
                    throw e;
                }
            }
        }
    }

    private static boolean causedByConcurrentModification(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConcurrentModificationException) {
                return true;
            }
        }
        return false;
    }

    @Test
    void timelineAndThumbAndEtagWork() throws Exception {
        String assetId = uploadPhotoAndIndex(Instant.parse("2026-06-01T10:00:00Z"));
        var auth = login("admin", "admin-secret");

        mvc.perform(get("/api/v1/media/timeline").header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].assetId").value(hasItem(assetId)));

        mvc.perform(get("/api/v1/media/assets/{id}", assetId).header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assetId").value(assetId))
            .andExpect(jsonPath("$.kind").value("IMAGE"))
            .andExpect(jsonPath("$.thumbStatus").value("READY"))
            .andExpect(jsonPath("$.sizeBytes").isNumber());

        var thumb = mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("Cache-Control", "private, max-age=604800"))
            .andReturn();
        String etag = thumb.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        assertThat(thumb.getResponse().getContentAsByteArray()).isNotEmpty();

        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", auth.cookie())
                .header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag));

        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "128").header("Cookie", auth.cookie()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidKindReturns400AndLowercaseIsAccepted() throws Exception {
        var admin = login("admin", "admin-secret");

        mvc.perform(get("/api/v1/media/timeline").param("kind", "bogus")
                .header("Cookie", admin.cookie()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));

        mvc.perform(get("/api/v1/media/timeline/buckets").param("kind", "bogus")
                .header("Cookie", admin.cookie()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));

        mvc.perform(get("/api/v1/media/timeline").param("kind", "image")
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk());
    }

    @Test
    void memberCannotSeeOrFetchOthersMedia() throws Exception {
        String assetId = uploadPhotoAndIndex(Instant.parse("2026-06-02T10:00:00Z"));
        String member = "media-bob-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createMember(member, "Bob", "secret123");
        var bob = login(member, "secret123");

        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", bob.cookie()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/assets/{id}", assetId).header("Cookie", bob.cookie()))
            .andExpect(status().isNotFound());
        String timeline = mvc.perform(get("/api/v1/media/timeline").header("Cookie", bob.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(timeline).doesNotContain(assetId);
    }

    @Test
    void reindexRequiresAdmin() throws Exception {
        var admin = login("admin", "admin-secret");
        mvc.perform(post("/api/v1/media/reindex")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty());

        String member = "media-carol-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createMember(member, "Carol", "secret123");
        var carol = login(member, "secret123");
        mvc.perform(post("/api/v1/media/reindex")
                .header("Cookie", carol.cookie()).header("X-CSRF", carol.csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void contentSupportsRangeForVideo() throws Exception {
        // 用一个 MP4 假体(仅需可下载与 Range 语义,不要求可播放)
        byte[] mp4 = new byte[4096];
        java.util.Arrays.fill(mp4, (byte) 7);
        String name = "clip-" + UUID.randomUUID() + ".mp4";
        var auth = login("admin", "admin-secret");
        String assetId = assetIdByName(auth, uploadAndIndex(name, mp4));

        mvc.perform(get("/api/v1/media/timeline").param("kind", "VIDEO")
                .header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].assetId").value(hasItem(assetId)));

        byte[] expected = new byte[16];
        java.util.Arrays.fill(expected, (byte) 7);
        var ranged = performStreaming(get("/api/v1/media/assets/{id}/content", assetId)
                .header("Cookie", auth.cookie()).header("Range", "bytes=0-15"), 206, expected);
        assertThat(ranged.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(ranged.getHeader("Content-Range")).startsWith("bytes 0-15/");
        assertThat(ranged.getContentAsByteArray()).isEqualTo(expected);

        var full = performStreaming(get("/api/v1/media/assets/{id}/content", assetId)
                .header("Cookie", auth.cookie()), 200, mp4);
        assertThat(full.getHeader("Content-Range")).isNull();
        assertThat(full.getContentAsByteArray()).isEqualTo(mp4);

        // 视频没有缩略图(fixture 不可解码/或非图片):一律 404
        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", auth.cookie()))
            .andExpect(status().isNotFound());
    }
}
