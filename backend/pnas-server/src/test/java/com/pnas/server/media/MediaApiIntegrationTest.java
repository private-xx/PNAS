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
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    private record IndexedPhoto(String assetId, byte[] content) {}

    /** 管理员上传一张带 EXIF 的照片并跑完媒体任务,返回 assetId 与上传的原始字节。 */
    private IndexedPhoto uploadPhotoAndIndex(Instant taken) throws Exception {
        byte[] jpeg = TestImages.jpegWithExif(LocalDateTime.ofInstant(taken, ZoneOffset.UTC),
            39.9, 116.4, 1, 640, 480);
        String name = "api-" + UUID.randomUUID() + ".jpg";
        return new IndexedPhoto(assetIdByName(login("admin", "admin-secret"), uploadAndIndex(name, jpeg)),
            jpeg);
    }

    /**
     * 上传一张无 EXIF 的 JPEG:taken_at 回退到版本写入时间(即"刚创建",落在当前月),
     * 用于给月份桶断言提供确定的当月资产,而不是依赖测试库里的历史数据。
     */
    private void uploadCurrentMonthAsset() throws Exception {
        uploadAndIndex("api-now-" + UUID.randomUUID() + ".jpg", TestImages.plainJpeg(32, 24));
    }

    /** 按文件名在时间线里定位 assetId(时间线按 taken_at 排序,不能假定新上传就在首尾)。 */
    private String assetIdByName(Auth auth, String name) throws Exception {
        String timeline = mvc.perform(get("/api/v1/media/timeline")
                .param("limit", "200")
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
     * 也会偶发),仅对 CME 或"空 body"这两个竞态表现做有限重试;状态码/字节内容
     * 不符时立即返回,由调用方断言失败,避免掩盖真正的截断。
     */
    private MockHttpServletResponse performStreaming(RequestBuilder request, byte[] expectedBody)
            throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
                awaitStreamingBody(response);
                boolean emptyAsyncRace = response.getContentAsByteArray().length == 0
                    && (expectedBody == null || expectedBody.length > 0);
                if (!emptyAsyncRace || attempt >= 5) {
                    return response;
                }
            } catch (Exception e) {
                if (attempt >= 5 || !causedByConcurrentModification(e)) {
                    throw e;
                }
            }
        }
    }

    /** 等待异步流把响应体写到声明的 Content-Length(MockMvc 的 perform 可能先于异步线程返回)。 */
    private static void awaitStreamingBody(MockHttpServletResponse response) throws InterruptedException {
        long declared = response.getContentLengthLong();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (declared > 0
            && response.getContentAsByteArray().length < declared
            && System.nanoTime() < deadline) {
            Thread.sleep(1);
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
        var photo = uploadPhotoAndIndex(Instant.parse("2026-06-01T10:00:00Z"));
        String assetId = photo.assetId();
        // 本月桶必须是"刚创建"的资产撑起来的:额外上传 4 张无 EXIF 的 JPEG,
        // taken_at 回退到版本写入时间(当前时刻),从而不依赖测试库里的历史数据。
        for (int i = 0; i < 4; i++) {
            uploadCurrentMonthAsset();
        }
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
        assertThat(etag).matches("\"[0-9a-f]{64}-256\"");
        assertThat(thumb.getResponse().getContentAsByteArray()).isNotEmpty();

        var thumb512 = mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "512").header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andReturn();
        String etag512 = thumb512.getResponse().getHeader("ETag");
        assertThat(etag512).matches("\"[0-9a-f]{64}-512\"");
        assertThat(etag512).isNotEqualTo(etag);

        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", auth.cookie())
                .header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag));

        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "128").header("Cookie", auth.cookie()))
            .andExpect(status().isBadRequest());

        // 图片内容:按版本 MIME / 文件名推断应为 image/jpeg,可 inline 展示
        var imageContent = performStreaming(get("/api/v1/media/assets/{id}/content", assetId)
                .header("Cookie", auth.cookie()), photo.content());
        assertThat(imageContent.getStatus()).isEqualTo(200);
        assertThat(imageContent.getHeader("Content-Type")).isEqualTo("image/jpeg");
        assertThat(imageContent.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(imageContent.getHeader("Content-Disposition")).isEqualTo("inline");
        assertThat(imageContent.getHeader("Content-Range")).isNull();
        // 内容必须是完整的上传字节,不能只满足于"有响应/非空"(流式竞态下可能拿到空 body)
        assertThat(imageContent.getHeader("Content-Length"))
            .isEqualTo(String.valueOf(photo.content().length));
        assertThat(imageContent.getContentLengthLong()).isEqualTo(photo.content().length);
        assertThat(imageContent.getContentAsByteArray()).isEqualTo(photo.content());
        assertThat(imageContent.getContentAsByteArray()[0]).isEqualTo((byte) 0xFF);
        assertThat(imageContent.getContentAsByteArray()[1]).isEqualTo((byte) 0xD8);

        // 相册日期视图与月份桶必须有真实内容:仅 isArray() 会让空数组通过
        // 月份桶按 PostgreSQL 会话时区分组(测试基类固定 Asia/Shanghai),这里用同一时区取"当前月"
        var bucketZone = ZoneId.of("Asia/Shanghai");
        int currentYear = Year.now(bucketZone).getValue();
        String currentPeriod = YearMonth.now(bucketZone).toString();
        String bucketsJson = mvc.perform(get("/api/v1/media/timeline/buckets")
                .param("year", String.valueOf(currentYear))
                .param("kind", "IMAGE")
                .header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Number> counts = JsonPath.read(bucketsJson, "$[*].count");
        long bucketSum = counts.stream().mapToLong(Number::longValue).sum();
        assertThat(bucketSum)
            .as("当前年月份桶应包含本用例刚创建的 4 张当月图片")
            .isGreaterThanOrEqualTo(4);
        List<String> periods = JsonPath.read(bucketsJson, "$[*].period");
        assertThat(periods).as("当前月桶").contains(currentPeriod);

        String albumsJson = mvc.perform(get("/api/v1/media/albums/dates")
                .header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> albums = JsonPath.read(albumsJson, "$");
        assertThat(albums).as("相册日期视图").isNotEmpty();
        assertThat(albums).extracting(a -> ((Number) a.get("year")).intValue())
            .contains(currentYear);
        List<String> albumPeriods = JsonPath.read(albumsJson, "$[*].months[*].period");
        assertThat(albumPeriods).as("当前年份应至少有一个月桶").contains(currentPeriod);
    }

    @Test
    void heicNamedUploadHasSkippedThumbsAndThumbEndpointIs404() throws Exception {
        var auth = login("admin", "admin-secret");
        // 内容是合法 JPEG,但扩展名是 .heic:入库认得是图片并置 SKIPPED(不尝试解码),
        // 因此缩略图端点必须以 thumb_unsupported 契约返回 404,而不是 500/空响应。
        byte[] jpeg = TestImages.jpegWithExif(LocalDateTime.of(2026, 6, 3, 10, 0),
            1.0, 2.0, 1, 64, 48);
        String assetId = assetIdByName(auth,
            uploadAndIndex("api-skip-" + UUID.randomUUID() + ".heic", jpeg));

        mvc.perform(get("/api/v1/media/assets/{id}", assetId).header("Cookie", auth.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("IMAGE"))
            .andExpect(jsonPath("$.thumbStatus").value("SKIPPED"));

        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", auth.cookie()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("thumb_unsupported"));
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
        String assetId = uploadPhotoAndIndex(Instant.parse("2026-06-02T10:00:00Z")).assetId();
        String member = "media-bob-" + UUID.randomUUID().toString().substring(0, 8);
        userService.createMember(member, "Bob", "secret123");
        var bob = login(member, "secret123");

        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", bob.cookie()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/assets/{id}", assetId).header("Cookie", bob.cookie()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/assets/{id}/content", assetId).header("Cookie", bob.cookie()))
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
                .header("Cookie", auth.cookie()).header("Range", "bytes=0-15"), expected);
        assertThat(ranged.getStatus()).isEqualTo(206);
        assertThat(ranged.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(ranged.getHeader("Content-Range")).startsWith("bytes 0-15/");
        assertThat(ranged.getHeader("Content-Type")).startsWith("video/");
        assertThat(ranged.getHeader("Content-Disposition")).isEqualTo("inline");
        assertThat(ranged.getContentAsByteArray()).isEqualTo(expected);

        var full = performStreaming(get("/api/v1/media/assets/{id}/content", assetId)
                .header("Cookie", auth.cookie()), mp4);
        assertThat(full.getStatus()).isEqualTo(200);
        assertThat(full.getHeader("Content-Range")).isNull();
        assertThat(full.getHeader("Content-Type")).startsWith("video/");
        assertThat(full.getContentAsByteArray()).isEqualTo(mp4);

        // Range 解析错误:越界 416,非 bytes 单位 400(同步异常,不进入流式路径)
        mvc.perform(get("/api/v1/media/assets/{id}/content", assetId)
                .header("Cookie", auth.cookie()).header("Range", "bytes=999999-"))
            .andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(get("/api/v1/media/assets/{id}/content", assetId)
                .header("Cookie", auth.cookie()).header("Range", "items=0-1"))
            .andExpect(status().isBadRequest());

        // 视频没有缩略图(fixture 不可解码/或非图片):一律 404
        mvc.perform(get("/api/v1/media/assets/{id}/thumb", assetId)
                .param("size", "256").header("Cookie", auth.cookie()))
            .andExpect(status().isNotFound());
    }
}
