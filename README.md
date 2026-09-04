# PNAS — Personal NAS(纯从零自研)

面向家庭小团队(2–8 人)的私有化网络存储系统:文件存储与多端访问/自动上传、照片视频媒体管理、影音流媒体、集中备份。**内网为主,可选安全外网访问。**

> 定位:自研学习/研究型项目 —— 存储语义、上传协议、权限模型均为自研;技术栈 **Java 21 + Spring Boot + Vue 3 + PostgreSQL + nginx**。

## 文档

| 文档 | 说明 |
|---|---|
| [需求分析](docs/requirements-analysis.md) | v0.2 已确认(M0) |
| [系统架构设计](docs/architecture-design.md) | v0.2 已确认,ADR-01~12 全部通过(M1) |

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 需求分析 | ✅ |
| M1 | 架构设计 | ✅ |
| M2 | MVP:账号/ACL/文件管理/分块上传下载/回收站 + Vue Web | 🔨 进行中 |
| M3 | 媒体(时间线/相册/在线播放) | 待启动 |
| M4 | 自动上传与备份 Agent(macOS/Windows)+ 恢复演练 | 待启动 |
| M5 | 影音(刮削/转码)+ 安全加固/外网访问 | 待启动 |
| M6 | 试运行与文档 | 待启动 |

## 仓库结构

```
backend/    Maven 多模块:pnas-common / pnas-server(单体服务)
frontend/   Vue 3 + TypeScript(Vite),生产由 nginx 托管
deploy/     docker-compose、Dockerfile、nginx 配置、env 示例
docs/       需求与架构文档(后续四份手册)
agent/      (M4)桌面备份客户端
scripts/    (陆续补充)开发/运维脚本
```

## 开发前置要求

- **JDK 21**(本机默认 JDK 8,请通过 sdkman/brew/官方包安装并设置 `JAVA_HOME`);
- Maven 3.8+(随 Maven wrapper 亦可);
- Node.js 20+ 与 npm;
- PostgreSQL 16(推荐 `docker compose up -d db` 起容器)或本机安装;
- Docker 20.10+ / Compose v2(用于一键部署)。

> 注意:后端编译需要 JDK 21;使用 JDK 8 执行 Maven 会失败(`release 21` 不被支持)。
