# PNAS 进度记录

> 记录每个里程碑的关键结果与断点信息,便于随时恢复。最后更新:2026-11-03(M4a 桌面备份 Agent 验收、打包与文档)。

## 总体状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 需求分析 | ✅ [requirements-analysis.md](requirements-analysis.md) v0.3 已确认(M0 + 严谨化评审 M0-R2,2026) |
| M1 | 架构设计 | ✅ [architecture-design.md](architecture-design.md) v0.3,ADR-01~12 全部确认,与需求 v0.3 同步 |
| M2 | MVP 实现 | ✅ **已合并进 `main`**(PR #1,合并提交 `dc5afe5`):14 任务全部实现并逐任务评审 + 三段最终评审;后端全套件 **47/47**、前端构建通过、端到端验收 **PASS=23/FAIL=0**;交付与决策记录见 [m2-delivery.md](m2-delivery.md) |
| M3 | 媒体(时间线 / 日期相册 / 缩略图 / 视频播放) | ✅ **已合并进 `main`**(PR #2,合并提交 `3b12e96`):13 个任务实现完成;后端全套件 **126/126**、前端构建 PASS;验收 **PASS=42 / FAIL=0**;SC-3 时间线首屏 1000 条实测中位数 **0.0287s / 修复后复测 0.0217s**(预算 ≤2s);交付与决策见 [m3-delivery.md](m3-delivery.md) |
| M4 | 备份(桌面 Agent) | 🔄 **M4a 完成**(分支 `m4a-backup`,待合并):后端全套件 **156/156**、Agent **52/52**、E2E 验收 **PASS=34/FAIL=0**;macOS app-image 打包通过(dmg 受本机沙箱限制,见下);Windows 打包文档未实测;其余备份范围待启动 |
| M5–M6 | 流媒体加固 / 验收 | 待启动 |
| M7 | 后续立项(镜像同步、移动通道评估等 Could 项) | 待启动 |

## M4a 完成情况(2026-11-03)

- 范围:备份域后端(设备/令牌/运行记录/清单/上传携带 sourceMtime)、桌面 Agent(配置/扫描/增量/HTTP 客户端/备份/恢复/校验/实时监视/定时)、Web 备份页;以及 macOS/Windows 打包、E2E 验收脚本与本文档。
- 执行计划与账本:`.superpowers/sdd/2026-10-24-m4a-backup-agent/`(T1–T14;本文件即 T14 文档产出)。
- **提交链**(`m4a-backup`,基于 `main`):`dc56c4b`(计划/插件锁定)→ `4d00e3e`/`17e9713`/`990b135`/`999be18`(设备、Bearer 令牌、运行记录)→ `c3ac80b`(清单)→ `4b277d7`…`3dad9bc`(Agent 骨架、扫描/增量、HTTP、运行器、监视/定时、恢复/校验)→ `cf1f9b9`(前端备份页)→ `191c5db`(恢复路径安全);共 24 个实现/修复提交,任务 14 提交紧随其后。
- **质量门禁**:
  - 后端全套件(HEAD `191c5db`,`mvn -o -s ../.tools/maven-settings.xml -pl pnas-server -am test`):**`Tests run: 156, Failures: 0, Errors: 0, Skipped: 0`**,BUILD SUCCESS(M3 为 126;M4a 新增 30 项)。
  - Agent 模块(`cd agent && mvn -o -s ../.tools/maven-settings.xml test`):**`Tests run: 52, Failures: 0, Errors: 0, Skipped: 0`**(7 个测试类)。
- **E2E 验收**:`scripts/acceptance-m4.sh`(幂等;`BASE` 参数,默认 `http://localhost:8080`;无 jq 依赖,python3 解析 JSON)。隔离环境:PostgreSQL 容器 `pnas-acc-db`(库 `pnas`,已含 V1–V4)、server `:8092`、`PNAS_DATA_DIR=/tmp/pnas-m4a-e2e`、`PNAS_REQUIRE_TLS=false`;同一构建连续两次 **PASS=34 / FAIL=0**。覆盖:
  - admin 登录 HTTP 200 + CSRF 头;创建设备 HTTP 200 并返回设备 ID/令牌;
  - 4 个源文件(含 5,000,000 字节大文件)首次备份退出码 0、`uploaded=4`;服务端节点树 `/自动备份/<deviceId>/Files/{a.txt,b.txt,big.bin,sub/c.txt}` 逐一经 API 断言,`big.bin` 大小 = 5,000,000;
  - 修改 `a.txt` 后二次备份退出码 0、`uploaded=1 skipped=3`、`a.txt` 最新版本 = 2(验证文件级增量);
  - `verify --all` 退出码 0、`checked=4 passed=4 failed=0`;`restore --path Files/a.txt` 退出码 0 且 `cmp` 与源文件一致;
  - 吊销设备 HTTP 204;随后备份返回 HTTP 401、agent 退出码 2。
  - 环境备注:本机 `:8081` 被 2026-09-11 启动的旧 `pnas_fresh` 服务占用、`:8082` 有 node 进程监听 `[::1]`,故验收服务起在 `:8092`;未触碰 `:8080` 的既有服务。
- **打包**:
  - macOS:`agent/packaging/package-macos.sh`(jpackage + 手工 bundle,Jackson 三 jar 放入 `bundle/lib` 并写入 jar 清单 `Class-Path`)。本机 `jpackage --type dmg` 在 hdiutil 阶段失败,精确报错为 **`hdiutil: create failed - 操作不被允许`**(受限沙箱下 hdiutil 的 disk image 设备操作被拒绝,直接调用 `hdiutil create` 同样复现,非脚本逻辑错误);改用 `--type app-image` 成功生成 `agent/target/package/PNAS Agent.app`,并用该 app 对 `:8092` 实跑 `backup --once` → `seen=1 uploaded=1 failed=0`、退出码 0,证明 Jackson 依赖与入口随包可用。`.dmg` 需在非沙箱 macOS 终端复跑脚本确认。
  - Windows:`agent/packaging/windows.md`(构建命令 + jpackage + Task Scheduler 注册 + 限制),**未实测**;与 macOS 相同,`--app-version` 固定为 `1.0.0`(jpackage 不允许首段为 0,项目版本 `0.1.0-SNAPSHOT` 不能直接使用)。
- **已知限制与延后项(M4a 接受)**:
  - Windows Agent 的打包、安装与任务计划未实测(当前仅文档);
  - 增量是**文件级**(大小/mtime/首末块哈希),块级 delta 延后;大文件小改动会整文件重传;
  - `audit_events` 表尚未创建,备份操作暂无独立审计事件(留待后续里程碑;当前库表清单中无该表);
  - 清单构造为游标分页 + 每条版本查询首末块,存在 **N+1**;大库/大目录下 SQL 次数线性增长,规模化前需批量化;
  - 设备令牌仅以哈希落库(创建时一次性返回),丢失需重建;吊销即时生效(验收已覆盖)。

## M3 完成情况(2026-10-22)

- 范围:媒体元数据入库(EXIF/MP4 时长)、缩略图(256/512)、时间线(游标分页 + 可见性 CTE)、日期桶/日期相册、单条读取 ACL、视频 Range 复用、回收站排除与重建索引;前端时间线/相册/查看器/播放/任务中心。
- 执行计划与账本:`.superpowers/sdd/2026-09-28-m3-media/`(T1–T13;本文件即 T13 的文档产出)。
- **质量门禁**:后端全套件 **126/126 PASS**(含 M2 的 47;M3 新增 79 项;最终集成态在 HEAD `412e77d` 上实测,修复波评审基线 124/124);前端 `npm run build`(vue-tsc 类型检查 + vite 打包)**PASS**。
- **验收**:修复轮在隔离环境(全新数据库 `pnas_m3_fixwave` + `--server.port=8091`)连续两次运行 `bash scripts/acceptance-m3.sh http://localhost:8091`,均 **PASS=42 / FAIL=0**;此前的 8090 环境为 PASS=31 / FAIL=0(本轮脚本新增 11 项断言,见下)。脚本幂等;时间线与跨用户隔离断言只针对本轮上传的 4 个节点,年/月桶与日期相册按当年全局口径断言。覆盖:
  - 健康检查;admin 登录 + 随机成员创建/登录(CSRF 取自登录响应头);
  - 3 张内容互不相同的真实 JPEG(JDK ImageIO 生成,不同渐变参数,避免 `IIOException: Huffman table`)+ 1 个 MP4 分块上传;
  - `media.scan`/`media.thumb` 队列 60s 内排空且本轮无 FAILED/DEAD;
  - 时间线含本次 4 项;`/media/assets/{id}/thumb?size=256` → 200 + `image/jpeg`;当年月桶合计 ≥4 **且本月桶 ≥4**(均为当年全局口径);`/media/albums/dates` 非空(全局口径)。时间线读取先断言 HTTP 200,再用 python3 解析 JSON(不对可能为错误体的响应做 `grep -c`);
  - 跨用户隔离:成员读管理员缩略图 404、成员时间线 HTTP 200 且解析后不含该资产;
  - 视频 `/media/assets/{id}/content` + `Range: bytes=0-15` → 206 且 `Content-Range: bytes 0-15/`;
  - 删除 → 时间线消失(HTTP 200 + JSON 计数 0)→ 恢复 → 重新出现(HTTP 200 + JSON 计数 1);`POST /media/reindex` → 202,等待完成后条目数不变,且资产详情 `GET /media/assets/{id}` 仍为 200、时间线仍含该节点(非恒真信号)。
- **性能(SC-3,1000 张首屏 ≤2s)**:`bash scripts/perf-m3-timeline.sh <BASE>`(首次 T13 于 `:8090`;修复波在隔离库/`:8091` 复测)。口径:psql 直插 1000 条 `media_assets`(绕开上传),`taken_at` 从 `now()` 起按 8h 间隔向过去铺开(1000 条约 11 个月,脚本按实际 min/max 计算跨度)、`thumb_status='READY'`、`indexed_at=now()`,全部复用同一已入库版本的 `manifest_sha256/size_bytes`(blob 与缩略图各一份),只测查询 + 首屏 JSON;端点 `GET /api/v1/media/timeline?limit=60`,3 次预热 + 5 次计时,每次断言 HTTP 200 后取 `curl time_total` 中位数。
  - 实测(T13):`[0.029968, 0.029064, 0.027618, 0.028661, 0.027169]s`(N=1000,返回 60 条),**中位数 0.0287s**;**修复波复测**:同样 N=1000,5 次计时**中位数 0.0217s**(`PNAS_PERF_KEEP=1` 复跑 0.0172s、清理后 0.0179s),均判定 ✅ ≤2s(本机单实例、PostgreSQL 16 容器、库与数据盘同机;正式部署需按其硬件复测)。
- **环境复现**:Postgres 容器 `pnas-acc-db`(host 55432,`acc/acc`,`pnas_m3`);`PNAS_JOBS_SCHEDULER_ENABLED=true`(否则媒体任务不会被领取);服务 `--server.port=8090`;Java 21 与 Maven 见下方工具链。
- **已知限制与延后项(M3 接受,M5 收敛)**:
  - HEIC/HEIF/WebP/AVIF 可入库但 `thumb_status='SKIPPED'`(纯 Java 无法解码),缩略图待 M5;
  - 视频 `thumb_status='SKIPPED'`、无封面/海报帧,播放直接走 Range(`thumb?size=` 对视频返回 404 属预期);
  - 时间线可见性按"可读根 + 子树"近似:祖先显式 DENY 时可能"列表可见、单条打开 404"(见 `MediaTimelineService#readableRoots` 注释);
  - 月桶/日期相册按 PostgreSQL 会话时区分组(与 EXIF 无时区字段按本地墙上时间解释一致),跨时区部署会整体偏移;
  - 重建索引按"每节点一个 media.scan"排队,调度器 5s/20 条,超大库重建耗时线性增长(性能脚本默认在测完后清理直插数据,以便验收脚本可重复运行);
  - **日期相册的月份只做静态展示,无月份筛选/跳转**(年/月跳转明确延后至 M5,`api/media.ts#listBuckets` 仅保留为 `reserved: 年/月跳转(M5)` 的接口);
  - **验收脚本的桶断言覆盖单月分布**:本轮 4 项落在同一个月,断言"合计 ≥4 且本月桶 ≥4";跨月拆分由服务层单测覆盖;
  - **验收脚本的 "MP4" 为 2 KiB 占位字节**,只验证上传/Range/可达链路,真实 MP4 时长解析由单元/集成测试覆盖;
  - **SC-3 性能为单机单次绝对值**(3 次预热 + 5 次中位数,非并发/多实例基准),正式部署需按目标硬件复测。
  - **已知测试偶发**:MockMvc 异步流式响应与 Spring Security 头写入存在竞态,极少数运行会出现 `ConcurrentModificationException`/空体(集中在含 `StreamingResponseBody` 的用例);最终修复后连续两次全量 **BUILD SUCCESS**,若 CI 偶发重现,重跑即可(根治留作测试基础设施加固项)。

## M2 完成情况(2026-09-11)

- 执行计划:`docs/superpowers/plans/2026-09-07-m2-mvp.md`(14 任务,TDD 化;subagent 驱动执行)
- SDD 账本(含全部裁决 R1–R18 与 deferred minors):`.superpowers/sdd/2026-09-07-m2-mvp/progress.md`(gitignore)
- 提交链(m2-mvp):`7962214 → 6765c94(T1) → 2a94dfd(T2) → 05448b8+7df53af(T3) → 6340811(T5) → cf48ad0+c1022cd+16556f9(T6) → a9723e1+1f5ef02(T4) → 1a23449(T7) → 1a72da5(T8) → 7bb4469(T9) → ac86672(前端 T10–12) → 4ed6f17(修复轮) → e02b0cb(验收脚本幂等化) → b3f9609(preview 同源网关)`
- 验收证据:`scripts/acceptance-m2.sh` → **PASS=21 / FAIL=0**(两用户隔离、4 MiB 分块上传与哈希一致、断点续传、Range 206/416、删除→回收站→恢复);同源网关路径(SPA 托管 + `/api` 反代 + CSRF)已验证;生产 nginx 配置见 `deploy/nginx/default.conf`(运行时验证因镜像源不可用而改用等价方案,见账本)
- 已推送到 GitHub:`main`(文档+骨架基线)与 `m2-mvp`(全部实现提交)
- 环境:JAVA_HOME=`.tools/jdk-21.0.12.1+1/Contents/Home`;Maven 需 `-s ../.tools/maven-settings.xml`;Docker 已运行(postgres/ryuk 镜像本地)

## 已完成(截至本次断点)

1. **M0 需求分析**——用户场景(家庭小团队 2–8 人、纯软件不绑硬件)、范围、MoSCoW 优先级、里程碑、决策记录 D1~D9 落档(v0.1/v0.2)。
2. **M0-R2 需求严谨化评审(2026)**——基于 superpowers 方法论重审需求,产出 v0.3:
   - 新增决策 **D10**(手机照片经 Web 批量上传,桌面为自动通道)、**D11**(at-rest 加密由部署层磁盘/卷加密承担)、**D12**(默认仅管理员建用户,注册开关默认关);
   - 修复 B1–B10 一致性残留(标题"多端同步"、幽灵"访客角色"、M1 状态过时、Q/D 双表冗余等);
   - 成功标准改为**可测口径表 SC-1~6**(含验证方式与 FR/NFR 追踪);绝对吞吐目标改相对基线;
   - 决策记录合并为单一真源(§8)。
3. **M1 架构设计**——模块化单体 + PostgreSQL16 + nginx 托管前端;4MiB 分块 sha256 内容寻址 blobstore;服务端会话 + CSRF;DB 任务队列;API 契约概要;M2 WBS。v0.3 已同步需求 v0.3(上游引用 + D10–D12/RPO 对齐注记)。
4. **M2 仓库骨架**——已初始化 git,提交历史含 `8b0858c`(骨架)、`ccb13e3`(进度断点)、`e82f98c`(需求 v0.3)、`c0f2d8c`(日期 2026):
   - `backend/` Maven reactor(`pnas-backend` → `pnas-common` + `pnas-server`),Spring Boot 3.3.5 / Java 21;
   - 服务入口 `PnasServerApplication` + `application.yml`(环境变量化配置);
   - `frontend/` Vue 3 + TS + Vite(Element Plus/Pinia/Router/axios 已入依赖);
   - `deploy/` docker-compose(db + app + web/nginx)、两个 Dockerfile、nginx 反代配置、`.env.example`;
   - 顶层 `README.md`、`.gitignore`、`docs/`(需求 v0.3 / 架构 v0.3 / 进度)。
5. **前端构建已验证通过**(`npm run build`:vue-tsc 类型检查 + vite 打包 OK)。
6. **后端编译验证** —— 未完成:本机默认 JDK 8,需 JDK 21(见下"工具链")。

## 工具链断点(重要)

- **便携 JDK 21**:`.tools/temurin21.tar.gz` 仅下载 13MB/190MB 即被取消(2026-09 会话)。续传/重试命令(在仓库根):
  ```bash
  mkdir -p .tools
  curl -fL -C - -o .tools/temurin21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
  tar -xzf .tools/temurin21.tar.gz -C .tools
  .tools/jdk-21*/bin/java -version   # 验证
  ```
  - 构建后端时设置:`export JAVA_HOME=/Users/xiao/hui/project/PNAS/.tools/jdk-21.0.12.1+1`(以实际目录为准)。
  - 提示:Adoptium 官方源较慢(~65KB/s),可考虑镜像或换时段。
- **Homebrew 不可用**:`/opt/homebrew` 目录属 root(曾被以 root 运行),普通用户无法安装;修复需 `sudo chown -R xiao /opt/homebrew ...`(需人工授权)。
- **npm 系统缓存损坏**(root 属主):已用项目内缓存绕过:
  ```bash
  cd frontend && npm install --cache .npm-cache --no-audit --no-fund
  ```
- **Docker daemon 状态异常**(`docker info` 报错):Docker Desktop 未运行或 CLI 与 daemon 不匹配;需要时先人工确认 `docker ps`。
- 本机为 Apple Silicon(macOS 15.7.5),JDK 8(x86_64)仅为系统默认。
- **Superpowers 技能集已装**(用户级 `~/.dsh/skills`,2026):brainstorming / writing-plans / executing-plans / TDD / code-review / 调试等 10 项;需求严谨化评审(M0-R2)即由其方法驱动。

## M2 剩余工作(下次从这里继续)

1. 完成 JDK21 就绪后:`cd backend && mvn -DskipTests package` 验证骨架编译(首次需下载依赖,较慢);
2. Flyway `V1__init.sql`:建表(users/groups/nodes/file_versions/version_chunks/acl_entries/upload_sessions/jobs/audit_events/http_sessions 等)+ 种子(管理员、根目录);
3. `auth` 模块:登录/登出/会话(DB)/CSRF/**默认仅管理员建用户**(需求 D12);
4. `iam` 模块:ACL 模型 + 判定引擎 + 单测矩阵;
5. `blobstore`:内容寻址读写/校验/引用计数,先打通"分块 → tmp 校验 → blobs"链路;
6. `files` + `uploads` API:目录 CRUD、三端点分块上传、Range 下载、回收站、版本化写入(FR-FS-04);
7. `job` 模块:DB 任务队列 + 调度(先服务缩略图/扫描);
8. 前端基础 UI:登录页、文件浏览器、断点续传上传、删除/恢复;
9. 集成测试(Testcontainers + PostgreSQL)+ ArchUnit;docker compose 联调验收(出口标准:2 账号互不可见日常可用、大文件可断点续传、误删可恢复)。

## 参考命令速查

```bash
# 前端开发
cd frontend && npm run dev        # Vite(5173,代理 /api → :8080)

# 后端(需 JAVA_HOME 指向 JDK21)
cd backend && mvn spring-boot:run # 需本地 PostgreSQL 或 docker compose up -d db

# 一键部署
cd deploy && cp .env.example .env && docker compose up -d --build
```
