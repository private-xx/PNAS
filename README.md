# PNAS — Personal NAS(纯从零自研)

面向家庭小团队(2–8 人)的私有化网络存储系统:文件存储与多端访问/自动上传、照片视频媒体管理、影音流媒体、集中备份。**内网为主,可选安全外网访问。**

> 定位:自研学习/研究型项目 —— 存储语义、上传协议、权限模型均为自研;技术栈 **Java 21 + Spring Boot + Vue 3 + PostgreSQL + nginx**。

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 需求分析 | ✅ [requirements-analysis.md](docs/requirements-analysis.md) v0.3 |
| M1 | 架构设计 | ✅ [architecture-design.md](docs/architecture-design.md) v0.3(ADR-01~12) |
| M2 | MVP:账号/ACL/文件管理/分块上传下载/回收站 + Vue Web | ✅ **已合并**(PR #1);[交付与决策](docs/m2-delivery.md) |
| M3 | 媒体:入库/缩略图/时间线/日期相册/直放视频/任务中心 | ✅ **已合并**(PR #2);[交付与决策](docs/m3-delivery.md) |
| M4 | 自动上传与备份 Agent(macOS/Windows)+ 恢复演练 | 🔜 下一阶段 |
| M5 | 影音(刮削/转码)+ 安全加固/外网访问 | 待启动 |
| M6 | 试运行与文档 | 待启动 |

**已实现能力**:服务端会话 + CSRF、RBAC(仅管理员建用户)与目录级 ACL(默认拒绝/显式 DENY 全链优先)、4 MiB 分块断点续传与 sha256 内容寻址去重、版本化写入(绝不静默覆盖)、回收站恢复、EXIF/MP4 元数据入库、异步缩略图(256/512)、时间线(本地日期分组 + 月桶)、日期相册、图片查看器、直放视频(Range)、任务中心(重试/取消/重建媒体索引)。

## 质量门禁(最近一次实测)

- 后端全套件 **126/126 PASS**(Testcontainers PostgreSQL 16,含 M2 的 47 项)
- 前端 `npm run build`(vue-tsc 类型检查 + vite 打包)**PASS**
- 端到端验收 **PASS=42 / FAIL=0**(`scripts/acceptance-m3.sh`,幂等;隔离/上传/缩略图/时间线/相册/回收站/视频 Range/重建索引)
- 时间线性能(SC-3,1000 条):首屏中位 **0.0287s**(M3 验收)与 **0.0217s**(修复后复测),预算 ≤2s
- 已知限制与偶发:见 [docs/progress.md](docs/progress.md) 与 [m3-delivery.md](docs/m3-delivery.md) 的边界章节

## 快速开始(本地开发)

```bash
# 1) 依赖:JDK 21、Maven 3.8+、Node.js 20+、Docker(PostgreSQL 16)
#    注意本机默认 JDK 8 会导致 Maven 失败,请显式设置 JAVA_HOME
export JAVA_HOME=/path/to/jdk-21

# 2) 起数据库(仓库自带 compose 的 db 服务,或任意 PostgreSQL 16)
docker compose -f deploy/docker-compose.yml up -d db

# 3) 后端(默认 :8080;首启会按环境变量创建管理员)
cd backend
mvn -s ../.tools/maven-settings.xml -pl pnas-server -am spring-boot:run
#   或先打包: mvn -pl pnas-server -am package -DskipTests && java -jar pnas-server/target/pnas-server-0.1.0-SNAPSHOT.jar

# 4) 前端(开发态 :5173,代理 /api → :8080)
cd frontend && npm install && npm run dev

# 5) 验收(需服务与数据库可用;M3 需开启任务调度器)
PNAS_JOBS_SCHEDULER_ENABLED=true ...
bash scripts/acceptance-m3.sh http://localhost:8080
bash scripts/perf-m3-timeline.sh  http://localhost:8080   # 可选:1000 条时间线首屏
```

常用环境变量:`PNAS_DB_URL` / `PNAS_DB_USER` / `PNAS_DB_PASSWORD`、`PNAS_DATA_DIR`(blobs/derived/tmp 根目录)、`PNAS_REQUIRE_TLS`、`PNAS_JOBS_SCHEDULER_ENABLED`、`PNAS_BOOTSTRAP_ADMIN_USERNAME` / `PNAS_BOOTSTRAP_ADMIN_PASSWORD`。

> Maven 直连私有镜像不可达时,使用仓库内的 `.tools/maven-settings.xml`(阿里云公共镜像 + 本地仓库,见 `docs/progress.md`)。

## 文档

| 文档 | 说明 |
|---|---|
| [需求分析](docs/requirements-analysis.md) | v0.3 已确认(M0 + 严谨化评审,2026) |
| [系统架构设计](docs/architecture-design.md) | v0.3 已确认,ADR-01~12 全部通过,与需求 v0.3 同步 |
| [进度记录](docs/progress.md) | 各里程碑结果、验收证据、环境与已知限制 |
| [M2 交付与决策](docs/m2-delivery.md) | M2 证据、裁决与遗留项 |
| [M3 交付与决策](docs/m3-delivery.md) | M3 证据、R1–R32 裁决、已接受边界与 M4/M5 衔接 |
| [M3 设计 spec](docs/superpowers/specs/2026-09-28-m3-media-design.md) / [实施计划](docs/superpowers/plans/2026-09-28-m3-media.md) | M3 的设计与任务拆分 |
| [M2 实施计划](docs/superpowers/plans/2026-09-07-m2-mvp.md) | M2 的 14 任务计划 |

## 仓库结构

```
backend/    Maven 多模块:pnas-common / pnas-server(单体服务;media 模块含入库/缩略图/时间线)
frontend/   Vue 3 + TypeScript(Vite),Element Plus;生产由 nginx 托管
deploy/     docker-compose、Dockerfile、nginx 配置、env 示例
docs/       需求/架构/进度/交付记录,以及 superpowers 下的 spec 与实施计划
scripts/    acceptance-m2.sh / acceptance-m3.sh / perf-m3-timeline.sh 等验收与运维脚本
agent/      (M4)桌面备份客户端
```

## 开发前置要求

- **JDK 21**(本机默认 JDK 8,请通过 sdkman/brew/官方包安装并设置 `JAVA_HOME`);
- Maven 3.8+(随 Maven wrapper 亦可);
- Node.js 20+ 与 npm;
- PostgreSQL 16(推荐 `docker compose up -d db` 起容器)或本机安装;
- Docker 20.10+ / Compose v2(用于一键部署)。

> 注意:后端编译需要 JDK 21;使用 JDK 8 执行 Maven 会失败(`release 21` 不被支持)。
