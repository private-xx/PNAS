# M3 媒体设计(入库 / 缩略图 / 时间线 / 自动相册 / 直放播放 / 任务中心)

> 里程碑 M3。上游基线:[requirements-analysis.md](../../requirements-analysis.md) v0.3(FR-MEDIA-01~07、FR-SYS-01、SC-2/SC-3)、[architecture-design.md](../../architecture-design.md) v0.3(§5.3 数据模型、§6.5 媒体入库与时间线、§7 API 契约、ADR-06/07/08/09)。
>
> 本文档是 M3 的实现依据;经评审通过后由 `writing-plans` 拆为实施计划。

## 1. 目标与出口标准

**目标**:照片/视频能自动入库并被组织成可浏览的时间线,M3 首次让"媒体"成为可用功能。

**出口标准**(可测):

| # | 标准 | 验证方式 |
|---|---|---|
| E1 | 经 Web 批量上传的照片自动入库,时间线按 **月/日** 分组可见,并显示缩略图 | 验收脚本:批量上传 → 轮询 job → 时间线返回条目且缩略图 200 |
| E2 | 时间线**跨用户不可见**:成员看不到他人照片 | 自动化:成员读他人单条媒体 → **404**(不泄露存在性);时间线不含他人条目 |
| E3 | 图片查看器可用:渐进加载(缩略图→原图)、左右旋转、下载原图 | 手工 + 组件/接口测试 |
| E4 | 浏览器**直放格式视频**可在线播放(支持拖动进度) | 验收:MP4 经 `/content` 返回 206 + Content-Range |
| E5 | **日期相册**可按年/月浏览,计数正确 | 验收:桶计数与上传张数一致 |
| E6 | 后台任务**可观测**:任务中心可列出/筛选/重试/取消,并可触发"重建媒体索引" | 验收:触发 reindex → 任务出现并成功 |
| E7 | 时间线首屏性能:1000 张照片 ≤ 2s(预热后,SC-3) | 生成的 1000 张 fixture 实测,记录口径 |

## 2. 范围

**包含**:媒体识别与自动入库、EXIF 元数据提取、异步缩略图(256/512)、时间线(游标分页 + 日期分桶)、图片查看器、直放视频播放、日期自动相册、任务中心最小 UI + 重建媒体索引。

**不包含(明确排除,避免范围蔓延)**:

| 项 | 去向 |
|---|---|
| HEIC / WEBP / RAW **缩略图** | M5(ffmpeg);M3 仅入库并在时间线以占位图显示 |
| 视频**封面帧**、视频转码、HLS 自适应码率 | M5(FR-STREAM-03、ADR-08) |
| **地点相册**(GPS 逆地理/分桶视图) | 本设计已按确认**推迟到 M5**;M3 仍提取并存 GPS 坐标 |
| 手动相册、标签、收藏 | M5(FR-MEDIA-05 的 S 级部分) |
| 人脸/物体识别 | M7(FR-MEDIA-08) |
| 鉴权票据 URL | M5;M3 用会话 Cookie + ACL(见 D1) |
| 媒体库刮削(libraries 表的使用) | M5(FR-STREAM-01) |

## 3. 已确认决策

| # | 决策 | 理由 / 代价 |
|---|---|---|
| **D1** | 缩略图/原图读取用**会话 Cookie + 服务端 ACL 校验**;不做签名票据 | 同源 `<img>` 自动带 Cookie,与 M2 一致、实现最小;代价:URL 不能脱离会话分享(家庭自用可接受) |
| **D2** | 缩略图**不建新表**:文件按 `derived/thumbs/<sha前2>/<version_sha>-<size>.jpg` 内容寻址命名,DB 只在 `media_assets.thumb_status` 记状态 | 派生资源可再生、天然幂等与去重;代价:换尺寸/换算法需按命名规则重生成 |
| **D3** | HEIC/WEBP/视频封面 `thumb_status=SKIPPED`,前端显示占位图 | 纯 Java 路线(用户确认);M5 引入 ffmpeg 后补齐 |
| **D4** | 视频时长用**纯 Java 解析 MP4/MOV 的 `mvhd` box**;WebM 时长为 null | 不引 ffprobe,零外部二进制;代价:WebM 无时长 |
| **D5** | 唯一新增依赖:`com.drewnoakes:metadata-extractor:2.19.0`(+传递 `xmpcore 6.1.11`) | 已用 `dependency:get` + `javac` 冒烟验证可获取、可加载 |
| **D6** | 本机 Maven 用绝对路径 `/Users/xiao/hui/soft/maven3.8/bin/mvn`(3.8.1),配 `.tools/maven-settings.xml` 与 `.tools/jdk-21.0.12.1+1` | 本 shell PATH 极简,`mvn` 不在 PATH;经镜像源可拉依赖 |
| **D7** | 自动入库范围:**所有**写入完成的图片/视频,时间线按 ACL 过滤;另提供管理员「重建媒体索引」回填既有文件 | 与 FR-MEDIA-07/SC-2 对齐,零操作;管理员可随时重扫 |
| **D8** | 相册:M3 **只做日期相册**;地点相册、手动相册推迟 | 用户确认;GPS 仍提取入库 |
| **D9** | EXIF 拍摄时间按 **JVM 默认时区**解释(本地墙上时间) | 单时区家庭 NAS 下时间线按本地日期分组正确;多时区留 M5 |

## 4. 数据模型(迁移 `V3__media.sql`)

```sql
create table media_assets (
    id                uuid primary key,
    file_version_id   uuid not null unique references file_versions(id) on delete cascade,
    node_id           uuid not null references nodes(id) on delete cascade,
    kind              text not null,          -- IMAGE | VIDEO
    taken_at          timestamptz not null,   -- EXIF 拍摄时间;缺失回退文件 mtime
    taken_at_source   text not null,          -- EXIF | MTIME
    width             int,
    height            int,
    gps_lat           double precision,       -- 仅存储,M3 不做地点视图
    gps_lon           double precision,
    duration_ms       bigint,
    thumb_status      text not null,          -- PENDING | READY | FAILED | SKIPPED
    indexed_at        timestamptz not null
);

create index ix_media_timeline on media_assets (taken_at desc, id desc);
create index ix_media_node     on media_assets (node_id);
create index ix_media_thumb    on media_assets (thumb_status);
```

要点:
- `file_version_id` 唯一 ⇒ **每次覆盖写产生新版本即产生一条新媒体记录**(与 FR-FS-04 版本模型一致,旧版本记录随版本删除级联清理)。
- `node_id` 冗余存储 ⇒ 时间线/权限过滤与回收站排除无需额外 join `file_versions`。
- 不新增 thumbs 表(D2)。

## 5. 组件设计(新包 `com.pnas.server.media`)

```
media/
├── domain/MediaAsset.java               # 实体(kind/takenAt/thumbStatus 枚举)
├── MediaAssetRepository.java            # 游标分页、桶聚合、按版本/节点查询、待处理扫描
├── MediaTypes.java                      # 扩展名+MIME 白名单 → IMAGE/VIDEO/非媒体;是否可生成缩略图
├── ExifReader.java                      # metadata-extractor:拍摄时间/Orientation/GPS/尺寸
├── Mp4DurationReader.java               # 纯 Java 解析 mvhd 时长(MP4/MOV/M4V)
├── ThumbnailService.java                # 解码→按 Orientation 旋转→缩放→JPEG 原子落盘;幂等
├── MediaIngestService.java              # 迁移/幂等 upsert(供 scan job 调用)
├── MediaTimelineService.java            # 时间线游标分页 + 日期桶 + 可见性过滤
├── MediaController.java                 # /api/v1/media/**
└── job/MediaScanHandler.java            # media.scan  → media.thumb
    job/MediaThumbHandler.java           # media.thumb
    job/MediaReindexHandler.java         # media.reindex(ADMIN,分页推进)
```

**职责与接口要点**

- `MediaTypes.isMedia(name, mime)` → `IMAGE|VIDEO|NONE`;`canThumbnail(kind, name)` → JPEG/PNG/GIF/BMP/TIFF=true,HEIC/WEBP/视频=false(SKIPPED)。
- `ExifReader.read(InputStream)` → `(takenAt, orientation, width, height, gpsLat, gpsLon)`;解析失败不抛业务异常,返回空结果(降级见 §7)。
  - **时区语义(R13)**:EXIF 无时区,拍摄时间按 **JVM 默认时区**解释为本地墙上时间(家庭单时区;可配置时区留 M5);测试用 `ZoneId.systemDefault()` 固定期望值并覆盖「午夜附近仍落在同一本地日期」。
- `ThumbnailService.generate(versionSha, size)`:
  - 目标路径 `derived/thumbs/<sha前2>/<versionSha>-<size>.jpg`;**已存在即返回**(幂等)
  - 解码用 `ImageIO.read`;Orientation 5/6/7/8 做 90°/180° 旋转;长边等比缩放到 size(不放大);JPEG 质量 0.82
  - 临时文件 + `ATOMIC_MOVE`(与 blobstore 同法);失败清理临时文件
- `MediaIngestService.ingest(nodeId, versionId)`:读版本 → 判定类型 → 提取元数据 → upsert(以 `file_version_id` 幂等)→ 返回是否需要缩略图
- `MediaTimelineService`:
  - 可见性:PostgreSQL **递归 CTE** 从「我的 home 根 + 我所属组的共享根 + 显式授予我/我的组的节点」向下展开 `nodes`,得到我可读节点集,再 join `media_assets` 且 `nodes.trashed_at is null`
  - **可见性边界(R31)**:ACL 授权根的子树的**列表**按「授权型根」整棵展开,不折叠后代 `DENY`/`inherited=false`;后者只在单条读取(`requireReadable`)时生效,因此可能出现「列表可见、打开 404」的单向过度可见。该方向不产生跨用户泄漏(未共享的子树永不可见);M3 无共享 UI,接受此边界,**M5 引入共享 UI 时**必须把 DENY/inherited 链折叠进 CTE 或改为逐节点判定。
  - 分页:游标 `(taken_at, id)` 倒序,`limit` 默认 60、上限 200;**不用 offset**(避免深分页退化与插入抖动)
  - 桶:`date_trunc('month'|'day', taken_at)` 聚合计数,同样受可见性过滤
- **触发点**:`UploadService.complete()` 成功后 `jobService.enqueue("media.scan", {nodeId, versionId})`;入队失败不影响上传成功(仅记日志,等待 reindex 兜底)

**任务类型**

| type | 载荷 | 幂等性 | 备注 |
|---|---|---|---|
| `media.scan` | `{nodeId, versionId}` | 以 `file_version_id` upsert 结果一致 | 非媒体文件 → 直接完成(不产生记录) |
| `media.thumb` | `{nodeId, versionId, size}` | 目标文件存在即跳过;READY 需 256+512 同时存在 | 仅当 `canThumbnail`;handler 读取 EXIF Orientation 后再渲染(R17) |
| `media.reindex` | `{cursor?}` | 遍历所有 FILE 节点,重复执行结果一致 | ADMIN 触发;分批(每批 200)自链式入队,避免长任务 |

## 6. API 契约(`/api/v1`)

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/media/timeline?cursor=&limit=&kind=ALL\|IMAGE\|VIDEO` | 时间线条目:`{assetId, nodeId, name, kind, takenAt, width, height, durationMs, thumbStatus, sizeBytes}` | 登录 + 该节点 `r` |
| GET | `/media/timeline/buckets?year=&kind=` | `[{period:"2026-09", count}]`(月桶;`year` 缺省为最近) | 同上 |
| GET | `/media/assets/{id}` | 详情(含 `takenAtSource`、尺寸、时长、GPS 是否存在) | 同上 |
| GET | `/media/assets/{id}/thumb?size=256\|512` | JPEG 字节;`ETag: "<versionSha>-<size>"`,`Cache-Control: private, max-age=604800`;不支持 → 404 `thumb_unsupported` | 同上 |
| GET | `/media/assets/{id}/content` | 原图/视频字节,**支持 Range**(复用 M2 逻辑) | 同上 |
| GET | `/media/albums/dates` | `[{year, months:[{period:"YYYY-MM", count}]}]`(实现字段为 `period`) | 同上 |
| POST | `/media/reindex` | 触发重建(202 + jobId) | ADMIN |

任务中心复用 M2 已有接口:`GET /jobs?state=`、`POST /jobs/{id}/retry`、`POST /jobs/{id}/cancel`、`GET /jobs/{id}`。

错误契约沿用 M2 统一错误体 `{code,message,detail}`;新增/复用 code:`forbidden`、`not_found`、`thumb_unsupported`、`bad_request`。

## 7. 错误处理与降级矩阵

| 情况 | 行为 |
|---|---|
| 文件非媒体类型 | `media.scan` 直接成功结束,不写记录 |
| EXIF 无拍摄时间 | `taken_at` = 该版本文件 mtime,`taken_at_source=MTIME` |
| EXIF 解析抛异常 | 同"无拍摄时间"降级,`thumb_status` 不影响 |
| 格式不支持缩略图(HEIC/WEBP/视频) | `thumb_status=SKIPPED`;前端占位图 + 显示类型图标 |
| 缩略图生成失败(损坏文件等) | `thumb_status=FAILED`,job 走 M2 重试/死信;前端占位图 |
| 版本被覆盖/删除 | `file_version_id` 级联 → 记录消失;缩略图文件成为孤儿,由后续 M5 的 `derived` 清理 job 回收(可再生) |
| 文件进回收站 | `nodes.trashed_at` 非空 → 时间线自动排除;恢复后自动回归 |
| 无权访问他人媒体 | 列表过滤;单条访问返回 404(不泄露存在性) |
| 入队失败 | 上传仍成功;仅记 WARN,可用 reindex 兜底 |

## 8. 前端设计

- 路由 `/media`(**时间线**):月/日粘性分组标题;缩略图 `loading="lazy"` + `IntersectionObserver` 增量加载;列数按容器宽度自适应(手机 2–3 列、桌面 5–6 列);点击打开查看器;顶部"年/月"跳转(用 buckets)
- **图片查看器**:先显示 512 缩略图 → 后台加载原图替换(渐进);左右旋转(仅显示层,不改文件);下载原图;键盘 ←/→ 翻页、Esc 关闭;手机支持左右滑动
- **视频播放**:原生 `<video controls>` 指向 `/media/assets/{id}/content`;不可直放(或 M3 不支持的容器)提示"该格式需 M5 转码后播放"
- 路由 `/media/albums`(**日期相册**):年/月网格 + 计数,点入即过滤后的时间线;不渲染地点相册(D8)
- 路由 `/admin/jobs`(**任务中心**):任务列表(类型/状态/优先级/尝试次数/时间/错误)、按状态筛选、重试/取消、**「重建媒体索引」**按钮(触发 `POST /media/reindex`)
- 上传页:沿用 M2 分块上传(已支持多选);上传完成后提示"已加入媒体库,正在生成缩略图"

## 9. 性能设计(SC-3)

- 首屏 ≤2s 的前提:**缩略图已在后台生成**(D1 的异步管线),前端只请求已存在的 256 缩略图
- 时间线查询走 `ix_media_timeline` 覆盖排序;可见性 CTE 结果在 1000–10⁵ 节点规模下可接受(实测口径见 §11)
- 缩略图响应带 `ETag` + `Cache-Control: private, max-age=7d`,二次访问走 304
- 不做的优化(YAGNI):图片 CDN、WebP 转码、HTTP/2 推送、缩略图内存缓存

## 10. 测试策略

**单元**:`MediaTypes` 判定表(扩展名↔MIME↔是否可缩略图)、`ExifReader`(带 EXIF 的 JPEG fixture:拍摄时间/Orientation/GPS/尺寸;无 EXIF 的 PNG)、`Mp4DurationReader`(构造最小 `mvhd`)、`ThumbnailService`(缩放长边正确、Orientation 6 旋转 90°、不放大、已存在即跳过)。

**集成(Testcontainers,沿用 M2 基座)**:上传 JPEG → `media.scan` → `media_assets` 有记录且 `taken_at_source=EXIF`;`media.thumb` → 缩略图字节可解码且尺寸正确;**跨用户**:成员访问他人媒体 404、时间线不含;回收站文件不出现在时间线;`kind=VIDEO` 过滤;桶计数;`media.reindex` 幂等(重复执行不产生重复记录);HEIC 占位路径 `thumb_status=SKIPPED`。

**验收脚本** `scripts/acceptance-m3.sh`(幂等):
1. 管理员 + 成员登录;成员上传 3 张带 EXIF 的 JPEG(含不同月份)与 1 个 MP4
2. 轮询 `/jobs?state=` 直到 `media.scan`/`media.thumb` 终态(超时即失败)
3. 断言:时间线含 4 条;日期桶计数与上传月份一致;`/thumb?size=256` 返回 200 且 `Content-Type: image/jpeg`;`/content` 对 MP4 返回 206 + `Content-Range`
4. 断言:成员读管理员媒体 → 404;成员时间线不含管理员条目
5. 断言:删除(回收站)后时间线不再含该条,恢复后回来
6. 触发 `POST /media/reindex` → 任务成功且记录数不增(幂等)

## 11. 验收清单(M3 完成判据)

- [ ] `V3__media.sql` 迁移在空库与既有库上均可应用
- [ ] E1–E7 全部通过,证据记入 `docs/progress.md`
- [ ] 后端全套件(含 M2 的 47 项)保持绿色;新增媒体测试全绿
- [ ] 前端 `npm run build` 通过;时间线/查看器/相册/任务中心四个视图可用
- [ ] 1000 张 fixture 的时间线首屏实测 ≤2s 并记录口径(预热后、局域网参考浏览器)
- [ ] 交付与裁决记入 `docs/`(设计裁决 D1–D9、遗留项)

## 12. 风险与后续衔接

**已接受边界(M3,已验证/已记录)**:①并发同版本 ingest 先查后插可能撞 `file_version_id` 唯一约束 → 任务瞬时 FAILED,由队列重试自愈(R32);②`/content` 取节点**最新版本**字节,而详情/缩略图绑定**索引版本**,覆盖写后未重扫前两者可能短暂不一致;③`taken_at_source=MTIME` 实际取版本创建时间(上传时间),非文件 mtime;④HEIC 改名成 JPG 后再 reindex 仍为 `SKIPPED`;⑤验收脚本的"MP4"是占位字节、月桶仅覆盖单月(真实时长与多月分桶由单元/集成测试覆盖)。

| 风险 | 缓解 |
|---|---|
| 递归 CTE 在大目录树下变慢 | 先实测(§9);必要时把"可读根集"缓存到会话/内存,或给 `nodes(parent_id)` 加索引(M2 已有部分索引,按需补) |
| `media_assets` 随版本增长(每次覆盖写一条) | 与版本模型一致;版本轮换策略(M5)落地后随之收敛 |
| 纯 Java 缩略图质量/速度 | 长边缩放到 256/512 的规模下足够;若不足,M5 切 ffmpeg 时保持接口不变 |
| HEIC 占比高的用户体验 | 占位图 + 明确提示"M5 支持";M5 落地后对既有文件批量 reindex 即补齐 |
| 缩略图磁盘占用 | 可再生;M5 的 derived 清理 job 兜底 |
