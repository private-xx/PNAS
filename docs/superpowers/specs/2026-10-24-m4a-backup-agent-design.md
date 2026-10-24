# M4a 备份 Agent(设备/PAT + 增量备份 + 运行仪表盘 + 最小恢复)设计

> 里程碑 M4a(完整 M4 的第一切片)。上游基线:[requirements-analysis.md](../../requirements-analysis.md) v0.3(FR-BACKUP-01/02/03/07、SC-2)、[architecture-design.md](../../architecture-design.md) v0.3(§3.3 Agent、§6.8 自动上传/备份、§7 API、ADR-11)。
>
> 设计经用户逐节确认(2026-10-24);M4b 承接出站备份、恢复演练与告警。

## 1. 目标与出口标准

**目标**:桌面设备(macOS;Windows 交付配置与文档)通过一次性签发的设备令牌,把本地目录**增量**备份到 NAS 的固定路径下,并在 Web 上可见运行记录;可做最小化的恢复与校验。

| # | 出口标准 | 验证方式 |
|---|---|---|
| E1 | Web 可创建/吊销设备令牌,令牌**仅一次性展示**,服务端只存哈希 | 集成测试 + 手工 |
| E2 | `agent backup --once` 把目录树备份到 `/我的文件/<user>/自动备份/<device>/...`,文件内容哈希一致 | E2E 脚本比对 sha256 |
| E3 | **增量**:未变文件跳过、变更文件产生新版本、体积/首末块/mtime 任一不同即上传 | E2E:二次运行统计 skipped>0 且新版本号 +1 |
| E4 | **三模式**:手动(`--once`)、定时(daemon interval)、实时(WatchService + 防抖)可用 | 集成/手工:改文件后 10s 内触发 |
| E5 | 运行记录可查:状态/耗时/统计/错误,Web 仪表盘可见,自动刷新 | E2E + 页面 |
| E6 | `agent restore` 恢复目录到本地并校验 sha256;`agent verify` 校验最近一轮 | E2E 比对哈希 |
| E7 | 令牌吊销后 Agent 请求返回 401,Agent 以非 0 退出码失败 | E2E |
| E8 | macOS `jpackage` 产出可运行安装包;Windows 提供 jpackage 配置与文档 | 本机构建 + 安装后跑 `--once` |

## 2. 范围

**包含**:设备与 PAT、Bearer 鉴权、服务端清单接口、运行记录 API、`source_mtime` 协议扩展、备份目录树创建与版本化写入、Agent(扫描/规划/上传/实时/定时/恢复/校验/CLI)、Web 备份页(设备/令牌/运行)、macOS 安装包、Windows 打包脚本与文档、验收脚本。

**不包含(→ M4b 或更后)**:

| 项 | 去向 |
|---|---|
| NAS 出站备份(外接盘/另一台机器/对象存储) | M4b(FR-BACKUP-05) |
| 正式恢复演练与 RTO 报告、整机恢复 | M4b(SC-6) |
| 邮件/Webhook 告警 | M5(站内运行记录在 M4a) |
| 服务端 Profile 下发(路径/调度由服务端定义) | M4b(架构 §7 `/backup/profiles`) |
| 块级增量(只传变化块) | M4b 评估 |
| 保留/轮换策略(FR-BACKUP-04) | M4b |
| Windows 运行时验证 | 受环境所限,仅配置/文档(与 M2 nginx R18 同类) |

## 3. 已确认决策

| # | 决策 | 理由 / 代价 |
|---|---|---|
| **D1** | M4 拆为 **M4a(本设计)** 与 M4b;M4a 不做出站备份/演练/告警 | 可独立验收;完整 M4 需两个周期 |
| **D2** | Agent = **Java 21 CLI + jpackage**;macOS 本机构建实测,Windows 仅提供配置/脚本/文档 | 遵循 ADR-11;代价:Windows 运行未实测 |
| **D3** | 增量粒度 = **文件级**(size + sourceMtime + 首末块 sha 相同则跳过),变更文件整体上传,存储侧按内容寻址去重 | 与架构 §6.8 一致;大文件改中段仍传全量(局域网可接受) |
| **D4** | 删除语义 = **备份语义**:源端删除不影响 NAS 已有副本,旧版本保留 | 防误删;轮换清理 M4b |
| **D5** | 调度 = **常驻 daemon** + 内置定时 + WatchService 实时(5s 防抖)+ `--once` 给系统调度器 | 三模式全覆盖(FR-BACKUP-03) |
| **D6** | PAT = **用户身份**(Bearer),`deviceId` 仅用于归属/审计;一次性展示、存哈希、可吊销 | 与架构 §6.1/ADR-11 一致 |
| **D7** | NAS 侧布局 = `/我的文件/<user>/自动备份/<device>/<相对路径>`,同名覆盖走 M2 版本化写入 | 版本历史即备份历史,无日期层重复 |
| **D8** | 配置 = **Agent 本地 JSON**(服务器/令牌/根目录/间隔/实时开关/排除);服务端只存 devices + runs | 最快闭环;服务端 Profile 留 M4b |
| **D9** | M4a 含**最小恢复与校验**(`restore`/`verify`),正式演练留 M4b | 能在 M4a 内证明备份可恢复 |
| **D10** | 增量比对以**服务端清单接口**为真源 | 换机/重装/清状态仍正确 |

## 4. 数据模型(迁移 `V4__backup.sql`)

```sql
create table devices (
    id           uuid primary key,
    user_id      uuid not null references users(id) on delete cascade,
    name         text not null,
    platform     text not null,              -- MAC | WINDOWS | OTHER
    pat_hash     text not null unique,       -- sha256(token)
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz,
    revoked_at   timestamptz
);
create index ix_devices_user on devices (user_id);

create table backup_runs (
    id              uuid primary key,
    device_id       uuid not null references devices(id) on delete cascade,
    user_id         uuid not null references users(id) on delete cascade,
    mode            text not null,           -- MANUAL | SCHEDULED | REALTIME
    status          text not null,           -- RUNNING | SUCCESS | PARTIAL | FAILED
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    files_seen      int not null default 0,
    files_uploaded  int not null default 0,
    files_skipped   int not null default 0,
    files_failed    int not null default 0,
    bytes_uploaded  bigint not null default 0,
    error           text
);
create index ix_backup_runs_device on backup_runs (device_id, started_at desc);

-- 源端 mtime(可空):清单据此做增量判定;旧版本为空时只按 size + 首末块哈希
alter table file_versions add column source_mtime timestamptz;
```

`devices.pat_hash` 唯一;吊销写 `revoked_at`,鉴权查询要求 `revoked_at is null`。

## 5. 服务端组件(新包 `com.pnas.server.backup`)

```
backup/
├── domain/Device.java              # @Entity devices
├── domain/BackupRun.java           # @Entity backup_runs(+ 状态枚举)
├── DeviceRepository.java           # findByPatHash / findByUserId / ...
├── BackupRunRepository.java        # findByDeviceIdOrderByStartedAtDesc
├── DeviceService.java              # 创建(生成 32B 随机令牌→返回明文一次)/列表/吊销
├── BackupRunService.java           # start / finish(统计与状态校验)/list
├── ManifestService.java            # 按 device 备份根递归列出条目(分页 + 首末块)
├── BackupController.java           # /api/v1/devices、/backup/runs、/backup/manifest
└── DeviceBearerAuth.java           # 令牌解析工具(供 SessionAuthFilter 调用)
```

**鉴权扩展**:`SessionAuthFilter` 先看 `Authorization: Bearer <token>` → `sha256` 查 `devices`(未吊销、用户 ACTIVE)→ 注入 `SessionPrincipal` 并 `req.setAttribute("pnas.deviceId", …)`,更新 `last_seen_at`(节流:同设备 60s 内不重复写);无 Bearer 则沿用 Cookie 会话。CSRF 守卫现行逻辑在**无 Cookie** 时放行,Bearer 请求因此无需 `X-CSRF`(文档标注)。

**上传协议扩展**:`StartUpload(destParentId, filename, size, sourceMtime)`;`sourceMtime` 为可空 `Instant`(ISO-8601),`complete` 时写入 `file_versions.source_mtime`;旧客户端不传则为 null。

## 6. API 契约(`/api/v1`)

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/devices` | `{name, platform}` → `{device:{id,name,platform,createdAt}, token}`(token 仅此一次) | 登录用户 |
| GET | `/devices` | 我的设备列表(不含 token) | 登录用户 |
| DELETE | `/devices/{id}` | 吊销(幂等) | 属主 |
| GET | `/backup/manifest?deviceId=&cursor=&limit=` | `{items:[{relPath,nodeId,size,sourceMtime,firstChunkSha,lastChunkSha,versionNo}], nextCursor}` | PAT/会话;设备须属当前用户 |
| POST | `/backup/runs` | `{deviceId, mode}` → `{id, startedAt}` | PAT/会话 |
| PATCH | `/backup/runs/{id}` | `{status, filesSeen, filesUploaded, filesSkipped, filesFailed, bytesUploaded, error?}` | PAT/会话(须属主) |
| GET | `/backup/runs?deviceId=&limit=` | 运行列表(倒序) | PAT/会话 |

说明:清单的 `relPath` 为相对备份根(`/我的文件/<user>/自动备份/<device>/` 之下)的 POSIX 路径;服务端在查询前**幂等确保**该根目录存在(不存在则创建,返回空清单)。分页按 `relPath` 升序,`cursor` 为上一页最后一个 `relPath`。`firstChunkSha`/`lastChunkSha` 由 `version_chunks`(最小/最大 seq)得出;空文件两者为 null。

## 7. 认证与安全

- 令牌:32 字节 `SecureRandom` → Base64URL;服务端仅存 `sha256(token)`;返回一次;吊销后立即 401。
- 身份:令牌解析为用户主体(`SessionPrincipal`),ACL 按该用户判定;Agent 只能访问自己的备份根(路径由服务端解析,不接受 `..`/绝对路径)。
- 运行归属:`backup_runs.device_id` 必须属于当前用户;跨用户访问返回 404(不泄露)。
- 审计:设备创建/吊销、运行开始/结束写结构化应用日志,运行结果持久化在 `backup_runs`;独立 `audit_events` 表在 M2 未实现(仅架构草图),本设计不引入,留后续里程碑。
- 传输:内网 HTTP 或 TLS 由部署方决定(`PNAS_REQUIRE_TLS` 语义不变)。

## 8. Agent 设计(`agent/` 新模块 `pnas-agent`)

```
agent/
├── pom.xml                          # 依赖:JDK 21 + Jackson(与 Spring 同版本域)
└── src/main/java/com/pnas/agent/
    ├── Main.java                    # 子命令:init | run | backup --once | restore | verify | status
    ├── config/AgentConfig.java      # JSON:serverUrl, token, deviceId, roots[], intervalMinutes,
    │                                #       realtime, exclude[], maxRetries, retryBackoffMs
    ├── scan/Scanner.java            # 遍历 + 排除 + 不跟随符号链接 + 相对路径归一化
    ├── remote/ManifestClient.java   # GET /backup/manifest 分页聚合
    ├── remote/UploadClient.java     # start(带 sourceMtime)/putChunk/complete(重试与退避)
    ├── remote/RunClient.java        # start/finish 运行
    ├── plan/Planner.java            # 决策:SKIP | UPLOAD(+ 原因,便于日志与统计)
    ├── plan/ChunkHashes.java        # 首末块 sha256(4 MiB 分块),全量 sha 仅 verify 用
    ├── run/Runner.java              # 单飞、统计、部分失败、日志
    ├── watch/Watcher.java           # WatchService 递归 + 5s 防抖
    ├── sched/Scheduler.java         # 间隔调度(默认 360 分钟)
    ├── restore/Restorer.java        # 递归下载 + sha256 校验 + 冲突策略
    └── packaging/                   # jpackage 脚本与 Windows 文档(见 §11)
```

**配置示例**(`~/.pnas-agent/config.json`):

```json
{
  "serverUrl": "http://nas.local:8080",
  "token": "…一次性令牌…",
  "deviceId": "…",
  "roots": [
    { "path": "/Users/me/Documents", "remoteName": "Documents" },
    { "path": "/Users/me/Pictures",  "remoteName": "Pictures" }
  ],
  "intervalMinutes": 360,
  "realtime": true,
  "exclude": ["**/.git/**", "**/node_modules/**", "**/*.tmp"],
  "maxRetries": 3,
  "retryBackoffMs": 500
}
```

**决策规则(Planner)**:对每个本地文件,取 `size`、`mtime`、首末块 sha;
`manifest` 中存在同 `relPath` 且 `size 相同 && sourceMtime 相同 && firstChunkSha 相同 && lastChunkSha 相同` → `SKIP`,否则 `UPLOAD`。
服务端 `sourceMtime` 为 null 时,仅比较 `size + 首末块`(兼容旧数据)。

**启动与状态**:Agent 启动时读取并校验本地配置(缺失/非法 → 退出码 2);令牌有效性以首次 `POST /backup/runs` 的结果为准(401 → 退出码 2,不产生 run 记录)。`agent status` 输出服务器/设备/根目录,并拉取 `GET /backup/runs?limit=1` 显示最近一轮。

**上传**:逐级确保远端目录(`POST /nodes` 幂等:已存在同名目录则复用);`POST /uploads` 带 `sourceMtime`;分块 PUT 3 次重试(指数退避,`retryBackoffMs`);`complete` 后计入 `uploaded` 与 `bytesUploaded`。单文件失败 → `filesFailed++` 并继续;整轮 `status = filesFailed>0 ? PARTIAL : SUCCESS`;无法创建 run/鉴权失败 → `FAILED` 且进程退出码 2。

**实时**:递归注册 `WatchService`(新建子目录动态注册),事件合并后 5s 触发一轮增量;每轮结束重新全量扫描兜底(防漏事件)。**定时**:`intervalMinutes` 到点触发;与实时共用单飞锁(并发请求合并为一轮)。

**恢复**:`restore <relPath> --dest <dir> [--overwrite]`:按清单/节点树递归下载,校验全量 sha256;目标已存在且非 `--overwrite` → 跳过并计入冲突。`verify [--last N]`:对最近一轮抽样(默认 20 或全部 `--all`)下载并比对哈希,输出通过/失败清单。

## 9. 错误处理矩阵

| 情况 | 行为 |
|---|---|
| 令牌无效/吊销 | API 401;Agent 记录错误并以退出码 2 结束 |
| 单文件上传失败(网络/校验) | 重试 3 次;仍失败→计入 `files_failed`,继续其它文件;轮次 `PARTIAL` |
| 远端目录创建冲突 | 复用同名目录;同名文件=新版本(版本化写入) |
| 本地文件在扫描后被删除 | 该文件跳过并记 `WARN`;不中断整轮 |
| 本地文件在扫描后被修改 | 下一轮按新 size/mtime 重新上传(本轮上传的是扫描时刻内容) |
| 清单分页中断 | 重试一次;仍失败→本轮 `FAILED`(下次全量) |
| 磁盘写满/只读目录 | 单文件失败计入;轮次 `PARTIAL` |
| WatchService 溢出 | 记 WARN,下一轮全量扫描兜底 |

## 10. 测试策略

**服务端**:单元(令牌哈希/吊销、manifest 首末块与排序分页、run 状态机);集成(Bearer 鉴权、401/404 隔离、设备 CRUD、manifest 与 `source_mtime` 往返、run 生命周期、上传带 mtime 后 `file_versions.source_mtime` 生效、ACL 隔离)。

**Agent**:单元(`Planner` 跳过矩阵:size/mtime/首末块各维度、`Scanner` 排除/符号链接/空目录、`Watcher` 防抖、`Config` 解析与校验、`Restorer` 路径安全);集成(本机起真实 `pnas-server` 测试上下文或用 JDK `HttpServer` 桩驱动 CLI;覆盖 `init → backup --once → 变更 → 再跑 → verify → restore`)。

**E2E 验收** `scripts/acceptance-m4.sh`(幂等,服务端 :8080/指定 BASE):
1. 管理员登录 → 建设备 → 拿令牌;
2. 本地生成临时目录(3 文件 + 1 子目录 + 1 大文件 > 4 MiB);
3. `agent backup --once` → 断言节点树/大小/sha256、run SUCCESS、统计匹配;
4. 改 1 个文件、增 1 个文件 → 再跑 → 断言 skipped>0、新文件上传、改动文件版本号 +1;
5. `agent verify --all` → 通过;`agent restore` 到新目录 → 哈希一致;
6. 吊销令牌 → `agent backup --once` 退出码非 0 且服务端返回 401(失败发生在建立 run 之前,因此**不新增**运行记录);
7. 输出 PASS/FAIL 统计。

**打包**:macOS `jpackage --type dmg`(应用镜像 + 启动器),安装后执行 `--once`;Windows 提供 `jpackage` 命令与 Task Scheduler/launchd 注册说明(不实测,标注)。

## 11. 性能与规模

- 扫描 1,000 文件(本地 SSD)≤ 2s;清单拉取(1,000 条,分页 200)≤ 2 次请求;
- 上传吞吐受服务端分块与磁盘限制,沿用 M2/M3 实测能力;单轮串行上传(避免并发写放大),失败重试;
- manifest 查询走 `nodes`/`file_versions`/`version_chunks` 索引,单页 ≤200 条。

## 12. 风险与 M4b 衔接

| 风险 | 缓解 |
|---|---|
| Windows 未实测 | 代码平台中立(仅 `java.nio.file` + `Path`),打包文档化;M4b 或用户 Windows 机器上补测 |
| 大目录扫描耗时 | 排除规则 + 单轮串行 + 后续可加并行(读取与上传分离) |
| 令牌泄露 | 一次性展示、仅存哈希、可吊销、审计;内网部署 |
| 版本历史无限增长 | M4b 轮换/保留策略(FR-BACKUP-04) |
| 实时事件丢失(macOS WatchService 轮询) | 5s 防抖 + 每轮全量兜底 |
| 运行记录与真实上传不一致 | 服务端以实际完成的上传/版本为准,Agent 统计仅参考 |

## 13. 验收清单(M4a 完成判据)

- [ ] `V4__backup.sql` 在空库与既有库上均可应用
- [ ] E1–E8 全部通过,证据记入 `docs/progress.md`
- [ ] 后端全套件(含既有 126 项)保持绿色;新增 backup 测试全绿
- [ ] 前端 `npm run build` 通过;备份页可用
- [ ] `scripts/acceptance-m4.sh` 幂等通过
- [ ] macOS 安装包构建 + 安装后 `--once` 实测;Windows 打包文档
- [ ] 交付与裁决记入 `docs/`(design decisions D1–D10、已知限制)
