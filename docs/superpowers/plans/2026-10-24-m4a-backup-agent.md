# M4a 备份 Agent 实施计划(设备/PAT + 增量备份 + 运行仪表盘 + 最小恢复)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让桌面设备用一次性签发的设备令牌,把本地目录**增量**备份到 NAS 的固定路径,并在 Web 上看到运行记录;可做最小恢复与校验。

**Architecture:** 服务端新增 `backup` 包(设备/令牌、Bearer 鉴权、备份清单、运行记录)与 `V4__backup.sql`;Agent 是独立 Maven 模块 `pnas-agent`(Java 21 CLI,仅依赖 Jackson + JDK HTTP),以"扫描 → 与服务端清单比对 → 按 4 MiB 分块上传 → 上报运行"的薄客户端形态工作;Web 新增备份页。

**Tech Stack:** Java 21 · Spring Boot 3.3.5 · PostgreSQL 16 · Flyway · JUnit 5 + Testcontainers · Java `java.net.http` + Jackson(databind 2.17.x)· Vue 3 + TypeScript + Element Plus · jpackage。

**Spec:** `docs/superpowers/specs/2026-10-24-m4a-backup-agent-design.md`(实现依据;冲突时以 spec 为准并回报)

## Global Constraints

- **分支**:执行前从 `main` 创建 `m4a-backup` 分支;每个任务结束提交一次,消息用中文 `feat(m4a): ...` / `fix(m4a): ...` / `test(m4a): ...`。
- **Maven(绝对路径,本机 PATH 无 mvn)**:`MVN=/Users/xiao/hui/soft/maven3.8/bin/mvn`;`JAVA_HOME=/Users/xiao/hui/project/PNAS/.tools/jdk-21.0.12.1+1/Contents/Home`;后端命令 `cd backend && "$MVN" -o -s ../.tools/maven-settings.xml -pl pnas-server -am test`;**Agent 模块独立构建**:`cd agent && "$MVN" -o -s ../.tools/maven-settings.xml test`。
- **依赖**:后端不新增依赖(M3 已允许 metadata-extractor);**Agent 只允许 `com.fasterxml.jackson.core:jackson-databind`(2.17.2)** 与 JDK(HTTP 用 `java.net.http`)。
- **数据目录**:沿用 `PNAS_DATA_DIR`;备份文件走既有 blobstore,不新建存储。
- **令牌**:32 字节 `SecureRandom` → Base64URL;服务端仅存 `sha256(token)`;一次性返回;吊销后立即 401。
- **备份根**:`/我的文件/<user>/自动备份/<device>/`;文件同名覆盖 = 新版本(M2 既有语义)。
- **增量判定**:`size` 相同 && `sourceMtime` 相同 && 首末 4 MiB 块 sha256 相同 → 跳过;`sourceMtime` 缺失时只看 size + 首末块。
- **分块**:固定 4 MiB(`4194304`),复用 `POST /uploads` + `PUT /uploads/{id}/chunks/{seq}` + `POST /uploads/{id}/complete`。
- **运行状态**:`RUNNING → SUCCESS | PARTIAL | FAILED`;单文件失败不中断整轮(`PARTIAL`)。
- **权限**:所有 backup 端点走服务端鉴权(会话 Cookie 或 `Authorization: Bearer`);设备必须属于当前用户,跨用户一律 404。
- **输出/日志**:Agent 日志写 stderr;退出码 `0` 成功、`2` 配置/鉴权/致命错误、`1` 部分失败。
- **测试纪律**:先写失败测试再实现;任务结束该任务测试 + 既有 126 项后端测试全绿;前端 `npm run build` 通过。
- **提交协议(共享工作区)**:`git reset` → `git add <本任务文件>` → `git commit --only <本任务文件>`;提交后 `git show --stat` 核验,禁止 `git add -A`。

---

## 文件结构(职责边界)

**服务端新增**(`backend/pnas-server/src/main/java/com/pnas/server/backup/`)
| 文件 | 职责 |
|---|---|
| `domain/Device.java` | `devices` 实体 |
| `domain/DevicePlatform.java` | `MAC / WINDOWS / OTHER` |
| `domain/BackupRun.java` | `backup_runs` 实体 |
| `domain/RunMode.java` / `domain/RunStatus.java` | `MANUAL/SCHEDULED/REALTIME`、`RUNNING/SUCCESS/PARTIAL/FAILED` |
| `DeviceRepository.java` | `findByPatHashAndRevokedAtIsNull`、`findByUserIdOrderByCreatedAtDesc`、`findByIdAndUserId` |
| `BackupRunRepository.java` | `findByDeviceIdOrderByStartedAtDesc`、`findTop50ByUserIdOrderByStartedAtDesc` |
| `DeviceService.java` | 生成令牌/哈希/创建/列表/吊销/`resolveToken`/`touch` |
| `DeviceController.java` | `/api/v1/devices` |
| `BackupRunService.java` | 运行开始/结束/校验/列表 |
| `ManifestService.java` | 备份根幂等创建、递归清单、首末块、分页 |
| `BackupController.java` | `/api/v1/backup/manifest`、`/api/v1/backup/runs` |

**服务端修改**
| 文件 | 改动 |
|---|---|
| `src/main/resources/db/migration/V4__backup.sql` | 新表 + `file_versions.source_mtime` + `upload_sessions.source_mtime` |
| `web/security/SessionAuthFilter.java` | 支持 `Authorization: Bearer`(解析设备令牌,注入 principal + deviceId) |
| `web/security/SecurityConfig.java` | 过滤器构造参数加入设备仓库 |
| `files/domain/FileVersion.java` | `sourceMtime` 字段 |
| `files/domain/UploadSession.java` | `sourceMtime` 字段 |
| `files/UploadService.java` | `start(...)` 增加 `Instant sourceMtime` 重载;`complete` 写入版本 |
| `files/UploadController.java` | `StartUpload` 增加 `Instant sourceMtime` |
| `common/error/ErrorCode.java` | 需要时新增 `DEVICE_NOT_FOUND`(默认复用 `NOT_FOUND`) |

**Agent 新模块**(`agent/`)
```
agent/pom.xml
agent/src/main/java/com/pnas/agent/
  Main.java                 # 子命令分发与退出码
  config/AgentConfig.java   # 配置 record + JSON 读写 + 校验
  model/FileItem.java       # 本地文件(relPath/size/mtime/first/last sha)
  model/ManifestEntry.java  # 服务端清单条目(与服务端 JSON 对齐)
  scan/Scanner.java         # 遍历 + 排除 glob + 不跟随符号链接
  scan/ChunkHashes.java     # 首末 4 MiB 的 sha256
  plan/Planner.java         # SKIP/UPLOAD 决策
  remote/HttpJson.java      # 共享 HttpClient + Jackson + Bearer + 错误处理
  remote/ManifestClient.java
  remote/RunClient.java
  remote/UploadClient.java
  remote/NodeClient.java    # ensureDir / listChildren / download
  run/Runner.java           # 单飞、统计、PARTIAL
  watch/Watcher.java        # WatchService 递归 + 防抖
  sched/Scheduler.java      # 间隔调度
  restore/Restorer.java     # 递归下载 + sha256 校验
  restore/Verifier.java     # 清单抽样/全量校验
  packaging/package-macos.sh / windows.md
```

**Agent 测试**(`agent/src/test/java/com/pnas/agent/`):`ConfigTest`、`ScannerTest`、`ChunkHashesTest`、`PlannerTest`、`RunnerTest`(JDK `HttpServer` 桩)、`RestorerTest`(路径安全)。

**前端新增**:`src/api/backup.ts`、`src/views/BackupView.vue`(设备/令牌/运行);修改 `router/index.ts`、`App.vue`。
**脚本新增**:`scripts/acceptance-m4.sh`。

---

### Task 1: 迁移、实体与仓库

**Files:**
- Create: `backend/pnas-server/src/main/resources/db/migration/V4__backup.sql`
- Create: `backend/pnas-server/src/main/java/com/pnas/server/backup/domain/{Device,DevicePlatform,BackupRun,RunMode,RunStatus}.java`
- Create: `backend/pnas-server/src/main/java/com/pnas/server/backup/{DeviceRepository,BackupRunRepository}.java`
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/files/domain/FileVersion.java`(sourceMtime)
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/files/domain/UploadSession.java`(sourceMtime)
- Test: `backend/pnas-server/src/test/java/com/pnas/server/SchemaMigrationTest.java`(追加断言)

**Interfaces:**
- Consumes: 既有 `User`、`Node`、`FileVersion`
- Produces: `Device.create(User, String name, DevicePlatform, String patHash)`、`Device.getUser()/getName()/getPlatform()/getPatHash()/getRevokedAt()/getLastSeenAt()/markRevoked()/touch()`;`BackupRun.start(Device, User, RunMode)`、`finish(RunStatus, stats...)`;`DeviceRepository.findByPatHashAndRevokedAtIsNull(String) : Optional<Device>`、`findByUserIdOrderByCreatedAtDesc(UUID)`、`findByIdAndUserId(UUID, UUID)`;`BackupRunRepository.findByDeviceIdOrderByStartedAtDesc(UUID, Pageable)`

- [ ] **Step 1: 写迁移**

```sql
-- V4: M4a 设备与备份运行
create table devices (
    id           uuid primary key,
    user_id      uuid not null references users(id) on delete cascade,
    name         text not null,
    platform     text not null,
    pat_hash     text not null unique,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz,
    revoked_at   timestamptz
);
create index ix_devices_user on devices (user_id);

create table backup_runs (
    id              uuid primary key,
    device_id       uuid not null references devices(id) on delete cascade,
    user_id         uuid not null references users(id) on delete cascade,
    mode            text not null,
    status          text not null,
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

alter table file_versions add column source_mtime timestamptz;
alter table upload_sessions add column source_mtime timestamptz;
```

- [ ] **Step 2: 实体**

`DevicePlatform.java`:`public enum DevicePlatform { MAC, WINDOWS, OTHER }`
`RunMode.java`:`public enum RunMode { MANUAL, SCHEDULED, REALTIME }`
`RunStatus.java`:`public enum RunStatus { RUNNING, SUCCESS, PARTIAL, FAILED }`

`Device.java`:

```java
package com.pnas.server.backup.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "devices")
@Getter @NoArgsConstructor
public class Device {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private User user;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DevicePlatform platform;
    @Column(name = "pat_hash", nullable = false, unique = true) private String patHash;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    public static Device create(User user, String name, DevicePlatform platform, String patHash) {
        Device d = new Device();
        d.id = UUID.randomUUID();
        d.user = user;
        d.name = name;
        d.platform = platform;
        d.patHash = patHash;
        return d;
    }
    public void touch() { this.lastSeenAt = Instant.now(); }
    public void markRevoked() { this.revokedAt = Instant.now(); }
    public boolean isRevoked() { return revokedAt != null; }
}
```

`BackupRun.java`:

```java
package com.pnas.server.backup.domain;

import com.pnas.server.iam.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "backup_runs")
@Getter @NoArgsConstructor
public class BackupRun {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_id")
    private Device device;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private User user;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RunMode mode;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RunStatus status = RunStatus.RUNNING;
    @Column(name = "started_at", nullable = false, insertable = false, updatable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "files_seen", nullable = false) private int filesSeen;
    @Column(name = "files_uploaded", nullable = false) private int filesUploaded;
    @Column(name = "files_skipped", nullable = false) private int filesSkipped;
    @Column(name = "files_failed", nullable = false) private int filesFailed;
    @Column(name = "bytes_uploaded", nullable = false) private long bytesUploaded;
    @Column private String error;

    public static BackupRun start(Device device, User user, RunMode mode) {
        BackupRun r = new BackupRun();
        r.id = UUID.randomUUID();
        r.device = device;
        r.user = user;
        r.mode = mode;
        return r;
    }
    public void finish(RunStatus status, int seen, int uploaded, int skipped, int failed, long bytes, String error) {
        this.status = status;
        this.filesSeen = seen;
        this.filesUploaded = uploaded;
        this.filesSkipped = skipped;
        this.filesFailed = failed;
        this.bytesUploaded = bytes;
        this.error = error;
        this.finishedAt = Instant.now();
    }
}
```

- [ ] **Step 3: 仓库与实体字段**

`DeviceRepository.java`:

```java
package com.pnas.server.backup;

import com.pnas.server.backup.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    Optional<Device> findByPatHashAndRevokedAtIsNull(String patHash);
    List<Device> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Device> findByIdAndUserId(UUID id, UUID userId);
}
```

`BackupRunRepository.java`:

```java
package com.pnas.server.backup;

import com.pnas.server.backup.domain.BackupRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BackupRunRepository extends JpaRepository<BackupRun, UUID> {
    List<BackupRun> findByDeviceIdOrderByStartedAtDesc(UUID deviceId, Pageable pageable);
    List<BackupRun> findByUserIdOrderByStartedAtDesc(UUID userId, Pageable pageable);
}
```

在 `FileVersion` 增加:

```java
    @Column(name = "source_mtime")
    private Instant sourceMtime;
```
在 `UploadSession` 增加同样字段。

- [ ] **Step 4: 测试(先失败)**

`SchemaMigrationTest` 追加:

```java
    @Test
    void backupTablesExistWithExpectedColumns() {
        var devices = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'devices'", String.class);
        assertThat(devices).contains("id", "user_id", "name", "platform", "pat_hash",
            "created_at", "last_seen_at", "revoked_at");
        var runs = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'backup_runs'", String.class);
        assertThat(runs).contains("id", "device_id", "user_id", "mode", "status", "started_at",
            "finished_at", "files_seen", "files_uploaded", "files_skipped", "files_failed",
            "bytes_uploaded", "error");
        var fv = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'file_versions'", String.class);
        assertThat(fv).contains("source_mtime");
    }
```

- [ ] **Step 5: 跑失败 → 实现 → 跑通过**

Run: `cd backend && "$MVN" -o -s ../.tools/maven-settings.xml -pl pnas-server test -Dtest='SchemaMigrationTest'`
Expected: 先失败(`devices` 表不存在),迁移与实体落地后 PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/pnas-server/src/main/resources/db/migration/V4__backup.sql \
        backend/pnas-server/src/main/java/com/pnas/server/backup \
        backend/pnas-server/src/main/java/com/pnas/server/files/domain/FileVersion.java \
        backend/pnas-server/src/main/java/com/pnas/server/files/domain/UploadSession.java \
        backend/pnas-server/src/test/java/com/pnas/server/SchemaMigrationTest.java
git commit -m "feat(m4a): 设备/运行记录迁移与实体"
```

---

### Task 2: 设备服务与设备 API

**Files:**
- Create: `backend/pnas-server/src/main/java/com/pnas/server/backup/DeviceService.java`
- Create: `backend/pnas-server/src/main/java/com/pnas/server/backup/DeviceController.java`
- Modify: `backend/pnas-server/src/test/java/com/pnas/server/architecture/PackageArchitectureTest.java`(控制器白名单与"领域不得依赖 web"列表加入 `com.pnas.server.backup..`)
- Test: `backend/pnas-server/src/test/java/com/pnas/server/backup/DeviceApiIntegrationTest.java`

**Interfaces:**
- Consumes: `DeviceRepository`、`UserRepository.findById`、`Sha256.hex(byte[])`、`SessionPrincipal`
- Produces: `DeviceService.create(me,name,platform) : Created(device, token)`、`list(me)`、`revoke(me,id)`、`resolveToken(String) : Optional<Device>`;HTTP:`POST/GET/DELETE /api/v1/devices`

- [ ] **Step 1: 写失败测试**(MockMvc;登录 helper 复用既有集成测试的写法)

```java
package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class DeviceApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private record Auth(String cookie, String csrf) {}

    private Auth login(String user, String pass) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return new Auth(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }

    @Test
    void createListAndRevokeDevice() throws Exception {
        var admin = login("admin", "admin-secret");
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"MacBook\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.device.platform").value("MAC"))
            .andReturn().getResponse().getContentAsString();
        String deviceId = JsonPath.read(body, "$.device.id");
        String token = JsonPath.read(body, "$.token");

        mvc.perform(get("/api/v1/devices").header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItem("MacBook")));

        // 列表不返回明文 token
        String list = mvc.perform(get("/api/v1/devices").header("Cookie", admin.cookie()))
            .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(token);

        mvc.perform(delete("/api/v1/devices/{id}", deviceId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    void invalidCreateInputIs400() throws Exception {
        var admin = login("admin", "admin-secret");
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"platform\":\"MAC\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/devices")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"platform\":\"LINUX\"}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 跑失败**

Run: `cd backend && "$MVN" -o -s ../.tools/maven-settings.xml -pl pnas-server test -Dtest='DeviceApiIntegrationTest'`
Expected: FAIL(404 或缺少 DeviceService)

- [ ] **Step 3: 实现**

`DeviceService.java`:

```java
package com.pnas.server.backup;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.backup.domain.DevicePlatform;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.Sha256;
import com.pnas.server.iam.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceService {

    public record Created(Device device, String token) {}

    private final DeviceRepository devices;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public DeviceService(DeviceRepository devices, UserRepository users) {
        this.devices = devices;
        this.users = users;
    }

    @Transactional
    public Created create(SessionPrincipal me, String name, DevicePlatform platform) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "设备名不能为空");
        }
        var user = users.findById(me.userId()).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在"));
        String token = randomToken();
        Device device = devices.save(Device.create(user, name.trim(), platform,
            Sha256.hex(token.getBytes(StandardCharsets.UTF_8))));
        return new Created(device, token);
    }

    @Transactional(readOnly = true)
    public List<Device> list(SessionPrincipal me) {
        return devices.findByUserIdOrderByCreatedAtDesc(me.userId());
    }

    @Transactional
    public void revoke(SessionPrincipal me, UUID deviceId) {
        Device device = devices.findByIdAndUserId(deviceId, me.userId())
            .orElseThrow(this::notFound);
        device.markRevoked();
        devices.save(device);
    }

    @Transactional(readOnly = true)
    public Optional<Device> resolveToken(String rawToken) {
        return devices.findByPatHashAndRevokedAtIsNull(
            Sha256.hex(rawToken.getBytes(StandardCharsets.UTF_8)));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "设备不存在");
    }
}
```

`DeviceController.java`:

```java
package com.pnas.server.backup;

import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.backup.domain.DevicePlatform;
import com.pnas.server.common.error.BusinessException;
import com.pnas.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    public record CreateDevice(String name, String platform) {}
    public record DeviceDto(UUID id, String name, String platform, Instant createdAt,
                            Instant lastSeenAt, boolean revoked) {}
    public record CreatedDto(DeviceDto device, String token) {}

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping
    public CreatedDto create(@AuthenticationPrincipal SessionPrincipal me, @RequestBody CreateDevice req) {
        DevicePlatform platform = parsePlatform(req.platform());
        var created = devices.create(me, req.name(), platform);
        return new CreatedDto(toDto(created.device()), created.token());
    }

    @GetMapping
    public List<DeviceDto> list(@AuthenticationPrincipal SessionPrincipal me) {
        return devices.list(me).stream().map(this::toDto).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id) {
        devices.revoke(me, id);
    }

    private DevicePlatform parsePlatform(String raw) {
        try {
            return DevicePlatform.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "platform 仅支持 MAC/WINDOWS/OTHER");
        }
    }

    private DeviceDto toDto(Device d) {
        return new DeviceDto(d.getId(), d.getName(), d.getPlatform().name(), d.getCreatedAt(),
            d.getLastSeenAt(), d.isRevoked());
    }
}
```

- [ ] **Step 4: 跑通过 + 全量**

先更新 `PackageArchitectureTest`:在 `controllers_only_in_allowed_packages` 的 `resideInAnyPackage(...)` 加入 `"com.pnas.server.backup.."`,并在 `domain_packages_do_not_depend_on_web` 的模块列表加入同一包(不得删除/放宽其它规则);否则本任务的控制器会使 ArchUnit 失败。
Run: `-Dtest='DeviceApiIntegrationTest,PackageArchitectureTest'`,再跑全量(126+ 绿)。

- [ ] **Step 5: 提交**

```bash
git add backend/pnas-server/src/main/java/com/pnas/server/backup/DeviceService.java \
        backend/pnas-server/src/main/java/com/pnas/server/backup/DeviceController.java \
        backend/pnas-server/src/test/java/com/pnas/server/backup/DeviceApiIntegrationTest.java
git commit -m "feat(m4a): 设备创建/列表/吊销 API 与令牌哈希"
```

---

### Task 3: Bearer 设备令牌鉴权

**Files:**
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/web/security/SessionAuthFilter.java`
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/web/security/SecurityConfig.java:25-45`
- Test: `backend/pnas-server/src/test/java/com/pnas/server/backup/BearerAuthIntegrationTest.java`

**Interfaces:**
- Consumes: `DeviceRepository`、`User.Status`(已存在枚举)、`SessionPrincipal`、`Sha256.hex`
- Produces: 任何 API 在 `Authorization: Bearer <token>` 下以设备所属用户身份鉴权;`req.setAttribute("pnas.deviceId", UUID)`;`lastSeenAt` 60s 节流更新

- [ ] **Step 1: 写失败测试**(真实 HTTP 头;MockMvc 可直接带 Authorization)

```java
package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BearerAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private String createToken() throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
            .andExpect(status().isOk()).andReturn();
        String cookie = login.getResponse().getHeader("Set-Cookie").split(";")[0];
        String csrf = login.getResponse().getHeader("X-CSRF-Token");
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", cookie).header("X-CSRF", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"BearTest\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    @Test
    void bearerTokenAuthenticatesWithoutCookieAndCsrf() throws Exception {
        String token = createToken();
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        // 写操作也不需要 CSRF(无 Cookie 时守卫放行)
        mvc.perform(post("/api/v1/nodes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":null,\"name\":\"bearer-dir-" + System.nanoTime() + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void revokedOrUnknownTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer not-a-token"))
            .andExpect(status().isUnauthorized());
        String token = createToken();
        var session = adminSession();
        var list = mvc.perform(get("/api/v1/devices")
                .header("Cookie", session.cookie()).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String deviceId = JsonPath.read(list, "$[0].id");
        mvc.perform(delete("/api/v1/devices/{id}", deviceId)
                .header("Cookie", session.cookie()).header("X-CSRF", session.csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    private record Session(String cookie, String csrf) {}

    private Session adminSession() throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
            .andExpect(status().isOk()).andReturn();
        return new Session(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }
}
```

- [ ] **Step 2: 跑失败**(Bearer 被忽略 → 401)

- [ ] **Step 3: 实现**

`SessionAuthFilter`:构造器加 `DeviceRepository devices`;`doFilterInternal` 开头:

```java
        String authorization = req.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String raw = authorization.substring("Bearer ".length()).trim();
            devices.findByPatHashAndRevokedAtIsNull(sha256(raw)).ifPresent(device -> {
                User user = device.getUser();
                if (user.getStatus() != User.Status.ACTIVE) {
                    return;
                }
                var principal = new SessionPrincipal(user.getId(), user.getUsername(),
                    user.getRole(), null);
                var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                req.setAttribute(SessionPrincipal.class.getName(), principal);
                req.setAttribute("pnas.deviceId", device.getId());
                touchThrottled(device);
            });
            chain.doFilter(req, res);
            return;
        }
```

`touchThrottled`:

```java
    private void touchThrottled(Device device) {
        Instant last = device.getLastSeenAt();
        if (last == null || last.isBefore(Instant.now().minusSeconds(60))) {
            device.touch();
            devices.save(device);
        }
    }
```

`SecurityConfig`:`chain(...)` 参数加 `DeviceRepository devices`;`new SessionAuthFilter(sessions, users, devices)`。

- [ ] **Step 4: 跑通过 + 全量**

Run: `-Dtest='BearerAuthIntegrationTest'` + 全量。

- [ ] **Step 5: 提交**

```bash
git add backend/pnas-server/src/main/java/com/pnas/server/web/security/SessionAuthFilter.java \
        backend/pnas-server/src/main/java/com/pnas/server/web/security/SecurityConfig.java \
        backend/pnas-server/src/test/java/com/pnas/server/backup/BearerAuthIntegrationTest.java
git commit -m "feat(m4a): Bearer 设备令牌鉴权(注入请求 deviceId)"
```

---

### Task 4: 运行记录服务与 API

**Files:**
- Create: `backend/pnas-server/src/main/java/com/pnas/server/backup/BackupRunService.java`
- Create: `backend/pnas-server/src/main/java/com/pnas/server/backup/BackupController.java`
- Test: `backend/pnas-server/src/test/java/com/pnas/server/backup/BackupRunApiIntegrationTest.java`

**Interfaces:**
- Consumes: `DeviceRepository.findByIdAndUserId`、`BackupRunRepository`、`BackupRun.start/finish`
- Produces: `BackupRunService.start(me, deviceId, mode) : BackupRun`、`finish(me, runId, Finish)`、`list(me, deviceId, limit)`;HTTP `POST/PATCH/GET /api/v1/backup/runs`;`record Finish(RunStatus status,int filesSeen,int filesUploaded,int filesSkipped,int filesFailed,long bytesUploaded,String error)`

- [ ] **Step 1: 写失败测试**

```java
package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BackupRunApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private record Auth(String cookie, String csrf) {}

    private Auth login(String user, String pass) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return new Auth(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }

    private String deviceId(Auth auth, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", auth.cookie()).header("X-CSRF", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.device.id");
    }

    @Test
    void runLifecycleFromRunningToSuccess() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run-" + System.nanoTime());

        String started = mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"mode\":\"MANUAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(started, "$.id");

        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"SUCCESS","filesSeen":10,"filesUploaded":3,"filesSkipped":7,
                     "filesFailed":0,"bytesUploaded":1234}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.filesUploaded").value(3));

        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId)
                .header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(runId))
            .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    void finishingTwiceOrByAnotherUserFails() throws Exception {
        var admin = login("admin", "admin-secret");
        String deviceId = deviceId(admin, "run2-" + System.nanoTime());
        String body = mvc.perform(post("/api/v1/backup/runs")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"mode\":\"MANUAL\"}"))
            .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.id");

        String finish = "{\"status\":\"SUCCESS\",\"filesSeen\":1,\"filesUploaded\":1,"
            + "\"filesSkipped\":0,\"filesFailed\":0,\"bytesUploaded\":1}";
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(finish))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/backup/runs/{id}", runId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(finish))
            .andExpect(status().isBadRequest());   // 已结束

        // 成员拿管理员 run：404(不泄露)
        String member = "runmember-" + System.nanoTime() % 100000;
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + member + "\",\"displayName\":\"M\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        var m = login(member, "secret123");
        mvc.perform(get("/api/v1/backup/runs").param("deviceId", deviceId)
                .header("Cookie", m.cookie()))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 跑失败** → **Step 3: 实现**

`BackupRunService.java`:

```java
package com.pnas.server.backup;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.*;
import com.pnas.server.common.error.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BackupRunService {

    public record Finish(RunStatus status, int filesSeen, int filesUploaded, int filesSkipped,
                         int filesFailed, long bytesUploaded, String error) {}

    private final BackupRunRepository runs;
    private final DeviceRepository devices;

    public BackupRunService(BackupRunRepository runs, DeviceRepository devices) {
        this.runs = runs;
        this.devices = devices;
    }

    @Transactional
    public BackupRun start(SessionPrincipal me, UUID deviceId, RunMode mode) {
        Device device = devices.findByIdAndUserId(deviceId, me.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "设备不存在"));
        if (mode == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "mode 不能为空");
        }
        return runs.save(BackupRun.start(device, device.getUser(), mode));
    }

    @Transactional
    public BackupRun finish(SessionPrincipal me, UUID runId, Finish f) {
        BackupRun run = runs.findById(runId)
            .filter(r -> r.getUser().getId().equals(me.userId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "运行记录不存在"));
        if (run.getStatus() != RunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "运行已结束,不能重复提交");
        }
        if (f.status() == null || f.status() == RunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "status 必须是 SUCCESS/PARTIAL/FAILED");
        }
        run.finish(f.status(), f.filesSeen(), f.filesUploaded(), f.filesSkipped(),
            f.filesFailed(), f.bytesUploaded(), f.error());
        return runs.save(run);
    }

    @Transactional(readOnly = true)
    public List<BackupRun> list(SessionPrincipal me, UUID deviceId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        if (deviceId != null) {
            devices.findByIdAndUserId(deviceId, me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                    "设备不存在"));
            return runs.findByDeviceIdOrderByStartedAtDesc(deviceId, PageRequest.of(0, cap));
        }
        return runs.findByUserIdOrderByStartedAtDesc(me.userId(), PageRequest.of(0, cap));
    }
}
```

`BackupController.java`:

```java
package com.pnas.server.backup;

import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.BackupRun;
import com.pnas.server.backup.domain.RunMode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    private final BackupRunService runs;

    public BackupController(BackupRunService runs) {
        this.runs = runs;
    }

    public record StartRun(UUID deviceId, RunMode mode) {}

    @PostMapping("/runs")
    public RunDto start(@AuthenticationPrincipal SessionPrincipal me, @RequestBody StartRun req) {
        return toDto(runs.start(me, req.deviceId(), req.mode()));
    }

    @PatchMapping("/runs/{id}")
    public RunDto finish(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id,
                         @RequestBody BackupRunService.Finish finish) {
        return toDto(runs.finish(me, id, finish));
    }

    @GetMapping("/runs")
    public List<RunDto> list(@AuthenticationPrincipal SessionPrincipal me,
                             @RequestParam(required = false) UUID deviceId,
                             @RequestParam(defaultValue = "50") int limit) {
        return runs.list(me, deviceId, limit).stream().map(this::toDto).toList();
    }

    public record RunDto(UUID id, UUID deviceId, String mode, String status,
                         java.time.Instant startedAt, java.time.Instant finishedAt,
                         int filesSeen, int filesUploaded, int filesSkipped, int filesFailed,
                         long bytesUploaded, String error) {}

    private RunDto toDto(BackupRun r) {
        return new RunDto(r.getId(), r.getDevice().getId(), r.getMode().name(), r.getStatus().name(),
            r.getStartedAt(), r.getFinishedAt(), r.getFilesSeen(), r.getFilesUploaded(),
            r.getFilesSkipped(), r.getFilesFailed(), r.getBytesUploaded(), r.getError());
    }
}
```

- [ ] **Step 4: 跑通过 + 全量** → **Step 5: 提交**(`feat(m4a): 备份运行记录 API`)

---

### Task 5: 上传协议携带 sourceMtime

**Files:**
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/files/UploadService.java`
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/files/UploadController.java`
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/files/domain/UploadSession.java`(已有 sourceMtime 字段)
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/files/domain/FileVersion.java`(已有 sourceMtime 字段)
- Test: `backend/pnas-server/src/test/java/com/pnas/server/files/SourceMtimeUploadTest.java`

**Interfaces:**
- Produces: `UploadService.start(User, UUID parentId, String name, long size, Instant sourceMtime) : UUID`;`complete` 后 `file_versions.source_mtime` = 启动时值;`UploadController.StartUpload(destParentId, filename, size, Instant sourceMtime)`(可为 null)

- [ ] **Step 1: 写失败测试**(服务层)

```java
package com.pnas.server.files;

import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import com.pnas.server.files.domain.FileVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SourceMtimeUploadTest extends AbstractIntegrationTest {

    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired FileVersionRepository versions;
    @Autowired UserRepository users;

    private FileVersion upload(String name, byte[] content, Instant mtime) {
        User admin = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(admin);
        UUID uploadId = mtime == null
            ? uploads.start(admin, home.getId(), name, content.length)
            : uploads.start(admin, home.getId(), name, content.length, mtime);
        uploads.acceptChunk(admin.getId(), uploadId, 0, new ByteArrayInputStream(content),
            Sha256.hex(content));
        var done = uploads.complete(admin.getId(), uploadId);
        return versions.findTopByNodeIdOrderByVersionNoDesc(done.nodeId()).orElseThrow();
    }

    @Test
    void sourceMtimeIsPersistedWhenProvided() {
        Instant mtime = Instant.parse("2026-10-01T08:00:00Z");
        var version = upload("mtime-" + UUID.randomUUID() + ".txt",
            "hello".getBytes(StandardCharsets.UTF_8), mtime);
        assertThat(version.getSourceMtime()).isEqualTo(mtime);
    }

    @Test
    void sourceMtimeIsNullForLegacyClients() {
        var version = upload("legacy-" + UUID.randomUUID() + ".txt",
            "hello".getBytes(StandardCharsets.UTF_8), null);
        assertThat(version.getSourceMtime()).isNull();
    }
}
```

- [ ] **Step 2: 跑失败** → **Step 3: 实现**

`UploadService`:

```java
    public UUID start(User owner, UUID destParentId, String filename, long totalSize) {
        return start(owner, destParentId, filename, totalSize, null);
    }

    @Transactional
    public UUID start(User owner, UUID destParentId, String filename, long totalSize,
                      Instant sourceMtime) {
        // ...原校验与构造不变...
        s.setSourceMtime(sourceMtime);
        // ...
    }
```

`complete` 中创建 `FileVersion` 的位置:`fv.setSourceMtime(s.getSourceMtime());`

`UploadController`:

```java
    public record StartUpload(UUID destParentId, String filename, long size,
                              java.time.Instant sourceMtime) {}
    // ...
    UUID id = uploads.start(owner, req.destParentId(), req.filename(), req.size(), req.sourceMtime());
```

> 若 `UploadSession` 尚无 `sourceMtime` 的 getter/setter,由 Lombok `@Getter/@Setter` 提供(该实体已是 `@Getter @Setter` 风格);无则补上。

- [ ] **Step 4: 跑通过 + 全量** → **Step 5: 提交**(`feat(m4a): 上传携带 sourceMtime 并写入版本`)

---

### Task 6: 备份清单接口(Manifest)

**Files:**
- Create: `backend/pnas-server/src/main/java/com/pnas/server/backup/ManifestService.java`
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/backup/BackupController.java`(加 `/manifest`)
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/files/VersionChunkRepository.java`(首/末块查询)
- Test: `backend/pnas-server/src/test/java/com/pnas/server/backup/ManifestApiIntegrationTest.java`

**Interfaces:**
- Consumes: `FilesService.homeOf(me)`、`FilesService.createDir(me, parentId, name)`、`NodeRepository`、`FileVersionRepository.findTopByNodeIdOrderByVersionNoDesc`、`VersionChunkRepository`
- Produces: `ManifestService.page(me, deviceId, cursor, limit) : Page(UUID rootNodeId, List<Entry> items, String nextCursor)`;`Entry(String relPath, UUID nodeId, long size, Instant sourceMtime, String firstChunkSha, String lastChunkSha, String manifestSha256, int versionNo)`;HTTP `GET /api/v1/backup/manifest?deviceId=&cursor=&limit=`

- [ ] **Step 1: 写失败测试**

```java
package com.pnas.server.backup;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.files.FilesService;
import com.pnas.server.files.UploadService;
import com.pnas.server.files.domain.Node;
import com.pnas.server.files.NodeRepository;
import com.pnas.server.iam.UserRepository;
import com.pnas.server.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ManifestApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UploadService uploads;
    @Autowired FilesService files;
    @Autowired NodeRepository nodes;
    @Autowired UserRepository users;

    private record Ctx(String deviceId, String token) {}

    private Ctx createDevice() throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
            .andReturn();
        String cookie = login.getResponse().getHeader("Set-Cookie").split(";")[0];
        String csrf = login.getResponse().getHeader("X-CSRF-Token");
        String body = mvc.perform(post("/api/v1/devices")
                .header("Cookie", cookie).header("X-CSRF", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"man-" + UUID.randomUUID() + "\",\"platform\":\"MAC\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(JsonPath.read(body, "$.device.id"), JsonPath.read(body, "$.token"));
    }

    private String manifest(String token, String deviceId, String cursor, int limit) throws Exception {
        var req = get("/api/v1/backup/manifest").param("deviceId", deviceId).param("limit", String.valueOf(limit))
            .header("Authorization", "Bearer " + token);
        if (cursor != null) req = req.param("cursor", cursor);
        return mvc.perform(req).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void manifestIsSortedPagedAndCarriesChunkHashes() throws Exception {
        Ctx ctx = createDevice();
        // 首次调用创建备份根并返回 rootNodeId(空清单)
        String first = manifest(ctx.token(), ctx.deviceId(), null, 50);
        String rootId = JsonPath.read(first, "$.rootNodeId");
        assertThat(JsonPath.<List<?>>read(first, "$.items")).isEmpty();

        User admin = users.findByUsername("admin").orElseThrow();
        var me = new com.pnas.server.auth.SessionPrincipal(admin.getId(), admin.getUsername(),
            admin.getRole(), UUID.randomUUID());
        Node root = nodes.findById(UUID.fromString(rootId)).orElseThrow();
        Node sub = files.createDir(me, root.getId(), "sub");

        byte[] a = "aaaa".getBytes(StandardCharsets.UTF_8);
        byte[] b = "bbbbbb".getBytes(StandardCharsets.UTF_8);
        Instant mtime = Instant.parse("2026-10-02T09:30:00Z");
        for (var item : List.of(new Object[]{root, "b.txt", b, mtime},
                                new Object[]{root, "a.txt", a, mtime},
                                new Object[]{sub, "c.txt", a, mtime})) {
            var dir = (Node) item[0];
            byte[] content = (byte[]) item[2];
            UUID up = uploads.start(admin, dir.getId(), (String) item[1], content.length, (Instant) item[3]);
            uploads.acceptChunk(admin.getId(), up, 0, new ByteArrayInputStream(content), Sha256.hex(content));
            uploads.complete(admin.getId(), up);
        }

        String page1 = manifest(ctx.token(), ctx.deviceId(), null, 2);
        List<String> paths1 = JsonPath.read(page1, "$.items[*].relPath");
        assertThat(paths1).containsExactly("a.txt", "b.txt");
        String cursor = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor).isEqualTo("b.txt");

        String page2 = manifest(ctx.token(), ctx.deviceId(), cursor, 2);
        List<String> paths2 = JsonPath.read(page2, "$.items[*].relPath");
        assertThat(paths2).containsExactly("sub/c.txt");

        assertThat(JsonPath.<String>read(page1, "$.items[0].sourceMtime"))
            .startsWith("2026-10-02T09:30:00");
        assertThat(JsonPath.<String>read(page1, "$.items[0].firstChunkSha"))
            .isEqualTo(Sha256.hex(a));
        assertThat(JsonPath.<String>read(page1, "$.items[0].lastChunkSha")).isNotNull();
        assertThat(JsonPath.<Integer>read(page1, "$.items[0].versionNo")).isEqualTo(1);
    }

    @Test
    void otherUsersDeviceIs404() throws Exception {
        Ctx ctx = createDevice();
        String member = "man-u-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", adminCookie()).header("X-CSRF", adminCsrf())
                .content("{\"username\":\"" + member + "\",\"displayName\":\"M\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        var mlogin = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + member + "\",\"password\":\"secret123\"}"))
            .andReturn();
        String cookie = mlogin.getResponse().getHeader("Set-Cookie").split(";")[0];
        mvc.perform(get("/api/v1/backup/manifest").param("deviceId", ctx.deviceId())
                .header("Cookie", cookie))
            .andExpect(status().isNotFound());
    }

    private String adminCookie() throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
            .andReturn().getResponse().getHeader("Set-Cookie").split(";")[0];
    }

    private String adminCsrf() throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
            .andReturn().getResponse().getHeader("X-CSRF-Token");
    }
}
```

> 测试里的 `firstChunkSha` 断言写法按实际字段名调整:对 4 字节文件首块即整块,`firstChunkSha` 应等于 `Sha256.hex(content)`;不要用 `substring` 凑。

- [ ] **Step 2: 跑失败** → **Step 3: 实现**

`VersionChunkRepository` 追加:

```java
    Optional<VersionChunk> findFirstByVersionIdOrderBySeqAsc(UUID versionId);
    Optional<VersionChunk> findFirstByVersionIdOrderBySeqDesc(UUID versionId);
```

`ManifestService.java`:

```java
package com.pnas.server.backup;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.files.*;
import com.pnas.server.files.domain.FileVersion;
import com.pnas.server.files.domain.Node;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ManifestService {

    public record Entry(String relPath, UUID nodeId, long size, Instant sourceMtime,
                        String firstChunkSha, String lastChunkSha, String manifestSha256,
                        int versionNo) {}
    public record Page(UUID rootNodeId, List<Entry> items, String nextCursor) {}

    private final DeviceRepository devices;
    private final FilesService files;
    private final NodeRepository nodes;
    private final FileVersionRepository versions;
    private final VersionChunkRepository chunks;

    public ManifestService(DeviceRepository devices, FilesService files, NodeRepository nodes,
                           FileVersionRepository versions, VersionChunkRepository chunks) {
        this.devices = devices;
        this.files = files;
        this.nodes = nodes;
        this.versions = versions;
        this.chunks = chunks;
    }

    @Transactional
    public Page page(SessionPrincipal me, UUID deviceId, String cursor, int limit) {
        Device device = devices.findByIdAndUserId(deviceId, me.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND,
                "设备不存在"));
        int cap = Math.min(Math.max(limit, 1), 200);

        Node backup = ensureDir(me, files.homeOf(me), "自动备份");
        Node root = ensureDir(me, backup, device.getId().toString());

        List<Entry> all = new ArrayList<>();
        walk(root, "", all);
        all.sort(Comparator.comparing(Entry::relPath));
        if (cursor != null && !cursor.isBlank()) {
            all = all.stream().filter(e -> e.relPath().compareTo(cursor) > 0).toList();
        }
        List<Entry> page = all.stream().limit(cap).toList();
        String next = all.size() > cap ? page.get(page.size() - 1).relPath() : null;
        return new Page(root.getId(), page, next);
    }

    private Node ensureDir(SessionPrincipal me, Node parent, String name) {
        return nodes.findByParentIdAndNameAndTrashedAtIsNull(parent.getId(), name)
            .orElseGet(() -> nodes.save(Node.dir(files.homeOf(me).getOwner(), parent, name)));
    }

    private void walk(Node dir, String prefix, List<Entry> out) {
        for (Node child : nodes.findByParentIdAndTrashedAtIsNullOrderByNameAsc(dir.getId())) {
            String rel = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.getKind() == Node.Kind.DIR) {
                walk(child, rel, out);
            } else {
                var version = versions.findTopByNodeIdOrderByVersionNoDesc(child.getId()).orElse(null);
                if (version == null) {
                    continue;
                }
                String first = chunks.findFirstByVersionIdOrderBySeqAsc(version.getId())
                    .map(c -> c.getBlobHash()).orElse(null);
                String last = chunks.findFirstByVersionIdOrderBySeqDesc(version.getId())
                    .map(c -> c.getBlobHash()).orElse(null);
                out.add(new Entry(rel, child.getId(), version.getSizeBytes(),
                    version.getSourceMtime(), first, last, version.getManifestSha256(),
                    version.getVersionNo()));
            }
        }
    }
}
```

`BackupController` 增加:

```java
    @GetMapping("/manifest")
    public ManifestService.Page manifest(@AuthenticationPrincipal SessionPrincipal me,
                                         @RequestParam UUID deviceId,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "200") int limit) {
        return manifests.page(me, deviceId, cursor, limit);
    }
```
(构造器注入 `ManifestService manifests`。)

- [ ] **Step 4: 跑通过 + 全量** → **Step 5: 提交**(`feat(m4a): 备份清单接口(首末块/分页/根目录幂等)`)

---

### Task 7: Agent 模块骨架与配置

**Files:**
- Create: `agent/pom.xml`
- Create: `agent/src/main/java/com/pnas/agent/config/AgentConfig.java`
- Create: `agent/src/main/java/com/pnas/agent/Main.java`(仅参数解析与退出码框架)
- Test: `agent/src/test/java/com/pnas/agent/ConfigTest.java`

**Interfaces:**
- Produces: `AgentConfig(String serverUrl, String token, String deviceId, List<Root> roots, int intervalMinutes, boolean realtime, List<String> exclude, int maxRetries, long retryBackoffMs)` + `record Root(String path, String remoteName)`;`AgentConfig.load(Path)`、`save(Path)`、`validate()`;`Main.main(String[])` 退出码语义(0/1/2)

- [ ] **Step 1: pom(独立模块)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.pnas</groupId>
    <artifactId>pnas-agent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <jackson.version>2.17.2</jackson.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.5</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.2</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.pnas.agent.Main</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 写失败测试**

```java
package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {

    @TempDir Path dir;

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        var cfg = new AgentConfig("http://nas.local:8080", "tok", "dev-1",
            List.of(new AgentConfig.Root("/tmp/a", "a")), 360, true,
            List.of("**/.git/**"), 3, 500L);
        Path file = dir.resolve("config.json");
        cfg.save(file);
        AgentConfig loaded = AgentConfig.load(file);
        assertThat(loaded).isEqualTo(cfg);
    }

    @Test
    void validateRejectsMissingFields() {
        assertThatThrownBy(() -> new AgentConfig("", "t", "d",
            List.of(new AgentConfig.Root("/tmp/a", "a")), 60, false, List.of(), 3, 100L).validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serverUrl");
        assertThatThrownBy(() -> new AgentConfig("http://x", "", "d",
            List.of(new AgentConfig.Root("/tmp/a", "a")), 60, false, List.of(), 3, 100L).validate())
            .hasMessageContaining("token");
        assertThatThrownBy(() -> new AgentConfig("http://x", "t", "d",
            List.of(), 60, false, List.of(), 3, 100L).validate())
            .hasMessageContaining("roots");
    }
}
```

- [ ] **Step 3: 跑失败** → **Step 4: 实现**

`AgentConfig.java`:

```java
package com.pnas.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(String serverUrl, String token, String deviceId, List<Root> roots,
                          int intervalMinutes, boolean realtime, List<String> exclude,
                          int maxRetries, long retryBackoffMs) {

    public record Root(String path, String remoteName) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AgentConfig load(Path file) {
        try {
            return MAPPER.readValue(Files.readAllBytes(file), AgentConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置失败: " + file, e);
        }
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("写入配置失败: " + file, e);
        }
    }

    public void validate() {
        require(serverUrl != null && !serverUrl.isBlank(), "serverUrl 不能为空");
        require(token != null && !token.isBlank(), "token 不能为空");
        require(deviceId != null && !deviceId.isBlank(), "deviceId 不能为空");
        require(roots != null && !roots.isEmpty(), "roots 不能为空");
        require(intervalMinutes > 0, "intervalMinutes 必须为正数");
        require(retryBackoffMs >= 0, "retryBackoffMs 不能为负");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
```

`Main.java`(框架,后续任务补子命令):

```java
package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;

import java.nio.file.Path;

/** pnas-agent 入口:退出码 0=成功,1=部分失败,2=配置/鉴权/致命错误。 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try {
            int code = run(args);
            System.exit(code);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println("用法: init|run|backup|restore|verify|status ...");
            return 2;
        }
        return switch (args[0]) {
            case "init" -> 0;
            case "status" -> 0;
            default -> throw new IllegalArgumentException("未知子命令: " + args[0]);
        };
    }
}
```

- [ ] **Step 5: 跑通过**(`cd agent && "$MVN" -o -s ../.tools/maven-settings.xml test`)
- [ ] **Step 6: 提交**(`feat(m4a): Agent 模块骨架与配置`)

---

### Task 8: 扫描、首末块哈希与增量决策

**Files:**
- Create: `agent/src/main/java/com/pnas/agent/model/FileItem.java`
- Create: `agent/src/main/java/com/pnas/agent/model/ManifestEntry.java`
- Create: `agent/src/main/java/com/pnas/agent/scan/ChunkHashes.java`
- Create: `agent/src/main/java/com/pnas/agent/scan/Scanner.java`
- Create: `agent/src/main/java/com/pnas/agent/plan/Planner.java`
- Test: `agent/src/test/java/com/pnas/agent/ScannerTest.java`、`.../ChunkHashesTest.java`、`.../PlannerTest.java`

**Interfaces:**
- Produces: `FileItem(String relPath, Path path, long size, Instant mtime, String firstChunkSha, String lastChunkSha)`;`ManifestEntry(String relPath, UUID nodeId, long size, Instant sourceMtime, String firstChunkSha, String lastChunkSha, int versionNo)`;`Scanner.scan(Path root, String remoteName, List<String> excludes, int chunkSize) : List<FileItem>`;`ChunkHashes.firstAndLast(Path file, int chunkSize) : String[]`(空文件为 `{null,null}`);`Planner.decide(FileItem, ManifestEntry) : Decision(boolean upload, String reason)`

- [ ] **Step 1: 失败测试**

```java
package com.pnas.agent;

import com.pnas.agent.model.FileItem;
import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.plan.Planner;
import com.pnas.agent.scan.ChunkHashes;
import com.pnas.agent.scan.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScannerTest {

    private static final int CHUNK = 4 * 1024 * 1024;

    @TempDir Path dir;

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void scansRelativePathsAndSkipsExcludedAndSymlinks() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "aaa");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/b.txt"), "bbb");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/ignored.txt"), "x");
        var link = dir.resolve("link.txt");
        try { Files.createSymbolicLink(link, dir.resolve("a.txt")); } catch (Exception ignored) { }

        var items = new Scanner().scan(dir, "root", List.of("**/.git/**"), CHUNK);

        assertThat(items).extracting(FileItem::relPath)
            .contains("root/a.txt", "root/sub/b.txt")
            .doesNotContain("root/.git/ignored.txt");
        assertThat(items).extracting(FileItem::relPath).doesNotContain("root/link.txt");
        FileItem a = items.stream().filter(i -> i.relPath().endsWith("a.txt")).findFirst().orElseThrow();
        assertThat(a.firstChunkSha()).isEqualTo(sha("aaa".getBytes()));
    }

    @Test
    void chunkHashesOfEmptyFileAreNull() throws Exception {
        Path empty = dir.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        String[] hashes = ChunkHashes.firstAndLast(empty, CHUNK);
        assertThat(hashes[0]).isNull();
        assertThat(hashes[1]).isNull();
    }

    @Test
    void chunkHashesUseFirstAndLastChunk() throws Exception {
        byte[] content = new byte[CHUNK + 10];
        java.util.Arrays.fill(content, (byte) 7);
        Path file = dir.resolve("big.bin");
        Files.write(file, content);
        String[] hashes = ChunkHashes.firstAndLast(file, CHUNK);
        assertThat(hashes[0]).isEqualTo(sha(java.util.Arrays.copyOfRange(content, 0, CHUNK)));
        assertThat(hashes[1]).isEqualTo(sha(java.util.Arrays.copyOfRange(content, CHUNK, content.length)));
    }
}

class PlannerTest {

    private final Planner planner = new Planner();

    private FileItem local(long size, Instant mtime, String first, String last) {
        return new FileItem("root/a.txt", Path.of("/tmp/a.txt"), size, mtime, first, last);
    }

    private ManifestEntry remote(long size, Instant mtime, String first, String last) {
        return new ManifestEntry("root/a.txt", UUID.randomUUID(), size, mtime, first, last, 1);
    }

    @Test
    void skipsWhenSizeMtimeAndBothHashesMatch() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(planner.decide(local(10, t, "f", "l"), remote(10, t, "f", "l")).upload()).isFalse();
    }

    @Test
    void uploadsWhenAnyDimensionDiffers() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(planner.decide(local(11, t, "f", "l"), remote(10, t, "f", "l")).upload()).isTrue();
        assertThat(planner.decide(local(10, t.plusSeconds(1), "f", "l"), remote(10, t, "f", "l")).upload()).isTrue();
        assertThat(planner.decide(local(10, t, "x", "l"), remote(10, t, "f", "l")).upload()).isTrue();
        assertThat(planner.decide(local(10, t, "f", "x"), remote(10, t, "f", "l")).upload()).isTrue();
    }

    @Test
    void uploadsWhenRemoteMissingOrMtimeUnknown() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(planner.decide(local(10, t, "f", "l"), null).upload()).isTrue();
        assertThat(planner.decide(local(10, t, "f", "l"), remote(10, null, "f", "l")).upload()).isTrue();
    }
}
```

- [ ] **Step 2: 跑失败** → **Step 3: 实现**

`ChunkHashes.java`:

```java
package com.pnas.agent.scan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

public final class ChunkHashes {

    private ChunkHashes() {}

    /** 返回 [首块 sha256, 末块 sha256];空文件为 {null, null}。 */
    public static String[] firstAndLast(Path file, int chunkSize) throws IOException {
        long size = java.nio.file.Files.size(file);
        if (size == 0) {
            return new String[]{null, null};
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            int firstLen = (int) Math.min(chunkSize, size);
            String first = sha(ch, 0, firstLen);
            if (size <= chunkSize) {
                return new String[]{first, first};
            }
            long lastStart = size - Math.min(chunkSize, size);
            return new String[]{first, sha(ch, lastStart, (int) (size - lastStart))};
        }
    }

    private static String sha(FileChannel ch, long offset, int length) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer buf = ByteBuffer.allocate(Math.min(length, 1 << 16));
            long pos = offset;
            int remaining = length;
            while (remaining > 0) {
                buf.clear();
                buf.limit(Math.min(buf.capacity(), remaining));
                int read = ch.read(buf, pos);
                if (read <= 0) {
                    break;
                }
                buf.flip();
                md.update(buf);
                pos += read;
                remaining -= read;
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`FileItem.java`:

```java
package com.pnas.agent.model;

import java.nio.file.Path;
import java.time.Instant;

public record FileItem(String relPath, Path path, long size, Instant mtime,
                       String firstChunkSha, String lastChunkSha) {}
```

`ManifestEntry.java`:

```java
package com.pnas.agent.model;

import java.time.Instant;
import java.util.UUID;

public record ManifestEntry(String relPath, UUID nodeId, long size, Instant sourceMtime,
                            String firstChunkSha, String lastChunkSha, String manifestSha256,
                            int versionNo) {}
```

`Scanner.java`:

```java
package com.pnas.agent.scan;

import com.pnas.agent.model.FileItem;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Scanner {

    public List<FileItem> scan(Path root, String remoteName, List<String> excludes, int chunkSize)
            throws IOException {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String g : excludes == null ? List.<String>of() : excludes) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        List<FileItem> items = new ArrayList<>();
        Files.walkFileTree(root, java.util.Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = root.relativize(dir).toString().replace('\\', '/');
                for (PathMatcher m : matchers) {
                    if (m.matches(Path.of(rel)) || m.matches(Path.of(rel + "/"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String relUnderRoot = root.relativize(file).toString().replace('\\', '/');
                String rel = remoteName == null || remoteName.isBlank()
                    ? relUnderRoot : remoteName + "/" + relUnderRoot;
                for (PathMatcher m : matchers) {
                    if (m.matches(Path.of(relUnderRoot))) {
                        return FileVisitResult.CONTINUE;
                    }
                }
                String[] hashes = ChunkHashes.firstAndLast(file, chunkSize);
                items.add(new FileItem(rel, file, attrs.size(),
                    attrs.lastModifiedTime().toInstant(), hashes[0], hashes[1]));
                return FileVisitResult.CONTINUE;
            }
        });
        return items;
    }
}
```

`Planner.java`:

```java
package com.pnas.agent.plan;

import com.pnas.agent.model.FileItem;
import com.pnas.agent.model.ManifestEntry;

public final class Planner {

    public record Decision(boolean upload, String reason) {}

    public Decision decide(FileItem local, ManifestEntry remote) {
        if (remote == null) {
            return new Decision(true, "remote-missing");
        }
        if (local.size() != remote.size()) {
            return new Decision(true, "size-changed");
        }
        if (remote.sourceMtime() != null && local.mtime() != null
            && local.mtime().toEpochMilli() != remote.sourceMtime().toEpochMilli()) {
            return new Decision(true, "mtime-changed");
        }
        if (!java.util.Objects.equals(local.firstChunkSha(), remote.firstChunkSha())
            || !java.util.Objects.equals(local.lastChunkSha(), remote.lastChunkSha())) {
            return new Decision(true, "chunk-changed");
        }
        return new Decision(false, "unchanged");
    }
}
```

> 说明:`mtime` 比较用 `equals` 依赖文件系统精度(纳秒/毫秒);若出现反复上传,执行者应在测得的精度上截断(如 `toMillis`)并在报告中说明。

- [ ] **Step 4: 跑通过**(`-Dtest='ScannerTest,PlannerTest'` + 全量)
- [ ] **Step 5: 提交**(`feat(m4a): Agent 扫描/首末块哈希/增量决策`)

---

### Task 9: Agent 远程客户端(HTTP + Jackson)

**Files:**
- Create: `agent/src/main/java/com/pnas/agent/remote/HttpJson.java`
- Create: `agent/src/main/java/com/pnas/agent/remote/AgentException.java`
- Create: `agent/src/main/java/com/pnas/agent/remote/ManifestClient.java`
- Create: `agent/src/main/java/com/pnas/agent/remote/RunClient.java`
- Create: `agent/src/main/java/com/pnas/agent/remote/UploadClient.java`
- Create: `agent/src/main/java/com/pnas/agent/remote/NodeClient.java`
- Create: `agent/src/test/java/com/pnas/agent/StubServer.java`(JDK `HttpServer` 测试桩)
- Test: `agent/src/test/java/com/pnas/agent/RemoteClientsTest.java`

**Interfaces:**
- Produces:
  - `HttpJson(String baseUrl, String token)`:`<T> T get(String path, Class<T>)`、`<T> T post(String path, Object body, Class<T>)`、`<T> T patch(String path, Object body, Class<T>)`、`byte[] getBytes(String path)`、`void putBytes(String path, byte[] body, String sha256)`
  - `ManifestClient.page(String deviceId, String cursor, int limit) : Page(UUID rootNodeId, List<ManifestEntry> items, String nextCursor)` 与 `all(String deviceId) : List<ManifestEntry>`(自动翻页)
  - `RunClient.start(String deviceId, String mode) : UUID`、`finish(UUID runId, Finish finish)`;`record Finish(String status,int filesSeen,int filesUploaded,int filesSkipped,int filesFailed,long bytesUploaded,String error)`
  - `UploadClient.start(UUID parentId, String name, long size, Instant sourceMtime) : UUID`、`putChunk(UUID uploadId,int seq,byte[])`、`complete(UUID uploadId) : UUID`
  - `NodeClient.ensureDir(UUID rootNodeId, String relDir) : UUID`、`listChildren(UUID parentId) : List<NodeDto>`、`download(UUID nodeId) : byte[]`;`record NodeDto(UUID id, String name, String kind)`

- [ ] **Step 1: 测试桩(先写,给后续任务复用)**

`StubServer.java`(最小内存实现,覆盖客户端契约):

```java
package com.pnas.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用内存服务:节点树/清单/上传会话/运行记录。 */
public final class StubServer implements AutoCloseable {

    public final HttpServer server;
    public final List<String> uploadedPaths = Collections.synchronizedList(new ArrayList<>());
    public final List<String> finishedRuns = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger chunkPuts = new AtomicInteger();
    private final Map<UUID, Dir> dirs = new HashMap<>();
    private final Map<UUID, List<FileDto>> files = new HashMap<>();
    private final Map<UUID, Upload> uploads = new HashMap<>();
    public String revokedToken = null;   // 非空时任何请求 401

    static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    record Dir(UUID id, UUID parent, String name) {}
    record FileDto(UUID id, String name, long size, String firstSha, String lastSha,
                   String sourceMtime, String manifestSha, int version) {}
    record Upload(UUID id, UUID parent, String name, long size, String sourceMtime,
                  java.io.ByteArrayOutputStream bytes) {}

    public StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || auth.equals("Bearer " + revokedToken)) {
            send(ex, 401, "{\"code\":\"unauthorized\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        if (path.equals("/api/v1/nodes") && method.equals("GET")) {
            UUID parent = UUID.fromString(param(query, "parentId"));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Dir d : dirs.values()) {
                if (Objects.equals(d.parent(), parent)) {
                    out.add(Map.of("id", d.id().toString(), "name", d.name(), "kind", "DIR"));
                }
            }
            for (FileDto f : files.getOrDefault(parent, List.of())) {
                out.add(Map.of("id", f.id().toString(), "name", f.name(), "kind", "FILE",
                    "sizeBytes", f.size()));
            }
            send(ex, 200, Json.write(out));
        } else if (path.equals("/api/v1/nodes") && method.equals("POST")) {
            var body = Json.read(ex.getRequestBody().readAllBytes());
            UUID parent = UUID.fromString((String) body.get("parentId"));
            String name = (String) body.get("name");
            boolean exists = dirs.values().stream().anyMatch(d -> Objects.equals(d.parent(), parent)
                && d.name().equals(name));
            UUID id = exists ? dirs.values().stream().filter(d -> Objects.equals(d.parent(), parent)
                && d.name().equals(name)).findFirst().orElseThrow().id() : UUID.randomUUID();
            if (!exists) {
                dirs.put(id, new Dir(id, parent, name));
            }
            send(ex, 200, Json.write(Map.of("id", id.toString(), "name", name, "kind", "DIR")));
        } else if (path.equals("/api/v1/backup/manifest") && method.equals("GET")) {
            String cursor = param(query, "cursor");
            SendManifest(ex);
        } else if (path.equals("/api/v1/backup/runs") && method.equals("POST")) {
            send(ex, 200, Json.write(Map.of("id", UUID.randomUUID().toString(), "status", "RUNNING")));
        } else if (path.startsWith("/api/v1/backup/runs/") && method.equals("PATCH")) {
            finishedRuns.add(path.substring(path.lastIndexOf('/') + 1));
            send(ex, 200, "{}");
        } else if (path.equals("/api/v1/uploads") && method.equals("POST")) {
            var body = Json.read(ex.getRequestBody().readAllBytes());
            UUID id = UUID.randomUUID();
            uploads.put(id, new Upload(id, UUID.fromString((String) body.get("destParentId")),
                (String) body.get("filename"), ((Number) body.get("size")).longValue(),
                (String) body.get("sourceMtime"), new java.io.ByteArrayOutputStream()));
            send(ex, 200, Json.write(Map.of("uploadId", id.toString())));
        } else if (path.matches(".*/api/v1/uploads/[^/]+/chunks/\\d+") && method.equals("PUT")) {
            chunkPuts.incrementAndGet();
            UUID upId = UUID.fromString(path.split("/")[4]);
            uploads.get(upId).bytes().writeBytes(ex.getRequestBody().readAllBytes());
            send(ex, 204, "");
        } else if (path.matches(".*/api/v1/uploads/[^/]+/complete") && method.equals("POST")) {
            UUID id = UUID.fromString(path.split("/")[4]);
            Upload u = uploads.get(id);
            byte[] content = u.bytes().toByteArray();
            String first = Sha.first(content);
            String last = Sha.last(content);
            String manifest = Sha.manifest(content);
            UUID nodeId = UUID.randomUUID();
            files.computeIfAbsent(u.parent(), k -> new ArrayList<>())
                .add(new FileDto(nodeId, u.name(), u.size(), first, last, u.sourceMtime(),
                    manifest, 1));
            uploadedPaths.add(u.name());
            send(ex, 200, Json.write(Map.of("nodeId", nodeId.toString(), "versionNo", 1)));
        } else if (path.matches(".*/api/v1/nodes/[^/]+/content") && method.equals("GET")) {
            UUID nodeId = UUID.fromString(path.split("/")[4]);
            byte[] body = files.values().stream().flatMap(List::stream)
                .filter(f -> f.id().equals(nodeId)).findFirst()
                .map(f -> new byte[(int) f.size()]).orElse("content".getBytes(StandardCharsets.UTF_8));
            sendRaw(ex, 200, body);
        } else {
            send(ex, 404, "{}");
        }
    }

    private void SendManifest(HttpExchange ex) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (var entry : files.entrySet()) {
            for (FileDto f : entry.getValue()) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("relPath", relPath(entry.getKey(), f.name()));
                m.put("nodeId", f.id().toString());
                m.put("size", f.size());
                m.put("sourceMtime", f.sourceMtime());
                m.put("firstChunkSha", f.firstSha());
                m.put("lastChunkSha", f.lastSha());
                m.put("manifestSha256", f.manifestSha());
                m.put("versionNo", f.version());
                items.add(m);
            }
        }
        items.sort(Comparator.comparing(m -> (String) m.get("relPath")));
        send(ex, 200, Json.write(Map.of("rootNodeId", ROOT.toString(), "items", items)));
    }

    /** 由父目录反推 relPath(相对 ROOT)。 */
    private String relPath(UUID parent, String fileName) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        UUID cur = parent;
        while (cur != null && !cur.equals(ROOT)) {
            Dir d = dirs.get(cur);
            if (d == null) break;
            parts.addFirst(d.name());
            cur = d.parent();
        }
        parts.addLast(fileName);
        return String.join("/", parts);
    }

    /** 测试种子:创建目录链并写入一个文件(manifestSha 按服务端规则)。 */
    public UUID seedFile(String relPath, String content) {
        String[] parts = relPath.split("/");
        UUID parent = ROOT;
        for (int i = 0; i < parts.length - 1; i++) {
            UUID cur = parent;
            parent = dirs.values().stream()
                .filter(d -> Objects.equals(d.parent(), cur) && d.name().equals(parts[i]))
                .map(Dir::id).findFirst()
                .orElseGet(() -> { UUID id = UUID.randomUUID(); dirs.put(id, new Dir(id, cur, parts[i])); return id; });
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        UUID nodeId = UUID.randomUUID();
        files.computeIfAbsent(parent, k -> new ArrayList<>()).add(new FileDto(nodeId,
            parts[parts.length - 1], bytes.length, Sha.first(bytes), Sha.last(bytes),
            null, Sha.manifest(bytes), 1));
        return nodeId;
    }

    private static String param(String query, String name) {
        if (query == null) return "";
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (p[0].equals(name)) return p.length > 1 ? p[1] : "";
        }
        return "";
    }

    private static void sendRaw(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override public void close() { server.stop(0); }
}
```

`agent/src/test/java/com/pnas/agent/Json.java`(测试辅助):`write(Object)`/`read(byte[])` 基于 Jackson `ObjectMapper`。

`agent/src/test/java/com/pnas/agent/Sha.java`(测试辅助,模拟服务端规则):

```java
package com.pnas.agent;

import com.pnas.agent.remote.Sha256;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class Sha {
    private static final int CHUNK = 4 * 1024 * 1024;
    private Sha() {}
    private static List<String> chunks(byte[] data) {
        List<String> hashes = new ArrayList<>();
        for (int off = 0; off < data.length; off += CHUNK) {
            int end = Math.min(data.length, off + CHUNK);
            hashes.add(Sha256.hex(java.util.Arrays.copyOfRange(data, off, end)));
        }
        return hashes;
    }
    public static String first(byte[] data) { var c = chunks(data); return c.isEmpty() ? null : c.get(0); }
    public static String last(byte[] data) { var c = chunks(data); return c.isEmpty() ? null : c.get(c.size() - 1); }
    public static String manifest(byte[] data) {
        return Sha256.hex(String.join("", chunks(data)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: 写客户端测试**

```java
package com.pnas.agent;

import com.pnas.agent.remote.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteClientsTest {

    @Test
    void clientsSpeakTheProtocol() throws Exception {
        try (StubServer stub = new StubServer()) {
            HttpJson http = new HttpJson(stub.baseUrl(), "good-token");
            NodeClient nodes = new NodeClient(http);
            UUID root = UUID.fromString("00000000-0000-0000-0000-000000000001");

            UUID dir = nodes.ensureDir(root, "Docs");
            assertThat(nodes.listChildren(root)).extracting(NodeClient.NodeDto::name).contains("Docs");

            UploadClient uploads = new UploadClient(http);
            Instant mtime = Instant.parse("2026-10-03T10:00:00Z");
            UUID up = uploads.start(dir, "a.txt", 3, mtime);
            uploads.putChunk(up, 0, "abc".getBytes());
            uploads.complete(up);
            assertThat(stub.uploadedPaths).containsExactly("a.txt");

            var page = new ManifestClient(http).page("dev-1", null, 200);
            assertThat(page.items()).extracting(com.pnas.agent.model.ManifestEntry::relPath)
                .contains("Docs/a.txt");

            RunClient runs = new RunClient(http);
            UUID runId = runs.start("dev-1", "MANUAL");
            runs.finish(runId, new RunClient.Finish("SUCCESS", 1, 1, 0, 0, 3, null));
            assertThat(stub.finishedRuns).contains(runId.toString());
        }
    }

    @Test
    void revokedTokenRaisesUnauthorized() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.revokedToken = "bad-token";
            HttpJson http = new HttpJson(stub.baseUrl(), "bad-token");
            try {
                new ManifestClient(http).page("dev-1", null, 10);
                org.junit.jupiter.api.Assertions.fail("expected AgentException");
            } catch (AgentException e) {
                assertThat(e.status()).isEqualTo(401);
            }
        }
    }
}
```

- [ ] **Step 3: 跑失败** → **Step 4: 实现**

`HttpJson.java`:

```java
package com.pnas.agent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpJson {

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String token;

    public HttpJson(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    public <T> T get(String path, Class<T> type) throws IOException, InterruptedException {
        var res = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return handle(res, type);
    }

    public <T> T get(String path, com.fasterxml.jackson.core.type.TypeReference<T> type)
            throws IOException, InterruptedException {
        var res = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new AgentException(res.statusCode(), "HTTP " + res.statusCode() + " for " + path);
        }
        return mapper.readValue(res.body(), type);
    }

    public <T> T post(String path, Object body, Class<T> type) throws IOException, InterruptedException {
        var res = client.send(request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        return handle(res, type);
    }

    public <T> T patch(String path, Object body, Class<T> type) throws IOException, InterruptedException {
        var res = client.send(request(path).header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        return handle(res, type);
    }

    public byte[] getBytes(String path) throws IOException, InterruptedException {
        var res = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new AgentException(res.statusCode(), "HTTP " + res.statusCode() + " for " + path);
        }
        return res.body();
    }

    public void putBytes(String path, byte[] body, String sha256) throws IOException, InterruptedException {
        var res = client.send(request(path).header("Content-Type", "application/octet-stream")
            .header("X-Sha256", sha256)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new AgentException(res.statusCode(), "HTTP " + res.statusCode() + " for " + path);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token);
    }

    private <T> T handle(HttpResponse<byte[]> res, Class<T> type) throws IOException {
        if (res.statusCode() / 100 != 2) {
            throw new AgentException(res.statusCode(), "HTTP " + res.statusCode()
                + ": " + new String(res.body(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (type == Void.class || res.body().length == 0) {
            return null;
        }
        return mapper.readValue(res.body(), type);
    }

    public ObjectMapper mapper() { return mapper; }
}
```

`AgentException.java`:

```java
package com.pnas.agent.remote;

public class AgentException extends RuntimeException {
    private final int status;
    public AgentException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
```

`ManifestClient.java`:

```java
package com.pnas.agent.remote;

import com.pnas.agent.model.ManifestEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ManifestClient {

    public record Page(UUID rootNodeId, List<ManifestEntry> items, String nextCursor) {}

    private final HttpJson http;

    public ManifestClient(HttpJson http) { this.http = http; }

    public Page page(String deviceId, String cursor, int limit) throws IOException, InterruptedException {
        String path = "/api/v1/backup/manifest?deviceId=" + deviceId + "&limit=" + limit
            + (cursor == null ? "" : "&cursor=" + cursor);
        return http.get(path, Page.class);
    }

    public List<ManifestEntry> all(String deviceId, int pageSize) throws IOException, InterruptedException {
        List<ManifestEntry> out = new ArrayList<>();
        String cursor = null;
        do {
            Page page = page(deviceId, cursor, pageSize);
            out.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return out;
    }
}
```

`RunClient.java`:

```java
package com.pnas.agent.remote;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public final class RunClient {

    public record StartResponse(UUID id, String status) {}
    public record Finish(String status, int filesSeen, int filesUploaded, int filesSkipped,
                         int filesFailed, long bytesUploaded, String error) {}

    private final HttpJson http;

    public RunClient(HttpJson http) { this.http = http; }

    public UUID start(String deviceId, String mode) throws IOException, InterruptedException {
        return http.post("/api/v1/backup/runs", Map.of("deviceId", deviceId, "mode", mode),
            StartResponse.class).id();
    }

    public void finish(UUID runId, Finish finish) throws IOException, InterruptedException {
        http.patch("/api/v1/backup/runs/" + runId, finish, Map.class);
    }
}
```

`UploadClient.java`:

```java
package com.pnas.agent.remote;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class UploadClient {

    private final HttpJson http;

    public UploadClient(HttpJson http) { this.http = http; }

    public UUID start(UUID parentId, String name, long size, Instant sourceMtime)
            throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("destParentId", parentId.toString());
        body.put("filename", name);
        body.put("size", size);
        if (sourceMtime != null) {
            body.put("sourceMtime", sourceMtime.toString());
        }
        return UUID.fromString(((Map<?, ?>) http.post("/api/v1/uploads", body, Map.class))
            .get("uploadId").toString());
    }

    public void putChunk(UUID uploadId, int seq, byte[] chunk) throws IOException, InterruptedException {
        http.putBytes("/api/v1/uploads/" + uploadId + "/chunks/" + seq, chunk, Sha256.hex(chunk));
    }

    public UUID complete(UUID uploadId) throws IOException, InterruptedException {
        var body = http.post("/api/v1/uploads/" + uploadId + "/complete", Map.of(), Map.class);
        return UUID.fromString(body.get("nodeId").toString());
    }
}
```

`Sha256.java`(agent 内新增简单工具):

```java
package com.pnas.agent.remote;

import java.security.MessageDigest;
import java.util.HexFormat;

public final class Sha256 {
    private Sha256() {}
    public static String hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```

`NodeClient.java`:

```java
package com.pnas.agent.remote;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NodeClient {

    public record NodeDto(UUID id, String name, String kind, long sizeBytes) {}

    private final HttpJson http;

    public NodeClient(HttpJson http) { this.http = http; }

    public List<NodeDto> listChildren(UUID parentId) throws IOException, InterruptedException {
        List<Map<String, Object>> raw = http.get("/api/v1/nodes?parentId=" + parentId,
            new TypeReference<List<Map<String, Object>>>() {});
        return raw.stream().map(m -> new NodeDto(UUID.fromString((String) m.get("id")),
            (String) m.get("name"), (String) m.get("kind"),
            m.get("sizeBytes") == null ? 0 : ((Number) m.get("sizeBytes")).longValue())).toList();
    }

    /** 从 root 逐级确保目录存在,返回末级目录 id。relDir 使用 '/' 分隔。 */
    public UUID ensureDir(UUID rootNodeId, String relDir) throws IOException, InterruptedException {
        UUID current = rootNodeId;
        for (String segment : relDir.split("/")) {
            if (segment.isEmpty()) continue;
            String name = segment;
            var children = listChildren(current);
            var existing = children.stream()
                .filter(c -> "DIR".equals(c.kind()) && name.equals(c.name()))
                .findFirst();
            if (existing.isPresent()) {
                current = existing.get().id();
            } else {
                Map<?, ?> created = http.post("/api/v1/nodes",
                    Map.of("parentId", current.toString(), "name", name), Map.class);
                current = UUID.fromString(created.get("id").toString());
            }
        }
        return current;
    }

    public byte[] download(UUID nodeId) throws IOException, InterruptedException {
        return http.getBytes("/api/v1/nodes/" + nodeId + "/content");
    }
}
```

> `HttpJson.get` 需要支持泛型 TypeReference。执行者应将签名改为 `<T> T get(String path, TypeReference<T> type)`(用一个重载),并让上面的 `listChildren` 使用它;等价写法亦可,只要测试通过且不引入 unchecked 警告。

- [ ] **Step 5: 跑通过**(`-Dtest='RemoteClientsTest'` + 全量)
- [ ] **Step 6: 提交**(`feat(m4a): Agent HTTP 客户端(清单/运行/上传/节点)`)

---

### Task 10: Runner(单飞、统计、PARTIAL)与 CLI `backup --once`

**Files:**
- Create: `agent/src/main/java/com/pnas/agent/run/Runner.java`
- Modify: `agent/src/main/java/com/pnas/agent/Main.java`(接入 `backup --once` 与退出码)
- Test: `agent/src/test/java/com/pnas/agent/RunnerTest.java`

**Interfaces:**
- Consumes: `Scanner`、`Planner`、`ManifestClient`、`UploadClient`、`NodeClient`、`RunClient`、`AgentConfig`
- Produces: `Runner.run(RunMode) : Stats`;`record Stats(int seen,int uploaded,int skipped,int failed,long bytes)`;`Runner` 单飞(并发调用返回上一次统计的 `skipped` 语义:本轮跳过)

- [ ] **Step 1: 写失败测试**(StubServer;期间需把 Stub 的 complete 改为**计算真实首末块/清单 sha**,并固定 `rootNodeId`,使第二轮可跳过)

```java
package com.pnas.agent;

import com.pnas.agent.config.AgentConfig;
import com.pnas.agent.remote.*;
import com.pnas.agent.run.Runner;
import com.pnas.agent.scan.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerTest {

    @TempDir Path dir;

    private Runner runner(StubServer stub) {
        var http = new HttpJson(stub.baseUrl(), "t");
        var cfg = new AgentConfig(stub.baseUrl(), "t", "dev-1",
            List.of(new AgentConfig.Root(dir.toString(), "r")), 60, false, List.of(), 3, 100);
        return new Runner(cfg, new Scanner(), new com.pnas.agent.plan.Planner(),
            new ManifestClient(http), new UploadClient(http), new NodeClient(http),
            new RunClient(http), 4 * 1024 * 1024);
    }

    @Test
    void firstRunUploadsAndSecondRunSkips() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "aaa");
        Files.writeString(dir.resolve("b.txt"), "bbbb");
        try (StubServer stub = new StubServer()) {
            Runner runner = runner(stub);
            var first = runner.run(com.pnas.agent.run.RunMode.MANUAL);
            assertThat(first.uploaded()).isEqualTo(2);
            assertThat(first.skipped()).isZero();
            assertThat(stub.uploadedPaths).containsExactlyInAnyOrder("a.txt", "b.txt");

            var second = runner.run(com.pnas.agent.run.RunMode.MANUAL);
            assertThat(second.uploaded()).isZero();
            assertThat(second.skipped()).isEqualTo(2);
            assertThat(stub.finishedRuns).hasSize(2);
        }
    }

    @Test
    void changedFileUploadsAgainAndIsCounted() throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "aaa");
        try (StubServer stub = new StubServer()) {
            Runner runner = runner(stub);
            runner.run(com.pnas.agent.run.RunMode.MANUAL);
            Files.writeString(file, "aaaaaa");
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 10_000));
            var second = runner.run(com.pnas.agent.run.RunMode.MANUAL);
            assertThat(second.uploaded()).isEqualTo(1);
            assertThat(second.skipped()).isZero();
        }
    }
}
```

- [ ] **Step 2: 跑失败** → **Step 3: 实现**

`Runner.java`:

```java
package com.pnas.agent.run;

import com.pnas.agent.config.AgentConfig;
import com.pnas.agent.model.FileItem;
import com.pnas.agent.model.ManifestEntry;
import com.pnas.agent.plan.Planner;
import com.pnas.agent.remote.*;
import com.pnas.agent.scan.Scanner;

import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Runner {

    public record Stats(int seen, int uploaded, int skipped, int failed, long bytes) {}

    private final AgentConfig config;
    private final Scanner scanner;
    private final Planner planner;
    private final ManifestClient manifests;
    private final UploadClient uploads;
    private final NodeClient nodes;
    private final RunClient runs;
    private final int chunkSize;
    private final AtomicBoolean busy = new AtomicBoolean();

    public Runner(AgentConfig config, Scanner scanner, Planner planner, ManifestClient manifests,
                  UploadClient uploads, NodeClient nodes, RunClient runs, int chunkSize) {
        this.config = config;
        this.scanner = scanner;
        this.planner = planner;
        this.manifests = manifests;
        this.uploads = uploads;
        this.nodes = nodes;
        this.runs = runs;
        this.chunkSize = chunkSize;
    }

    public Stats run(RunMode mode) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            return new Stats(0, 0, 0, 0, 0);
        }
        UUID runId = runs.start(config.deviceId(), mode.name());
        int seen = 0, uploaded = 0, skipped = 0, failed = 0;
        long bytes = 0;
        try {
            var firstPage = manifests.page(config.deviceId(), null, 200);
            UUID rootNodeId = firstPage.rootNodeId();
            Map<String, ManifestEntry> remote = new HashMap<>();
            manifests.all(config.deviceId(), 200).forEach(e -> remote.put(e.relPath(), e));

            for (AgentConfig.Root root : config.roots()) {
                List<FileItem> items = scanner.scan(java.nio.file.Path.of(root.path()),
                    root.remoteName(), config.exclude(), chunkSize);
                for (FileItem item : items) {
                    seen++;
                    if (!planner.decide(item, remote.get(item.relPath())).upload()) {
                        skipped++;
                        continue;
                    }
                    try {
                        int slash = item.relPath().lastIndexOf('/');
                        UUID parent = slash < 0 ? rootNodeId
                            : nodes.ensureDir(rootNodeId, item.relPath().substring(0, slash));
                        UUID upId = uploads.start(parent, item.path().getFileName().toString(),
                            item.size(), item.mtime());
                        int seq = 0;
                        try (InputStream in = Files.newInputStream(item.path())) {
                            byte[] buf = new byte[chunkSize];
                            int n;
                            while ((n = in.read(buf)) >= 0) {
                                if (n == 0) continue;
                                uploads.putChunk(upId, seq++, Arrays.copyOf(buf, n));
                            }
                        }
                        uploads.complete(upId);
                        uploaded++;
                        bytes += item.size();
                    } catch (Exception e) {
                        failed++;
                        System.err.println("上传失败 " + item.relPath() + ": " + e.getMessage());
                    }
                }
            }
            String status = failed > 0 ? "PARTIAL" : "SUCCESS";
            runs.finish(runId, new RunClient.Finish(status, seen, uploaded, skipped, failed, bytes,
                failed > 0 ? failed + " 个文件失败" : null));
            return new Stats(seen, uploaded, skipped, failed, bytes);
        } catch (Exception e) {
            runs.finish(runId, new RunClient.Finish("FAILED", seen, uploaded, skipped, failed, bytes,
                e.getMessage()));
            throw e;
        } finally {
            busy.set(false);
        }
    }
}
```

`init --server <url> --token <t> --device <id> --root <path[:remoteName]> [--root ...]` 写出 `~/.pnas-agent/config.json`(intervalMinutes 默认 360、realtime 默认 true、exclude 默认 `[]`);`status` 打印配置并请求最近一轮运行。

`Main` 增加 `backup --once`:解析 `--config <file>`(默认 `~/.pnas-agent/config.json`),加载并 `validate()`,构造客户端与 Runner,调用 `run(MANUAL)`;退出码:uploaded/failed 决定 `failed>0 ? 1 : 0`;`AgentException(401)` → 2。

- [ ] **Step 4: 跑通过** → **Step 5: 提交**(`feat(m4a): Agent 备份运行器与 backup --once`)

---

### Task 11: 实时监视、定时调度与 `run` 常驻

**Files:**
- Create: `agent/src/main/java/com/pnas/agent/watch/Debouncer.java`
- Create: `agent/src/main/java/com/pnas/agent/watch/Watcher.java`
- Create: `agent/src/main/java/com/pnas/agent/sched/Scheduler.java`
- Modify: `agent/src/main/java/com/pnas/agent/Main.java`(`run` 子命令)
- Test: `agent/src/test/java/com/pnas/agent/DebouncerTest.java`

**Interfaces:**
- Produces: `Debouncer(long windowMs, Runnable action)`:`trigger()`、`close()`;`Watcher(Path root, Debouncer debouncer)`:`start()`、`close()`;`Scheduler(Runnable task, long intervalMinutes)`:`start()`、`close()`

- [ ] **Step 1: 失败测试**

```java
package com.pnas.agent;

import com.pnas.agent.watch.Debouncer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DebouncerTest {

    @Test
    void coalescesBurstIntoOneRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        try (Debouncer d = new Debouncer(100, () -> { runs.incrementAndGet(); latch.countDown(); })) {
            for (int i = 0; i < 5; i++) {
                d.trigger();
                Thread.sleep(20);
            }
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(runs.get()).isEqualTo(1);
        }
    }
}
```

- [ ] **Step 2: 跑失败** → **Step 3: 实现**

`Debouncer.java`:

```java
package com.pnas.agent.watch;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class Debouncer implements AutoCloseable {

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final long windowMs;
    private final Runnable action;
    private ScheduledFuture<?> pending;

    public Debouncer(long windowMs, Runnable action) {
        this.windowMs = windowMs;
        this.action = action;
    }

    public synchronized void trigger() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = exec.schedule(() -> {
            synchronized (this) { pending = null; }
            action.run();
        }, windowMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() { exec.shutdownNow(); }
}
```

`Watcher.java`:递归 `WatchService`(注册子目录,事件 `ENTRY_CREATE` 若是目录则注册),任何事件 `debouncer.trigger()`;`close()` 关服务。`Scheduler.java`:`scheduleAtFixedRate(() -> { try { task.run(); } catch (Exception e) { System.err.println(...); } }, interval, interval, MINUTES)`。

`Main.run`:读配置、可选启动 watcher(`config.realtime()`)、启动 scheduler,主线程 `Thread.currentThread().join()`;加 JVM shutdown hook 关闭资源。

- [ ] **Step 4: 跑通过** → **Step 5: 提交**(`feat(m4a): 实时防抖监视与定时调度`)

---

### Task 12: 恢复与校验(`restore`/`verify`)

**Files:**
- Modify: `backend/pnas-server/src/main/java/com/pnas/server/backup/ManifestService.java`(无字段变更;Entry 自 Task 6 已含 `manifestSha256`)
- Create: `agent/src/main/java/com/pnas/agent/restore/Restorer.java`
- Create: `agent/src/main/java/com/pnas/agent/restore/Verifier.java`
- Modify: `agent/src/main/java/com/pnas/agent/remote/ManifestClient.java`(Entry 字段同步)、`Main.java`(子命令)
- Test: `agent/src/test/java/com/pnas/agent/RestoreTest.java`

**Interfaces:**
- Produces: `Restorer.restore(String deviceId, String relPath, Path dest, boolean overwrite) : int`(返回恢复文件数);`Verifier.verifyAll(String deviceId, int sampleLimit) : VerifyResult(int checked,int passed,List<String> failed)`;`record VerifyResult(...)`

- [ ] **Step 1: 失败测试**(桩返回的 `manifestSha256` 用"内容的分块哈希再哈希"规则;路径含 `..` 必须拒绝)

> Main 子命令参数:`restore --config <file> --path <relPath> --dest <dir> [--overwrite]`、`verify --config <file> [--all|--limit N]`、`status --config <file>`。

```java
package com.pnas.agent;

import com.pnas.agent.remote.*;
import com.pnas.agent.restore.Restorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestoreTest {

    @TempDir Path dir;

    @Test
    void restoreWritesFileAndVerifiesManifestHash() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.seedFile("Docs/a.txt", "abc");
            var http = new HttpJson(stub.baseUrl(), "t");
            var restorer = new Restorer(new ManifestClient(http), new NodeClient(http), 4 * 1024 * 1024);
            int count = restorer.restore("dev-1", "Docs/a.txt", dir.resolve("out"), false);
            assertThat(count).isEqualTo(1);
            assertThat(Files.readString(dir.resolve("out/a.txt"))).isEqualTo("abc");
        }
    }

    @Test
    void rejectsPathTraversal() {
        try (StubServer stub = new StubServer()) {
            var http = new HttpJson(stub.baseUrl(), "t");
            var restorer = new Restorer(new ManifestClient(http), new NodeClient(http), 4 * 1024 * 1024);
            assertThatThrownBy(() -> restorer.restore("dev-1", "../etc/passwd", dir, false))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
```

> `StubServer.seedFile` 需新增:把一条文件放进内存树(其 `manifestSha256` 按服务端规则计算);`GET /nodes/{id}/content` 返回该内容。

- [ ] **Step 2: 跑失败** → **Step 3: 实现**

`ManifestService.Entry` 与 `ManifestEntry` 自 Task 6 起已含 `manifestSha256`(取 `version.getManifestSha256()`),此处无需改字段。

`Restorer` 要点:

```java
    public int restore(String deviceId, String relPath, Path dest, boolean overwrite) throws Exception {
        if (relPath == null || relPath.isBlank() || relPath.contains("..")
            || relPath.startsWith("/") || relPath.startsWith("\\")) {
            throw new IllegalArgumentException("非法相对路径: " + relPath);
        }
        List<ManifestEntry> entries = manifests.all(deviceId);
        var exact = entries.stream().filter(e -> e.relPath().equals(relPath)).findFirst();
        List<ManifestEntry> targets = exact.map(List::of).orElseGet(() ->
            entries.stream().filter(e -> e.relPath().startsWith(relPath + "/")).toList());
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("备份中不存在: " + relPath);
        }
        int count = 0;
        for (ManifestEntry e : targets) {
            Path target = dest.resolve(e.relPath().substring(e.relPath().indexOf('/') + 1));
            if (Files.exists(target) && !overwrite) { count++; continue; }
            byte[] content = nodes.download(e.nodeId());
            if (!manifestSha256(content, chunkSize).equals(e.manifestSha256())) {
                throw new IllegalStateException("哈希校验失败: " + e.relPath());
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.write(target, content);
            count++;
        }
        return count;
    }
```

`manifestSha256(content, chunkSize)` = `Sha256.hex(String.join("", chunkHashes).getBytes(UTF_8))`(服务端规则:先写块哈希十六进制串、再取 sha256)。

`Verifier`:`verifyAll(limit)` 遍历清单文件,下载并按上法校验,统计通过/失败。

- [ ] **Step 4: 跑通过** → **Step 5: 提交**(`feat(m4a): Agent 恢复与校验(manifest 哈希)`)

---

### Task 13: Web 备份页(设备/令牌/运行)

**Files:**
- Create: `frontend/src/api/backup.ts`
- Create: `frontend/src/views/BackupView.vue`
- Modify: `frontend/src/router/index.ts`(`/backup` 路由)
- Modify: `frontend/src/App.vue`(导航“备份”)
- Test: 构建门禁(`npm run build`)

**Interfaces:**
- Consumes: `/api/v1/devices`、`/api/v1/backup/runs`;既有 `http` 与 `errorMessage`
- Produces: 设备创建(一次性 token 对话框)、吊销、运行列表(状态/统计/错误,5s 刷新)

- [ ] **Step 1: API 层**

```ts
import { http } from './http'

export interface DeviceDto {
  id: string
  name: string
  platform: 'MAC' | 'WINDOWS' | 'OTHER'
  createdAt: string
  lastSeenAt: string | null
  revoked: boolean
}

export interface RunDto {
  id: string
  deviceId: string
  mode: 'MANUAL' | 'SCHEDULED' | 'REALTIME'
  status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED'
  startedAt: string
  finishedAt: string | null
  filesSeen: number
  filesUploaded: number
  filesSkipped: number
  filesFailed: number
  bytesUploaded: number
  error: string | null
}

export async function listDevices(): Promise<DeviceDto[]> {
  return (await http.get<DeviceDto[]>('/devices')).data
}

export async function createDevice(name: string, platform: string) {
  return (await http.post<{ device: DeviceDto; token: string }>('/devices', { name, platform })).data
}

export async function revokeDevice(id: string): Promise<void> {
  await http.delete(`/devices/${id}`)
}

export async function listRuns(limit = 50): Promise<RunDto[]> {
  return (await http.get<RunDto[]>('/backup/runs', { params: { limit } })).data
}
```

- [ ] **Step 2: 视图**

`BackupView.vue` 要点:顶部“新建设备”按钮 → `ElMessageBox.prompt` 名称 + 平台选择 → 创建成功后用 `ElMessageBox.alert` 展示**一次性令牌**(等宽字体 + 复制提示,关闭后不可再取);设备表(名称/平台/最近活跃/状态/吊销按钮);运行表(时间/设备/模式/状态 tag/统计/错误),`setInterval(5000)` 刷新并在 `onUnmounted` 清理;错误 `ElMessage.error`。

路由:`{ path: '/backup', name: 'backup', component: () => import('@/views/BackupView.vue') }`;导航 `<router-link to="/backup">备份</router-link>`。

- [ ] **Step 3: 构建门禁**

Run: `cd frontend && npm run build` — PASS。

- [ ] **Step 4: 提交**(`feat(m4a): 备份页(设备/令牌/运行记录)`)

---

### Task 14: 打包、E2E 验收与文档

**Files:**
- Create: `agent/packaging/package-macos.sh`
- Create: `agent/packaging/windows.md`
- Create: `scripts/acceptance-m4.sh`
- Modify: `docs/progress.md`(M4a 结果与证据)

**Interfaces:**
- Consumes: 全部前置;服务器 `BASE`、`agent/target/pnas-agent.jar`
- Produces: macOS `.dmg`;Windows 打包文档;M4a 验收证据

- [ ] **Step 1: macOS 打包脚本**

```bash
#!/usr/bin/env bash
# 构建 macOS .dmg:jpackage + 手工 bundle(含 Jackson 依赖的 Class-Path)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-21.0.12.1+1/Contents/Home}"
JAR="$ROOT/agent/target/pnas-agent.jar"
OUT="$ROOT/agent/target/package"
BUNDLE="$OUT/bundle"

cd "$ROOT/agent"
"${MVN:-/Users/xiao/hui/soft/maven3.8/bin/mvn}" -o -s ../.tools/maven-settings.xml package -DskipTests

rm -rf "$OUT" && mkdir -p "$BUNDLE/lib"
cp "$JAR" "$BUNDLE/"
for j in jackson-databind jackson-core jackson-annotations; do
  find "$ROOT/.tools/m2repo/com/fasterxml/jackson" -name "$j-2.17.2.jar" -exec cp {} "$BUNDLE/lib/" \; 2>/dev/null || true
done
# 让 jar 的 Class-Path 指向 lib/(jpackage 只把 main jar 放进 classpath)
printf 'Class-Path: lib/jackson-databind-2.17.2.jar lib/jackson-core-2.17.2.jar lib/jackson-annotations-2.17.2.jar\n' \
  > /tmp/pnas-agent-manifest.txt
"$JAVA_HOME/bin/jar" --update --manifest=/tmp/pnas-agent-manifest.txt --file "$BUNDLE/pnas-agent.jar"

"$JAVA_HOME/bin/jpackage" \
  --type dmg --name "PNAS Agent" --app-version 0.1.0 \
  --input "$BUNDLE" --main-jar pnas-agent.jar --main-class com.pnas.agent.Main \
  --dest "$OUT" --java-options "-Xmx256m"
echo "打包完成: $OUT"
```

- [ ] **Step 2: Windows 文档**(命令 + Task Scheduler 注册 + 限制说明:未实测)

- [ ] **Step 3: E2E 验收脚本**(幂等;`BASE` 参数,默认 `http://localhost:8080`)

```bash
#!/usr/bin/env bash
# M4a 验收:设备令牌 → agent 备份 → 增量 → verify/restore → 吊销
set -uo pipefail
BASE="${1:-http://localhost:8080}"; API="$BASE/api/v1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-21.0.12.1+1/Contents/Home}"
AGENT_JAR="$ROOT/agent/target/pnas-agent.jar"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0
check() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  ✅ $1"; else FAIL=$((FAIL+1)); echo "  ❌ $1 期望 $3 实际 $2"; fi }

# 1) 登录 + 建设备
curl -s -c "$WORK/admin.jar" -D "$WORK/login.hdr" -o /dev/null -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin-secret"}'
CSRF=$(grep -i '^x-csrf-token:' "$WORK/login.hdr" | awk '{print $2}' | tr -d '\r')
DEV=$(curl -s -b "$WORK/admin.jar" -H "X-CSRF: $CSRF" -H 'Content-Type: application/json' \
  -X POST "$API/devices" -d "{\"name\":\"acc-$RANDOM\",\"platform\":\"MAC\"}")
DEVICE_ID=$(printf '%s' "$DEV" | python3 -c 'import sys,json;print(json.load(sys.stdin)["device"]["id"])')
TOKEN=$(printf '%s' "$DEV" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 2) 生成 4 个文件(含 >4MiB 大文件)
SRC="$WORK/src"; mkdir -p "$SRC/sub"
printf 'alpha' > "$SRC/a.txt"; printf 'beta' > "$SRC/b.txt"
head -c 5000000 /dev/urandom > "$SRC/big.bin"
printf 'gamma' > "$SRC/sub/c.txt"
cat > "$WORK/config.json" <<EOF
{"serverUrl":"$BASE","token":"$TOKEN","deviceId":"$DEVICE_ID",
 "roots":[{"path":"$SRC","remoteName":"Files"}],
 "intervalMinutes":360,"realtime":false,"exclude":[],"maxRetries":3,"retryBackoffMs":100}
EOF

# 3) 首次备份
"${JAVA_HOME:-$ROOT/.tools/jdk-21.0.12.1+1/Contents/Home}/bin/java" -jar "$AGENT_JAR" \
  backup --once --config "$WORK/config.json"; check "首次备份退出码" "$?" "0"

# 4) 服务端校验节点树与版本(经 API 查询)
NODES=$(curl -s -b "$WORK/admin.jar" "$API/nodes?parentId=null")
echo "$NODES" | grep -q '自动备份' && check "备份根存在" "yes" "yes" || check "备份根存在" "no" "yes"

# 5) 改 1 个文件 + 再跑
printf 'alpha2' > "$SRC/a.txt"
touch -t 203001010000 "$SRC/a.txt" 2>/dev/null || true
"$JAVA_HOME/bin/java" -jar "$AGENT_JAR" backup --once --config "$WORK/config.json"
check "二次备份退出码" "$?" "0"

# 6) verify / restore
"$JAVA_HOME/bin/java" -jar "$AGENT_JAR" verify --config "$WORK/config.json" --all
check "verify 退出码" "$?" "0"
"$JAVA_HOME/bin/java" -jar "$AGENT_JAR" restore --config "$WORK/config.json" \
  --path Files/a.txt --dest "$WORK/restored"
check "restore 退出码" "$?" "0"
cmp -s "$SRC/a.txt" "$WORK/restored/a.txt" && check "恢复内容一致" "yes" "yes" || check "恢复内容一致" "no" "yes"

# 7) 吊销后失败
curl -s -o /dev/null -b "$WORK/admin.jar" -H "X-CSRF: $CSRF" -X DELETE "$API/devices/$DEVICE_ID"
"$JAVA_HOME/bin/java" -jar "$AGENT_JAR" backup --once --config "$WORK/config.json"
check "吊销后退出码非 0" "$([ $? -ne 0 ] && echo yes || echo no)" "yes"

echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ] || exit 1
```

> 执行者:路径/字段按真实 API 调整;断言全部来自真实响应,不要“预期达标”。运行前需服务端开启(`PNAS_JOBS_SCHEDULER_ENABLED=true` 非必需,但媒体无关)。

- [ ] **Step 4: 运行验收**,把真实输出(PASS/FAIL、退出码)记入报告。

- [ ] **Step 5: 更新 `docs/progress.md`** M4a 段(证据:后端测试数、agent 测试数、验收 PASS、macOS 打包结果、Windows 限制)。

- [ ] **Step 6: 提交**(`test(m4a): 打包脚本、E2E 验收与进度文档`)
