# PNAS 进度记录

> 记录每个里程碑的关键结果与断点信息,便于随时恢复。最后更新:2026-09-11(M2 MVP 实现完成,待最终评审)。

## 总体状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 需求分析 | ✅ [requirements-analysis.md](requirements-analysis.md) v0.3 已确认(M0 + 严谨化评审 M0-R2,2026) |
| M1 | 架构设计 | ✅ [architecture-design.md](architecture-design.md) v0.3,ADR-01~12 全部确认,与需求 v0.3 同步 |
| M2 | MVP 实现 | ✅ 分支 `m2-mvp`:14 任务全部实现并逐任务评审;后端全套件 **36/36**、前端构建通过、端到端验收 **PASS=21/FAIL=0**(待最终 whole-branch review) |
| M3–M6 | 媒体 / 备份 / 流媒体加固 / 验收 | 待启动 |
| M7 | 后续立项(镜像同步、移动通道评估等 Could 项) | 待启动 |

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
