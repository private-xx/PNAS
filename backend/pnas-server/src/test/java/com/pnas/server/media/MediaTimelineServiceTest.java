package com.pnas.server.media;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.FileVersionRepository;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.UploadService;
import com.pnas.server.iam.AclService;
import com.pnas.server.iam.GroupService;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.UserService;
import com.pnas.server.iam.domain.User;
import com.pnas.server.media.MediaTimelineService.PeriodCount;
import com.pnas.server.media.MediaTimelineService.TimelineItem;
import com.pnas.server.media.MediaTimelineService.YearMonths;
import com.pnas.server.media.domain.MediaKind;
import com.pnas.server.media.testfixtures.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时间线可见性/游标/桶的集成测试。
 *
 * <p>除 ADMIN 作用域用例外,每个用例都用**新建成员**做数据隔离:测试库在整个 JVM 内共享,
 * admin 的 home 里会有其它测试类留下的媒体,全局断言(如"总共几条")会被污染。成员名随机,
 * 数据只属于它自己;ADMIN 用例只断言"包含/不包含"指定 assetId,并使用 2037 年时间戳,
 * 保证在 ADMIN 全量时间线的前 200 条内。</p>
 */
class MediaTimelineServiceTest extends AbstractIntegrationTest {

    @Autowired MediaTimelineService timeline;
    @Autowired MediaIngestService ingest;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired AclService acls;
    @Autowired GroupService groupService;

    private SessionPrincipal principal(User u) {
        return new SessionPrincipal(u.getId(), u.getUsername(), u.getRole(), UUID.randomUUID());
    }

    private User newMember() {
        return userService.createMember("tl-" + UUID.randomUUID().toString().substring(0, 8),
            "TL", "secret123");
    }

    private record Photo(UUID nodeId, UUID assetId) {}

    /** 走真实上传 + 入库流程造出一张带 EXIF 的照片(默认传到 owner 的 home)。 */
    private Photo uploadPhoto(User owner, String localDateTime) throws Exception {
        return uploadPhoto(owner, files.ensureUserHome(owner).getId(), localDateTime);
    }

    /** 同上,但上传到指定目录(用于目录子树剪枝用例)。 */
    private Photo uploadPhoto(User owner, UUID parentId, String localDateTime) throws Exception {
        byte[] jpeg = TestImages.jpegWithExif(LocalDateTime.parse(localDateTime), 1.0, 2.0, 1, 200, 150);
        String name = "tl-" + UUID.randomUUID() + ".jpg";
        UUID uploadId = uploads.start(owner, parentId, name, jpeg.length);
        uploads.acceptChunk(owner.getId(), uploadId, 0, new ByteArrayInputStream(jpeg), Sha256.hex(jpeg));
        var done = uploads.complete(owner.getId(), uploadId);
        UUID versionId = versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId())
            .orElseThrow().getId();
        var result = ingest.ingest(done.nodeId(), versionId).orElseThrow();
        return new Photo(done.nodeId(), result.assetId());
    }

    @Test
    void timelineIsOrderedByTakenAtDescAndScopedToOwner() throws Exception {
        var owner = newMember();
        var older = uploadPhoto(owner, "2026-01-15T08:00:00");
        var newer = uploadPhoto(owner, "2026-03-20T08:00:00");

        var items = timeline.timeline(principal(owner), null, 50, null);

        assertThat(items).extracting(TimelineItem::assetId)
            .containsExactly(newer.assetId(), older.assetId());
        assertThat(items.get(0).takenAt()).isAfter(items.get(1).takenAt());
        assertThat(items.get(0).name()).startsWith("tl-").endsWith(".jpg");
        assertThat(items.get(0).sizeBytes()).isGreaterThan(0);
        assertThat(timeline.timeline(principal(owner), null, 50, MediaKind.VIDEO)).isEmpty();
    }

    @Test
    void otherUserCannotSeeOrReadOthersMedia() throws Exception {
        var owner = newMember();
        var photo = uploadPhoto(owner, "2026-02-02T08:00:00");
        var stranger = newMember();
        files.ensureUserHome(stranger); // 有 home 但无任何授权:时间线必须为空

        assertThat(timeline.timeline(principal(stranger), null, 50, null)).isEmpty();

        // 尚未创建 home 的成员:可读根为空,列表/相册都必须安全返回空
        var homeless = newMember();
        assertThat(timeline.timeline(principal(homeless), null, 50, null)).isEmpty();
        assertThat(timeline.albumsDates(principal(homeless))).isEmpty();

        assertThatThrownBy(() -> timeline.requireReadable(principal(stranger), photo.assetId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    void trashedMediaDisappearsFromTimelineAndRead() throws Exception {
        var owner = newMember();
        var kept = uploadPhoto(owner, "2026-04-01T08:00:00");
        var trashed = uploadPhoto(owner, "2026-04-02T08:00:00");

        files.trash(principal(owner), trashed.nodeId());

        var items = timeline.timeline(principal(owner), null, 200, null);
        assertThat(items).extracting(TimelineItem::assetId).contains(kept.assetId());
        assertThat(items).extracting(TimelineItem::nodeId).doesNotContain(trashed.nodeId());
        assertThatThrownBy(() -> timeline.requireReadable(principal(owner), trashed.assetId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    void cursorPaginationWalksAllItemsWithoutOverlap() throws Exception {
        var owner = newMember();
        var first = uploadPhoto(owner, "2026-01-10T08:00:00");
        var middle = uploadPhoto(owner, "2026-02-10T08:00:00");
        var last = uploadPhoto(owner, "2026-03-10T08:00:00");

        var page1 = timeline.timeline(principal(owner), null, 2, MediaKind.IMAGE);
        assertThat(page1).extracting(TimelineItem::assetId)
            .containsExactly(last.assetId(), middle.assetId());

        var cursor = page1.get(1).takenAt() + "|" + page1.get(1).assetId();
        var page2 = timeline.timeline(principal(owner), cursor, 2, MediaKind.IMAGE);
        assertThat(page2).extracting(TimelineItem::assetId).containsExactly(first.assetId());

        var all = page1.stream().map(TimelineItem::assetId)
            .collect(java.util.stream.Collectors.toSet());
        page2.forEach(i -> all.add(i.assetId()));
        assertThat(all).containsExactlyInAnyOrder(first.assetId(), middle.assetId(), last.assetId());

        // limit 下限钳制为 1;非法游标是 400 而不是 500
        assertThat(timeline.timeline(principal(owner), null, 0, MediaKind.IMAGE)).hasSize(1);
        assertThatThrownBy(() -> timeline.timeline(principal(owner), "not-a-cursor", 10, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cursor");
    }

    @Test
    void bucketsGroupByMonth() throws Exception {
        var owner = newMember();
        uploadPhoto(owner, "2026-05-05T08:00:00");
        uploadPhoto(owner, "2026-05-25T08:00:00");
        // 本地时间月初 00:30(UTC 仍是上个月末):桶必须按本地时区归到 6 月(R16/R13)
        uploadPhoto(owner, "2026-06-01T00:30:00");
        uploadPhoto(owner, "2026-07-01T08:00:00");

        var buckets = timeline.buckets(principal(owner), 2026, MediaKind.IMAGE);

        assertThat(buckets).containsExactly(
            new PeriodCount("2026-07", 1),
            new PeriodCount("2026-06", 1),
            new PeriodCount("2026-05", 2));
        assertThat(timeline.buckets(principal(owner), 2026, MediaKind.VIDEO)).isEmpty();

        var albums = timeline.albumsDates(principal(owner));
        assertThat(albums).containsExactly(new YearMonths(2026, buckets));
    }

    @Test
    void aclGrantsExposeSharedMediaAndDenyEntriesAreIgnored() throws Exception {
        var owner = newMember();
        var photo = uploadPhoto(owner, "2026-06-06T08:00:00");
        var home = files.ensureUserHome(owner);

        var direct = newMember();
        var grouped = newMember();
        var denied = newMember();
        var group = groupService.create("tl-grp-" + UUID.randomUUID().toString().substring(0, 8));
        groupService.addMember(group.id(), grouped.getId());

        assertThat(timeline.timeline(principal(direct), null, 50, null)).isEmpty();

        acls.setAcl(principal(owner), home.getId(), List.of(
            new AclService.EntryDto("USER", direct.getId(), "r", true),
            new AclService.EntryDto("GROUP", group.id(), "r", true),
            // 含 r 但同时含显式拒绝 -,不得成为可见性根
            new AclService.EntryDto("USER", denied.getId(), "r-", true)));

        assertThat(timeline.timeline(principal(direct), null, 50, null))
            .extracting(TimelineItem::assetId).containsExactly(photo.assetId());
        assertThat(timeline.timeline(principal(grouped), null, 50, null))
            .extracting(TimelineItem::assetId).containsExactly(photo.assetId());
        assertThat(timeline.timeline(principal(denied), null, 50, null)).isEmpty();

        assertThat(timeline.requireReadable(principal(direct), photo.assetId()).getId())
            .isEqualTo(photo.assetId());
        assertThat(timeline.requireReadable(principal(grouped), photo.assetId()).getId())
            .isEqualTo(photo.assetId());
    }

    @Test
    void trashingDirectoryPrunesItsSubtreeAndRestoreBringsItBack() throws Exception {
        var owner = newMember();
        var home = files.ensureUserHome(owner);
        var dir = files.createDir(principal(owner), home.getId(), "trip-" + UUID.randomUUID());
        var photo = uploadPhoto(owner, dir.id(), "2026-09-09T08:00:00");

        assertThat(timeline.timeline(principal(owner), null, 200, null))
            .extracting(TimelineItem::assetId).contains(photo.assetId());

        // M2 回收站是逐节点的:删目录不会级联标记子节点,时间线必须自行剪掉整棵子树
        files.trash(principal(owner), dir.id());
        assertThat(timeline.timeline(principal(owner), null, 200, null))
            .extracting(TimelineItem::assetId).doesNotContain(photo.assetId());
        assertThat(timeline.buckets(principal(owner), 2026, MediaKind.IMAGE)).isEmpty();

        files.restore(principal(owner), dir.id());
        assertThat(timeline.timeline(principal(owner), null, 200, null))
            .extracting(TimelineItem::assetId).contains(photo.assetId());
    }

    @Test
    void adminTimelineSeesAllTopLevelMediaWhileMembersSeeOnlyTheirOwn() throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        var member = newMember();
        var memberPhoto = uploadPhoto(member, "2037-10-01T08:00:00");
        var adminPhoto = uploadPhoto(admin, "2037-10-02T08:00:00");

        assertThat(timeline.timeline(principal(admin), null, 200, null))
            .extracting(TimelineItem::assetId)
            .contains(memberPhoto.assetId(), adminPhoto.assetId());
        // ADMIN 的列表可见性与单条读取(AclService 短路)保持一致
        assertThat(timeline.requireReadable(principal(admin), memberPhoto.assetId()).getId())
            .isEqualTo(memberPhoto.assetId());

        assertThat(timeline.timeline(principal(member), null, 200, null))
            .extracting(TimelineItem::assetId)
            .contains(memberPhoto.assetId())
            .doesNotContain(adminPhoto.assetId());
        assertThat(timeline.buckets(principal(member), 2037, MediaKind.IMAGE))
            .containsExactly(new PeriodCount("2037-10", 1));
    }

    @Test
    void requireReadableReturnsAssetForOwner() throws Exception {
        var owner = newMember();
        var photo = uploadPhoto(owner, "2026-08-08T08:00:00");

        var asset = timeline.requireReadable(principal(owner), photo.assetId());

        assertThat(asset.getId()).isEqualTo(photo.assetId());
        assertThat(asset.getNodeId()).isEqualTo(photo.nodeId());
        assertThat(asset.getKind()).isEqualTo(MediaKind.IMAGE);
    }
}
