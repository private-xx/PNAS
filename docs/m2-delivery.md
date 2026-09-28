# M2 MVP 交付与决策记录

> 里程碑 M2(个人 NAS 核心)已通过 PR #1 合并进 `main`。本文件把开发过程中的**关键裁决、验证证据与遗留事项**从临时账本固化到仓库,便于日后追溯。
>
> 计划与账本原文(SDD 工作区,未入库):`docs/superpowers/plans/2026-09-07-m2-mvp.md`

## 1. 交付结果

| 项 | 内容 |
|---|---|
| 合并 | PR #1:`m2-mvp` → `main`,29 提交 / 85 文件 / +5468 −58,合并提交 `dc5afe5` |
| 后端 | Java 21 · Spring Boot 3.3.5 · PostgreSQL 16 · Flyway v1+v2 · 服务端会话 + CSRF · ACL 引擎 · 4 MiB sha256 内容寻址 blobstore · 分块上传(断点续传/版本化/幂等)· 文件 REST API · DB 任务队列 · ArchUnit |
| 前端 | Vue 3 + TS + Vite + Pinia + Element Plus:登录/会话、文件浏览器(含版本历史)、分块断点续传上传器(指纹续传、重试、取消)、纯 JS SHA-256 回退 |
| 部署 | `deploy/docker-compose.yml`(db + app + web)、两个 Dockerfile、nginx 配置(含 `client_max_body_size` 与 SSE 反代) |
| 验收 | `scripts/acceptance-m2.sh`(幂等,23 项断言) |

## 2. 出口标准与验证证据

| 出口标准 | 验证 | 结果 |
|---|---|---|
| 2 个账号互不可见 | 成员读管理员目录 → 403;跨用户写/改/删/恢复/查版本 → 403(自动化用例) | ✅ |
| 大文件可断点续传 | 少块 complete → 400;补块后续传完成;下载 SHA-256 与本地一致 | ✅ |
| 误删可恢复 | 删除 → 回收站可见 → 恢复 → 下载哈希一致 | ✅ |

- 端到端脚本:**PASS=23 / FAIL=0**(可幂等重跑);**空库首次部署**(Flyway v1+v2 + bootstrap 管理员)同样通过
- 后端全套件:**47/47 PASS**(Testcontainers PostgreSQL 16;曾出现一次不可复现的偶发失败,其后连续多轮全绿)
- 前端:`npm run build` 通过;**同源网关**路径(SPA 托管 + `/api` 反代 + CSRF)验证通过
- 运行期完整性:12 条块记录 → 6 个唯一哈希 → 6 个 blob 文件(**内容寻址去重生效**,无缺失块)
- 部署就绪:`docker compose config` 通过(仅 `web` 对外发布;`app`/`db` 仅内部网络)

## 3. 评审与修复(共 6 Critical + 20+ Important)

按 subagent-driven 流程逐任务评审 + 修复轮;收尾做三段最终评审(认证/ACL、存储/任务、前端/部署)并再修复两轮。三个最值得注意的生产级问题:

1. **nginx 默认 1 MB 体量限制** → 4 MiB 分块 PUT 会被 413;本地 Vite 网关无该限制,原本测不出来(已加 `client_max_body_size 8m`)
2. **上传依赖 WebCrypto** → 纯 HTTP 局域网(`crypto.subtle` 不可用)上传整体失败;已加纯 JS SHA-256 回退,并以 Node crypto 8 组向量(含 0/55/56/64/4 MiB 边界)校验,期间修掉 padding 块数 bug
3. **ACL `inherited=false` 被忽略** → 静默越权授权;并改为**全链显式 DENY 优先**

其他重要修复:同名目录被挂文件版本、块长度未校验、并发同哈希入库竞态、并发分块 PUT 丢失更新(`@Version`)、`complete` 幂等 + 行级锁、上传前复检目标目录写权限、统一错误体扩展、登录失败锁定(FR-AUTH-03)、消除用户名枚举时序、组管理 API(FR-AUTH-05)、跨目录续传错放、失效会话重建、同文件双任务去重、取消不可被恢复路径复活、`index.html` 不缓存。

## 4. 过程裁决(R1–R27 摘要)

| # | 裁决 | 成本/影响 |
|---|---|---|
| R3 | 任务调度需 `@EnableScheduling`,测试环境关闭调度器 | 漏加则定时任务不跑 |
| R4 | Maven 必须用项目内 settings(绕过本机不可达私有镜像) | 漏用则依赖解析失败 |
| R6 | 本机 Docker Hub 不可达:新镜像需经镜像源拉取并本地 tag | 漏做则容器测试无法运行 |
| R8 | 集成测试基座改 JVM 生命周期单例容器 | 修正多测试类复用上下文的死端口问题 |
| R11 | 长时实现型 subagent 在本环境被反复中止 → 实现由控制器内联完成,**评审门禁保留** | 保证进度不被中断吞掉 |
| R13 | ACL 引擎直接依赖 `files.NodeRepository`(模块化单体内跨领域引用) | 拆模块时需引入端口接口 |
| R14 | ArchUnit 包模式必须用项目全限定前缀;`andShould` 误用改为单一谓词 | 否则规则命中框架包/语义错误 |
| R15 | 前端三任务合并为一个交付单元评审 | 共享 API 与视图骨架 |
| R16 | ACL `owner 全权` 语义在 M2 保留 | 共享功能(M4)前需重评 |
| R18 | nginx 容器运行时验证不可得 → 以等价同源网关 + compose 静态校验 + 配置评审替代 | 残留风险已记入 PR |
| R19/R21 | 长评审挂起 → 中断并按路径拆分重派 | 避免门禁卡死 |
| R20 | 祖先链 R 校验仅作用于目录列举;`content()`/`listVersions()` 以目标节点权限为准 | 符合架构 §6.2 第 4 条语义 |
| R23 | ACL 改为**全链显式 DENY 优先**;`'a'` 不再可写入 ACL | 更严格、更安全 |
| R24 | 登录失败锁定为内存实现(按用户名,5 次/5 分钟) | M5 加固时改持久化 + IP 维度 |
| R25 | compose 的 `web→app` 无健康条件,依赖 `restart` 自愈 | 已文档化 |
| R26 | 一次不可复现的全量失败 | 其后连续多轮 47/47,保留观察 |
| R27 | M2 出口标准全部达成;nginx 运行时未实测(见 R18) | 残留风险写入 PR |

## 5. 遗留事项(不阻塞 M2 合并)

- **nginx 运行时未实测**、**Docker 镜像尚未实际构建**(本机镜像仓库不可达)
- 设计取舍待重评:owner 全权(R16)、`iam → files` 耦合(R13)、内存版登录锁定(R24)
- 性能类:N+1 祖先查询、`manifest_sha256` 无索引、`content()` 在事务内打开多个流
- 功能边界:目录删除仅标记自身、restore 自动改名仅一次、Range 仅单段、配额/回收站到期清理/blob GC 未实现
- 各任务评审的逐条 deferred minors 见 SDD 账本(未入库)

## 6. 后续里程碑

- **M3 媒体**:缩略图、时间线、相册、直放格式在线播放
- **M4 自动上传与备份 Agent**(macOS/Windows)+ 恢复演练(SC-2/5/6)
- **M5 流媒体与加固**:刮削、转码、安全基线、外网访问方案、监控告警
- **M6 验收与文档**;**M7** 后续立项(镜像同步、Linux Agent、人脸、投屏等)

需求与架构基线见 [requirements-analysis.md](requirements-analysis.md) v0.3 与 [architecture-design.md](architecture-design.md) v0.3。

### 5.1 逐条 deferred minors(来自各任务评审,已确认不影响 M2 验收)

- Task 1: minor (deferred):SchemaMigrationTest 只断言表存在、未断言 jsonb 列(plan-mandated)
- Task 1: minor (deferred):HTTP 状态未由 ErrorCode 推导,调用方显式给 HttpStatus
- Task 1: minor (deferred):ApiError.detail 类型 Object 序列化偏重
- Task 1: minor (deferred):测试成员 package-private(plan-mandated)
- Task 2: minor (deferred):save 后实体内存 createdAt 为 null(plan-mandated,insertable=false)
- Task 2: minor (deferred):deleteByUserId 为派生 SELECT+delete 而非批量删除(report 措辞不准)
- Task 2: minor (deferred):groups.created_at 未被 Group 映射(brief 接口未要求,validate 下无害)
- Task 5: minor (deferred):store() 的 size 参数未被使用(brief verbatim)
- Task 5: minor (deferred):并发同名块竞态未测(重复写入竞态会抛 FileAlreadyExists/泄漏 tmp,brief 已知)

### 5.2 按任务汇总(含评审提出的其余 minor)

- Task 2:save 后 createdAt 为 null;deleteByUserId 非批量;groups.created_at 未映射
- Task 3:CsrfGuardFilter 自带 ObjectMapper;CSRF 未校验会话 liveness;DISABLED 用户仍可登录;session 端点重复查询;CSRF 负路径测试已补
- Task 5:store() size 参数未用;并发同名块竞态
- Task 6(复评另记):零字节测试未断言"无分块行"、manifest 无索引、`resolveNode` 名称未提供
- Task 7:N+1 祖先查询;目录 delete 只标记自身;restore 自动改名单次;list() 接受 FILE parentId;home 根可被删除但不可恢复
- Task 8:handler 重复 type 静默覆盖;cancel=DEAD 语义;测试钩子暴露在生产 bean
- Task 9:无"防静默空扫描"断言;规则未加 `.because`(部分已补)
- Task 4:`countByNodeId` 死代码;`'a'` 权限不可授予;混合 grant+deny 未拒绝;iam→files 环依赖(R13)

