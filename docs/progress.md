# PNAS 进度记录

> 记录每个里程碑的关键结果与断点信息,便于随时恢复。最后更新:2025(M2 骨架阶段)。

## 总体状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 需求分析 | ✅ [requirements-analysis.md](requirements-analysis.md) v0.2 已确认 |
| M1 | 架构设计 | ✅ [architecture-design.md](architecture-design.md) v0.2,ADR-01~12 全部确认 |
| M2 | MVP 实现 | 🔨 仓库骨架完成,核心模块待实现(见下) |
| M3–M6 | 媒体 / 备份 / 流媒体加固 / 验收 | 待启动 |

## 已完成(截至本次断点)

1. **M0 需求分析**——用户场景(家庭小团队 2–8 人、纯软件不绑硬件)、范围、MoSCoW 优先级、里程碑、决策记录 Q1~Q8/D1~D9 全部落档。
2. **M1 架构设计**——模块化单体 + PostgreSQL16 + nginx 托管前端;4MiB 分块 sha256 内容寻址 blobstore;服务端会话 + CSRF;DB 任务队列;API 契约概要;M2 WBS。
3. **M2 仓库骨架**——已初始化 git(初始提交 `8b0858c`),文件统计见下:
   - `backend/` Maven reactor(`pnas-backend` → `pnas-common` + `pnas-server`),Spring Boot 3.3.5 / Java 21;
   - 服务入口 `PnasServerApplication` + `application.yml`(环境变量化配置);
   - `frontend/` Vue 3 + TS + Vite(Element Plus/Pinia/Router/axios 已入依赖);
   - `deploy/` docker-compose(db + app + web/nginx)、两个 Dockerfile、nginx 反代配置、`.env.example`;
   - 顶层 `README.md`、`.gitignore`。
4. **前端构建已验证通过**(`npm run build`:vue-tsc 类型检查 + vite 打包 OK)。
5. **后端编译验证** —— 未完成:本机默认 JDK 8,需 JDK 21(见下"工具链")。

## 工具链断点(重要)

- **便携 JDK 21 下载中**:`.tools/temurin21.tar.gz`(Temurin 21.0.12.1, macOS aarch64),后台任务可能已中止。
  - 续传/重试命令(在仓库根):
    ```bash
    mkdir -p .tools
    curl -fL -C - -o .tools/temurin21.tar.gz \
      "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
    tar -xzf .tools/temurin21.tar.gz -C .tools
    .tools/jdk-21*/bin/java -version   # 验证
    ```
  - 构建后端时设置:`export JAVA_HOME=/Users/xiao/hui/project/PNAS/.tools/jdk-21.0.12.1+1`(以实际目录为准)。
- **Homebrew 不可用**:`/opt/homebrew` 目录属 root(曾被以 root 运行),普通用户无法安装;修复需 `sudo chown -R xiao /opt/homebrew ...`(需人工授权)。
- **npm 系统缓存损坏**(root 属主):已用项目内缓存绕过:
  ```bash
  cd frontend && npm install --cache .npm-cache --no-audit --no-fund
  ```
- **Docker daemon 状态异常**(`docker info` 报错):Docker Desktop 未运行或 CLI 版本与 daemon 不匹配;需要时先人工确认 `docker ps`。
- 本机为 Apple Silicon(macOS 15.7.5),JDK 8(x86_64)仅为系统默认。

## M2 剩余工作(下次从这里继续)

1. 完成 JDK21 就绪后:`cd backend && mvn -DskipTests package` 验证骨架编译(首次需下载依赖,较慢);
2. Flyway `V1__init.sql`:建表(users/groups/nodes/file_versions/version_chunks/acl_entries/upload_sessions/jobs/audit_events/http_sessions 等)+ 种子(管理员、根目录);
3. `auth` 模块:登录/登出/会话(DB)/CSRF/管理员建用户;
4. `iam` 模块:ACL 模型 + 判定引擎 + 单测矩阵;
5. `blobstore`:内容寻址读写/校验/引用计数,先打通"分块 → tmp 校验 → blobs"链路;
6. `files` + `uploads` API:目录 CRUD、三端点分块上传、Range 下载、回收站、版本 v1;
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
