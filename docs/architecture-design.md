# PNAS 系统架构设计

| 项 | 值 |
|---|---|
| 版本 | v0.2(已确认稿) |
| 上游 | M0 需求分析 docs/requirements-analysis.md v0.2(已确认) |
| 阶段 | M1 架构设计 ✅ 通过 |
| 状态 | ✅ ADR-01~12 全部确认(2025),进入 M2 |

---

## 1. 架构目标与约束

架构必须满足 M0 确认的约束:

1. **纯从零自研**(存储语义、上传/备份协议、权限模型自研;允许少量成熟库与外部二进制);
2. **技术栈:Java 后端 + Vue 前端**;
3. **家庭小团队(2–8 人)**:并发低但数据量可不小(照片/视频),单机为中心;
4. **先软件后硬件**:架构无关(x86_64 / ARM64 Linux),单盘可跑通,容器化部署优先;
5. **v1 范围**:多用户 + 目录 ACL + 文件管理/分块上传下载/回收站/版本/去重 + Web 全端 + 照片媒体管理 + 影音(刮削/转码/播放)+ 自动上传备份(macOS/Windows Agent);
6. **可靠性优先**:先数据后元数据、内容校验、静默损坏可发现、数据与系统分离;
7. **单人可维护**:模块边界清晰、测试充分、运维面最小化(少组件 = 少故障点)。

设计取舍总原则:**用一台消费级主机的算力与存储,换取架构简单、数据安全、代码可控**。

---

## 2. 总体架构:模块化单体(Modular Monolith)

```
                 ┌──────────────────────────────────────────────┐
 浏览器(Vue SPA) │                PNAS Server(单进程)            │
 ───────────────►│ ┌──────────────────────────────────────────┐  │
    (经 nginx)   │ │  web/api 层   (REST /api/v1 + SSE 事件)    │  │
                 │ ├──────────────────────────────────────────┤  │
                 │ │ 应用服务层 auth·iam·files·media·stream·    │  │
                 │ │            backup·job·audit·notify        │  │
                 │ ├──────────────────────────────────────────┤  │
                 │ │ 领域层 存储语义(blobstore/版本/ACL/回收站)   │  │
                 │ ├──────────────────────────────────────────┤  │
                 │ │ worker(同进程线程池,可按 profile 拆进程)     │  │
                 │ └──────────────────────────────────────────┘  │
                 └──────┬──────────────┬──────────────┬─────────┘
                        │              │              │
              ┌─────────▼───┐  ┌───────▼──────┐  ┌────▼──────────┐
              │ PostgreSQL  │  │ 数据目录(blobs/│  │ ffmpeg(转码)  │
              │ (元数据/会话/ │  │ derived/tmp)  │  │ (镜像内置二进制)│
              │  任务/审计)  │  │  挂载卷       │  └───────────────┘
              └─────────────┘  └──────────────┘
Agent(macOS/Windows):走与 Web 相同的分块上传协议(M4)
```

### 2.1 关键决策 ADR-01:为什么是"模块化单体"而不是微服务
- 用户规模 2–8、单机部署,微服务的分布式复杂度(网络分区、事务、可观测性)与收益完全不成比例;
- 以**包边界 + 模块间仅经接口调用 + ArchUnit 架构测试**保证可维护性,未来确有需要再拆;
- 运行形态:生产默认**单进程**同时提供 API 与后台 worker(worker 用独立线程池 + 虚拟线程);预留 `--worker-only` profile,便于将来把耗时任务拆到第二进程而不改代码结构。前端静态资源由独立 nginx 托管(ADR-07)。

---

## 3. 技术选型

### 3.1 后端
| 组件 | 选型 | 理由 |
|---|---|---|
| 语言 | Java 21 LTS | 虚拟线程(高并发 IO 成本低)、record/sealed/模式匹配,现代且稳定 |
| 框架 | Spring Boot 3.3+ | 生态成熟、安全(Spring Security)、任务调度、自动配置;选型约束 Q1 |
| 构建 | Maven(单 reactor 多模块) | 主流、稳定;`pnas-common`/`pnas-server`/`pnas-agent`(M4)共享协议 DTO |
| 元数据库 | PostgreSQL 16 ✅(ADR-03) | 强一致事务、JSONB、行级安全、可靠性;单容器运维简单 |
| 迁移 | Flyway | 版本化 schema 迁移,向后兼容 |
| ORM | Spring Data JPA(Hibernate) | 常规 CRUD 开发效率;复杂报表/巡检用原生 SQL |
| 安全 | Spring Security + argon2id | 认证授权、CSRF、安全头;口令用 argon2id |
| 会话 | 服务端会话存 DB ✅(ADR-05) | 可吊销、可审计、天然支持设备管理 |
| 文件哈希/EXIF | JDK + metadata-extractor(或自研解析最小集) | 图片 EXIF 解析 |
| 转码 | 外部 ffmpeg 二进制(镜像内置) | 不自研编解码;CommandRunner 接口可插拔(ADR-08) |
| 测试 | JUnit5 + Testcontainers + ArchUnit | 集成测试真库真盘;架构边界测试 |
| 日志/监控 | SLF4J + 结构化 JSON;Micrometer(基础指标) | 统一日志、健康端点 |

### 3.2 前端
| 组件 | 选型 | 理由 |
|---|---|---|
| 框架 | Vue 3 + TypeScript | 选型约束 Q1;组合式 API、生态成熟 |
| 构建 | Vite | 开发快、构建简单 |
| 状态/路由 | Pinia + Vue Router | Vue 官方生态 |
| UI 组件 | Element Plus | 表格/表单/上传/树组件齐全,中文友好 |
| HTTP | axios(自定义重试)+ 自研分块上传 SDK | 断点续传需要精细控制 |
| 媒体 | hls.js(播放)+ 原生 video 兜底 | 转码产物为 HLS |
| 测试 | Vitest + Playwright(可选) | 组件/端到端 |
| 托管 | **独立 nginx 容器** ✅(ADR-07) | 托管静态资源 + 反代 /api 与 SSE 到后端;贴近生产惯例 |

### 3.3 Agent(桌面备份, M4 实现)
- Java 21 CLI + `jpackage` 打包 macOS(.dmg/.pkg)与 Windows(.msi);带 JRE,用户无需预装 Java;
- 无头 + 轻量托盘可选(先 CLI + launchd/任务计划注册定时);
- 与 Web **同一套分块上传协议 + 设备令牌(PAT)**,服务端零额外协议面。

---

## 4. 后端模块划分(包边界)

Maven 模块:
| 模块 | 职责 |
|---|---|
| `pnas-common` | 领域常量、错误码、协议 DTO、工具(与 server/agent 共享) |
| `pnas-server` | 单体服务:所有包与功能 |
| `pnas-agent`(M4) | 桌面备份客户端 |

`pnas-server` 内部包(分层 + 按领域分包,依赖方向:web → service → domain;禁止反向):
```
com.pnas.server
├── common/     # 配置、异常、日志、事件
├── web/        # REST controller、SSE、DTO 转换、OpenAPI
├── auth/       # 登录、会话、CSRF、设备令牌(PAT)
├── iam/        # 用户/组/角色、目录 ACL 模型与判定引擎
├── files/      # 目录树、文件版本、回收站、配额
├── blobstore/  # 内容寻址分块存储、GC、完整性巡检(核心自研)
├── media/      # 媒体入库、EXIF、缩略图、时间线索引、相册
├── stream/     # 媒体库、刮削接口、转码流水线、播放鉴权票据
├── backup/     # 上传会话(与 files 复用)、Agent 设备管理、备份任务
├── job/        # DB 任务队列与调度、worker 执行器
├── audit/      # 审计日志(追加式)
└── notify/     # 站内通知、SSE、通知通道(邮件/Webhook 预留)
```

架构约束用 **ArchUnit** 固化:web 不得直接访问 blobstore;domain 不得依赖 web 等。

---

## 5. 存储设计(核心自研语义)

### 5.1 物理数据布局(与系统盘分离)
```
<数据根目录>/                    # 由配置指定,如 /var/lib/pnas 或外接盘挂载点
├── blobs/                       # 内容寻址数据块(唯一"真数据",只读不可变)
│   └── ab/ cdef0123…            # sha256 前 2 位分桶
├── derived/                     # 派生缓存:缩略图、转码 HLS(可再生,非数据)
├── tmp/                         # 上传暂存(分块落盘区)
└── export/                      # 出站备份/导出暂存(M4)
```
- 元数据(目录树、版本、ACL、任务、审计)全部在 PostgreSQL;
- **数据 ≠ 元数据**:blobs 目录本身可被 `rsync`/出站备份工具整体复制,恢复时只需"搬 blobs + 恢复 DB"。

### 5.2 内容寻址分块存储(Blobstore) — ADR-06
| 项 | 决策 |
|---|---|
| 分块大小 | 4 MiB(固定,上传/去重/断点续传的单位) |
| 寻址 | 每块 sha256 → 文件名即哈希;相同块物理只存一份(**天然去重**,含跨用户) |
| 不可变性 | blob 只写一次、只读;内容不变 → 校验成本低 |
| 写入路径 | 分块 → `tmp` 校验 sha256 → 原子 move 入 `blobs`(同名已存在则丢弃新块) |
| 清理 | 引用计数表 + 低频 GC job(标记-清扫);孤儿块回收有保护期 |
| 巡检 | 周期 job 抽样/全量重算哈希,发现静默损坏 → 告警(FR-SYS-06) |
| 派生缓存 | 一律可再生成,损坏不告警只重建 |

### 5.3 逻辑数据模型(关键表)
```
users(id, username uq, display_name, password_hash(argon2id), role[ADMIN/MEMBER],
      status, created_at)
groups(id, name) / group_members(user_id, group_id)

nodes(id, parent_id→nodes, owner_id, kind[DIR/FILE], name, size, trashed_at,
      created_at, updated_at)                 -- 目录树,含"我的文件/共享/回收站"虚拟根
  (parent_id, name) 唯一(排除已回收)

file_versions(id, node_id, version_no, size_bytes, mime_type,
              manifest_sha256, created_by, created_at, meta jsonb)  -- 文件每写一版
version_chunks(id, version_id, seq, blob_hash, size)                -- 版本→块清单(供 GC/校验)

acl_entries(id, node_id, principal_type[USER/GROUP], principal_id,
            perms bitmask[R/W/D/ADMIN], inherit bool)   -- 目录级 ACL

upload_sessions(id, user_id, dest_parent_id, filename, total_size, chunk_size,
                state[OPEN/COMPLETE/CANCELLED], received_chunks jsonb, created_at)

media_assets(id, file_version_id uq, taken_at, width, height, gps jsonb,
             duration_ms, thumb_status, indexed_at)    -- 媒体时间线索引
libraries(id, name, root_node_id, type[MOVIE/TV/PHOTO], options jsonb)
library_items(id, library_id, node_id uq, meta jsonb)  -- 刮削结果/手动录入

jobs(id, type, payload jsonb, state, attempt, max_attempts, priority,
     next_run_at, started_at, finished_at, error, result jsonb)
audit_events(id, at, actor_id, action, resource_type, resource_id,
             ip, detail jsonb)
http_sessions(id, user_id, token_hash, created_at, expires_at, revoked_at)
devices(id, user_id, name, pat_hash, last_seen_at, platform)   -- Agent 设备与令牌
notifications(id, user_id, level, title, body, read_at, created_at)
app_settings(key, value jsonb)
```

**版本模型(FR-FS-04)**:`nodes` 中 FILE 节点是"名字的稳定锚点";每次覆盖上传 = 新增一条 `file_versions` 并更新节点指针。历史版本随时可预览/回滚;版本数上限与轮换策略可配。目录本身不建版本(v1 语义,M7 镜像同步时再扩展)。

**回收站(FR-FS-03)**:删除 = 节点打 `trashed_at`(软删除)+ 挂到用户回收站虚拟根;保留期后由 job 彻底清除(清版本引用 → 触发 blob GC)。恢复 = 摘除标记回原路径(原名被占则自动改名)。

**权限(FR-AUTH-06)**:根上三类空间 —— 个人私有(`/我的文件/<user>` 仅本人)、组共享(`/共享/<group>` 组可读写)、系统共享(管理员建)。ACL 挂在节点上,默认继承父节点,可覆盖;判定采用**显式拒绝优先、未命中即拒绝**规则(见 6.2)。

### 5.4 事务与一致性
- **写序:NFR-REL-01**:先写 blob(磁盘 fsync),后提交 DB 元数据;DB 事务内完成"版本+节点指针+配额"更新;任何一步失败 → 幂等可重试;
- 上传全程幂等:分块 PUT 可重复(按 `(session, seq)` 去重),complete 时校验清单完整;
- 元数据层用 PostgreSQL 事务保证目录树与 ACL 的一致性;无分布式事务需求。

---

## 6. 关键流程设计

### 6.1 认证与会话 — ADR-05 ✅(服务端会话存 DB + CSRF)
- 浏览器:登录成功 → 服务端建 `http_sessions` 行 → 下发 **HttpOnly + Secure + SameSite=Lax** 会话 Cookie;CSRF 双提交令牌同源注入;
- 优点:随时吊销、按用户列出会话(FR-AUTH-03)、审计容易;服务端存哈希,DB 泄露不泄会话;
- 设备/Agent:管理员/用户签发**个人访问令牌(PAT)**,存哈希,走 `Authorization: Bearer`;可单独吊销;
- 登录保护:IP/账号维度失败计数 + 指数退避(FR-AUTH-04 预留 TOTP 扩展点)。

### 6.2 ACL 判定算法
1. 从目标节点向上收集到根,取"最近的显式 ACL 条目集"(含目标节点自身);
2. 解析主体:USER 直接匹配 + 其所属 GROUP 条目;角色 ADMIN 短路放行;
3. 决策:显式 DENY 优先;无任何命中 → 拒绝(默认拒绝);
4. 目录级操作(列出/进入)要求对沿途每级目录有 R;写操作要求目标父目录 W;
5. ACL 变更 → 失效事件刷新判定缓存;所有判定在**服务端**执行,前端只做展示。

### 6.3 分块上传协议(自研,支持断点续传/秒传)
```
① POST  /api/v1/uploads            {dest, filename, size, chunkSize=4MiB}
        → {uploadId}               服务端:建 upload_session、查配额/权限
② PUT   /api/v1/uploads/{id}/chunks/{seq}   body=原始块 + X-Sha256 头
        服务端:写 tmp → 校验哈希 → 幂等记 received_chunks;可随时断点重发
③ POST  /api/v1/uploads/{id}/complete       服务端:核对清单 → 秒传探测
        (若清单全部块已存在于 blobs → 免落盘直接建版本) → 建 file_versions
        + 更新 nodes → 投递异步事件(媒体扫描/缩略图) → 返回新版本信息
```
- 下载:`GET /nodes/{id}/content` 支持 `Range` 与流式,边读 blob 边发,大文件不占内存;
- 并发覆盖同一文件:以 complete 事务顺序为准,旧版本自动成为"上一版",绝不互相覆盖丢失(版本历史兜底)。

### 6.4 任务队列 — ADR-09(DB 实现,不引入独立 MQ)
- 表 `jobs` 即队列:状态机 `QUEUED→RUNNING→(SUCCESS|FAILED→重试→DEAD)`;按 `type` 分派到对应执行器;
- 调度:应用内 scheduler 周期性把到期 job 置 RUNNING 交给 worker 线程池(虚拟线程);支持优先级、延迟执行(`next_run_at`)、取消;
- 幂等执行:每个 job 处理器实现"重复执行结果一致"(媒体扫描、缩略图、GC 天然幂等);
- 用途:媒体入库扫描、缩略图、转码、blob GC、回收站清理、完整性巡检、备份计划、出站备份;
- 提供管理 API/UI:查看进度、重试、取消(FR-SYS-01)。

### 6.5 媒体入库与时间线
1. 上传完成事件 / 手动"导入目录" → job 扫描目录(按扩展名白名单);
2. 提取 EXIF(拍摄时间、GPS、尺寸)→ 写 `media_assets`,无拍摄时间退回文件 mtime;
3. 缩略图 job(尺寸阶梯:列表 256 / 网格 512)→ 写 `derived/thumbs`,DB 记状态;
4. 时间线查询:按 `taken_at` 分桶分页,DB 索引支持;缩略图 URL 带鉴权票据;
5. 智能相册:地点=GPS 坐标分桶聚合(逆地理可选离线库,默认仅存坐标);日期维度由 `taken_at` 天然支持;人工打标签 → `media_assets.meta`。

### 6.6 转码流水线(ADR-08)
- 判定:浏览器可直放(H.264/AAC/MP4/WebM)则直出(HTTP Range);否则排队转码;
- 执行:ffprobe 探测 → ffmpeg 生成 **HLS 多码率阶梯**(1080p/720p/480p,码率可配)→ 产物存 `derived/transcode/<version_sha>`,元数据登记;
- 播放:`/api/v1/stream/{nodeId}/master.m3u8` 需一次性播放票据(短时效、绑定节点与用户,防止外链盗播);
- 清理:对应版本删除/替换后,产物由 job 清除;引擎层接口 `TranscodeEngine`,当前实现为 ffmpeg 进程调用,后续可加 GPU 变体。

### 6.7 刮削(FR-STREAM-01)
- 接口 `MetadataScraper`(查标题/海报/简介)→ 默认实现 `ManualScraper`(管理员在 UI 手动录入)+ 可选在线源(**默认禁用**,启用需显式配置且文档注明合规边界);
- 扫描 job 按文件名启发式(如 `片名 (2020).mkv`)匹配媒体库条目 → 人工确认后写 `library_items.meta`,海报下载到 `derived`,不污染原始数据目录。

### 6.8 自动上传 / 备份 Agent(M4 细化,先留契约)
- Agent 设备注册 → 服务端签发 PAT(存哈希);
- 上传策略:全量索引本地文件 → 与远端清单比对(`size + mtime + 首尾块哈希`)→ 差异文件走 **6.3 同一分块上传协议**;远程按"用户媒体收件箱"组织(`/我的文件/<user>/自动备份/<device>/<date>`),照片另入媒体库;
- 调度:Agent 端 cron/launchd/任务计划触发;任务状态回传 → `backup_runs` 表,Web 仪表盘展示(FR-BACKUP-07);
- 版本轮换:远端按规则(如保留 N 代/按日期滚动)清理旧版本(FR-BACKUP-04)。

### 6.9 审计(FR-SYS-03)
- 写 `audit_events` 由**独立只追加服务**承接:应用内异步批量写,不阻塞业务;行只允许插入,不允许 UPDATE/DELETE(DB 层权限保证,管理员查询走只读接口);
- 覆盖:登录成败、文件增删改/恢复、权限变更、用户/组管理、任务关键操作。

### 6.10 事件与通知
- `SSE /api/v1/events`:任务进度、上传完成、备份状态、告警实时推送(单向足够,不引入 WebSocket);
- 通知持久化到 `notifications`,站内已读;通知通道抽象(邮件/Webhook 为 M5 预留实现)。

---

## 7. API 契约概要(细节在 M2 落地为 OpenAPI)

统一前缀 `/api/v1`,JSON,错误统一 `{code, message, detail?}`;鉴权:Cookie 会话或 PAT。

| 资源 | 主要端点 | 说明 |
|---|---|---|
| auth | POST /auth/login · /auth/logout · GET /auth/session | 登录/登出/会话信息 |
| iam | /users · /groups · /users/{id}/tokens | 用户组管理、PAT 签发/吊销 |
| nodes | GET /nodes?parent= · POST /nodes(dir) · PATCH /nodes/{id} · DELETE /nodes/{id} | 目录树 CRUD;DELETE 进回收站 |
| files | GET /nodes/{id}/content · GET /nodes/{id}/versions · POST /nodes/{id}/restore | 下载(Range)、版本列表/回滚、恢复 |
| uploads | POST /uploads · PUT /uploads/{id}/chunks/{seq} · POST /uploads/{id}/complete | 分块上传协议(6.3) |
| acl | GET/PUT /nodes/{id}/acl | 权限查看/修改 |
| search | GET /search?q= | 文件名搜索 |
| media | GET /media/timeline?before= · GET /media/assets/{id} | 时间线、单张详情 |
| libraries | /libraries · POST /libraries/{id}/scan · /libraries/{id}/items/{nid} | 媒体库与刮削管理 |
| stream | GET /stream/{nodeId}/master.m3u8 · POST /stream/ticket | 播放票据与 HLS |
| jobs | GET /jobs · POST /jobs/{id}/retry · POST /jobs/{id}/cancel | 任务管理 |
| backup | /backup/profiles · /backup/runs · /devices | Agent 配置与状态 |
| audit | GET /audit?actor=&from=&to= | 管理员检索审计 |
| notify | GET /notifications · GET /events(SSE) | 站内通知与实时事件 |
| admin | GET /health · GET /stats · /settings | 健康、统计、系统设置 |

---

## 8. 前端架构(Vue 3)

- **视图/路由**:登录、文件管理(主工作台)、媒体(时间线/相册)、影音(海报墙/播放器)、回收站、管理后台(用户/组/ACL/任务/审计/设置);
- **文件模块**:虚拟滚动列表 + 目录树;拖拽上传 → 自研 `Uploader`(分块、断点续传、并发 3、失败重试、暂停/恢复/取消);
- **媒体模块**:时间线虚拟滚动、懒加载缩略图;图片查看器(渐进加载/旋转);
- **影音模块**:hls.js 播放器(移动端优先 HLS;桌面直连 MP4 用原生);
- **状态**:Pinia 管理用户/节点上下文/任务进度;SSE 客户端维护连接与重连;
- **权限感知 UI**:按当前用户对节点权限渲染操作按钮(真正的校验永远在服务端);
- 开发态:Vite dev server 代理 `/api` 与 `/events` 到后端;生产态:nginx 托管 `dist` 并反代(ADR-07)。

---

## 9. 安全设计要点

- TLS:生产默认 HTTPS。nginx 终止 TLS(自签证书初始化生成,可替换为用户证书);提供反向代理接入模式;配置项可显式关闭(仅限纯内网开发);
- 口令 argon2id(Spring Security 内置),PAT/会话仅存哈希;
- CSRF:同源 SPA + 双提交令牌;Cookie 设 HttpOnly/Secure/SameSite=Lax;上传文件名消毒、拒绝路径穿越、类型白名单 + 内容嗅探提示;
- 服务端处处鉴权(ACL 判定引擎唯一入口),杜绝仅前端隐藏;
- 审计只追加;密钥全部走环境变量/secret 文件,不入代码库;
- 外网开启的安全基线(FR-NET-04):登录限速、失败锁定、可关停注册、安全响应头 —— M5 交付,文档先行。

---

## 10. 部署与网络

### 10.1 推荐拓扑(Docker Compose,ADR-07 ✅ nginx 托管)
```
services:
  db:   postgres:16-alpine          # 卷:pgdata(元数据)
  app:  pnas-server 镜像              # 卷:数据目录(blobs/derived/tmp);内置 ffmpeg;不直接暴露公网
  web:  nginx:alpine                 # 托管前端 dist + 反代 /api、/events → app;对外唯一入口
```
- `web` 对外暴露端口(443/80);`app` 仅暴露在 compose 内部网络,`db` 亦然;
- SSE 反代需关闭 proxy_buffering(nginx 配置已含);
- 数据目录与容器生命周期解耦:升级/重装容器不动数据卷;
- 开发快捷:也可 `docker compose up db` 后本机跑后端 + Vite dev server。

### 10.2 网络访问(FR-NET)
- 默认仅内网监听;提供三档文档化方案:① 内网直连(默认);② 叠加网 Tailscale/WireGuard(推荐的外网访问方式);③ 公网 + 反向代理(进阶,M5 交付安全基线);
- 备份:数据目录 + PostgreSQL 逻辑导出 → 外接盘/第二台机器脚本(FR-BACKUP-05,M4)。

---

## 11. 仓库结构

```
PNAS/
├── backend/
│   ├── pnas-common/                  # 协议 DTO、常量、错误码
│   └── pnas-server/                  # 单体服务(M2 起实现)
│       └── src/main/java/com/pnas/server/{common,web,auth,iam,files,
│                                         blobstore,media,stream,backup,
│                                         job,audit,notify}
│       └── src/test/...              # JUnit5 + Testcontainers + ArchUnit
├── frontend/                         # Vue 3 + TS(Vite),构建产物由 nginx 托管
│   └── src/{api,components,views,stores,router,assets}
├── agent/                            # M4:Java CLI + jpackage
├── deploy/                           # docker-compose.yml、Dockerfile、nginx 配置、env 示例
├── docs/                             # 本文档 + 手册(架构/部署/恢复/开发)
└── scripts/                          # 开发/运维辅助脚本
```

开发约定:Maven wrapper;Java 21;代码注释与提交信息中文;测试门禁(单测 + 集成 + ArchUnit)。

---

## 12. M2(MVP)任务拆分建议(WBS)

1. 仓库骨架:backend(pnas-common/pnas-server)+ frontend + compose(db/postgres + web/nginx + app);
2. 数据模型与 Flyway 迁移(V1 全量建表 + 种子角色/根目录);
3. auth 模块:登录/登出/会话/CSRF/管理员建用户;
4. iam 模块:ACL 模型 + 判定引擎 + 单测覆盖矩阵;
5. blobstore:分块读写、校验、CAS 落盘(先直写单 blob 目录,4MiB 分块与版本随后);
6. files:目录 CRUD、上传三端点(6.3)、下载 Range、回收站、版本 v1、配额;
7. web 基础 UI:登录、文件浏览器、上传(含断点续传)、删除/恢复;
8. job 模块:队列与调度(v1 先服务媒体扫描/缩略图);
9. 集成测试 + ArchUnit;部署 compose 验收(2 用户局域网日常使用)。

> 出口标准(对齐 M0):2 个账号可互不可见地日常使用 Web 端;上传大文件可断点续传;误删可恢复。

---

## 13. 架构决策记录(ADR,全部已确认 ✅)

| # | 决策点 | 结论(已确认) |
|---|---|---|
| ADR-01 | 进程形态 | ✅ 模块化单体,单进程(worker 可拆 profile);边界靠 ArchUnit 固化 |
| ADR-02 | 语言/框架/构建 | ✅ Java 21 + Spring Boot 3.3 + Maven reactor |
| ADR-03 | 元数据库 | ✅ PostgreSQL 16(容器,compose 一键起) |
| ADR-04 | ORM/迁移 | ✅ Spring Data JPA(Hibernate)+ Flyway |
| ADR-05 | 会话机制 | ✅ 服务端会话存 DB + CSRF;设备/Agent 用 PAT |
| ADR-06 | 存储语义 | ✅ 4MiB 分块 + sha256 内容寻址 + 引用计数 GC |
| ADR-07 | 前端托管 | ✅ **独立 nginx 容器**(托管静态 + 反代 /api、/events) |
| ADR-08 | 转码 | ✅ ffmpeg 外部二进制,引擎接口可插拔 |
| ADR-09 | 任务队列 | ✅ DB 表实现,v1 不引入 Redis/MQ |
| ADR-10 | API 风格 | ✅ REST /api/v1 + SSE;OpenAPI 契约 |
| ADR-11 | Agent | ✅ Java CLI + jpackage,复用上传协议 + PAT(M4) |
| ADR-12 | 测试策略 | ✅ 单测 + Testcontainers 集成 + ArchUnit 边界 |

### M1 出口标准 ✅
- [x] 本文档评审通过,ADR-01~12 逐条确认;
- [x] 仓库骨架规划与 M2 WBS 认可;
- [x] 关键未决项清零。

---

## 14. 风险(架构层面)

| 风险 | 影响 | 对策 |
|---|---|---|
| 自研 blobstore 与 GC 出错 | 数据丢失 | 先写后元、保护期双保险、巡检 job、恢复演练(M4) |
| 版本/覆盖并发语义复杂 | 用户困惑或丢版本 | 事务串行化 complete;版本列表 UI 明确;冲突策略留 M7 |
| PostgreSQL/nginx 增加运维面 | 上手成本 | 容器化一键起;文档提供备份恢复脚本 |
| 转码产物膨胀 | 磁盘占用 | derived 可再生 + 清理 job + 产物上限配置 |
| 前端工程量(时间线/上传)大 | 里程碑拖期 | 上传 SDK 与媒体视图先行;视觉逐步打磨 |
