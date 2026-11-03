# PNAS Agent — Windows 打包与开机运行

> 状态:**未实测**。本仓库的 macOS `.dmg` 已由 `agent/packaging/package-macos.sh` 验证;
> Windows 侧仅给出等价命令与步骤,未在 Windows 机器/CI 上执行过。首次部署前请按本文
> 在目标机器上逐条验证,尤其注意路径、JDK 位数与任务计划程序的权限。

## 1. 前置

- Windows 10/11 x64;
- JDK 21(含 `jpackage`,建议 Temurin/Oracle 官方安装包);
- Maven 3.8+ 与可离线/在线的 Maven 仓库;
- 首次打包需要 WiX Toolset v3(生成 `.msi`)或 jpackage 的 exe 打包依赖,按 jpackage 提示安装。

## 2. 构建并打包

在仓库根目录执行(把 `<JAVA_HOME>`、`<MVN>` 换成实际路径):

```bat
cd agent
"%MVN%" -o -s ..\.tools\maven-settings.xml package -DskipTests
```

`pom.xml` 未固定 `finalName`,产物为 `target\pnas-agent-<version>.jar`。

jpackage 只把 `--main-jar` 放进应用 classpath,因此需要手工准备 bundle(与 macOS 脚本一致):

```bat
cd ..
set OUT=agent\target\package
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%\bundle\lib"
copy agent\target\pnas-agent-0.1.0-SNAPSHOT.jar "%OUT%\bundle\pnas-agent.jar"

rem Jackson 三个 jar 可从本地 Maven 仓库复制(.m2\repository\com\fasterxml\jackson\...)
copy "%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-databind\2.17.2\jackson-databind-2.17.2.jar" "%OUT%\bundle\lib\"
copy "%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-core\2.17.2\jackson-core-2.17.2.jar" "%OUT%\bundle\lib\"
copy "%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-annotations\2.17.2\jackson-annotations-2.17.2.jar" "%OUT%\bundle\lib\"

rem 在 jar 清单中加入 Class-Path(Windows 下同样使用 / 分隔的清单路径)
(
echo Class-Path: lib/jackson-databind-2.17.2.jar lib/jackson-core-2.17.2.jar lib/jackson-annotations-2.17.2.jar
) > "%TEMP%\pnas-agent-manifest.txt"
"%JAVA_HOME%\bin\jar" --update --manifest="%TEMP%\pnas-agent-manifest.txt" --file "%OUT%\bundle\pnas-agent.jar"

"%JAVA_HOME%\bin\jpackage" ^
  --type exe --name "PNAS Agent" --app-version 1.0.0 ^
  --input "%OUT%\bundle" --main-jar pnas-agent.jar --main-class com.pnas.agent.Main ^
  --dest "%OUT%" --java-options "-Xmx256m" --win-console
```

产物:`agent\target\package\PNAS Agent-1.0.0.exe`。安装后默认位于
`C:\Program Files\PNAS Agent\PNAS Agent.exe`(实际以安装向导为准)。

> jpackage 要求 `--app-version` 首段为大于 0 的整数,项目版本 `0.1.0-SNAPSHOT` 不能直接使用;
> 打包版本因此固定为 `1.0.0`,jar 本身版本仍由 pom 管理。`.msi` 同理。

> 如需 `.msi`,把 `--type exe` 改为 `--type msi`;如不需要控制台窗口可去掉 `--win-console`,
> 但排障时保留控制台更方便。

## 3. 配置文件

Agent 默认读取当前用户目录下的配置(以实际 `Main.defaultConfigFile()` 实现为准);
建议显式传入。示例 `%USERPROFILE%\.pnas-agent\config.json`:

```json
{
  "serverUrl": "https://pnas.example.com",
  "token": "<设备令牌>",
  "deviceId": "<设备 ID>",
  "roots": [{ "path": "C:\\Users\\me\\Documents", "remoteName": "Docs" }],
  "intervalMinutes": 360,
  "realtime": false,
  "exclude": ["**/node_modules/**"],
  "maxRetries": 3,
  "retryBackoffMs": 100
}
```

设备令牌在 Web「备份」页创建设备时一次性展示,之后只存服务端哈希。

## 4. 注册开机/定时任务(Task Scheduler)

方式一(命令行,管理员 PowerShell 或 CMD):

```bat
schtasks /Create /TN "PNAS Agent" ^
  /TR "\"C:\Program Files\PNAS Agent\PNAS Agent.exe\" run --config \"%USERPROFILE%\.pnas-agent\config.json\"" ^
  /SC ONLOGON /RL LIMITED /F
```

- `/SC ONLOGON`:登录后启动常驻进程(内部按 `intervalMinutes` 定时 + 可选 realtime 监视);
- `/RL LIMITED`:以当前用户普通权限运行,避免读取系统目录;
- 开机即跑(无人登录)可改用 `/SC ONSTART /RU SYSTEM`,但需要额外的目录/配置权限,
  且服务端令牌对 SYSTEM 用户可见性不同,不推荐默认使用。

方式二(图形界面):任务计划程序 → 创建任务 → 触发器「登录时」→ 操作「启动程序」,
程序填 `PNAS Agent.exe`,参数填 `run --config "C:\Users\<user>\.pnas-agent\config.json"`,
起始于填安装目录;勾选「不管用户是否登录都要运行」时需改为 SYSTEM 并确认配置可读。

验证:

```bat
schtasks /Run /TN "PNAS Agent"
schtasks /Query /TN "PNAS Agent" /V /FO LIST
```

## 5. 限制与未验证项

- **整套 Windows 打包/安装/任务计划流程未实测**:本仓库验收环境为 macOS + JDK 21,
  Windows 的 jpackage(WiX)、杀毒软件拦截、UAC 与任务计划权限均未验证。
- jpackage 生成的 exe 默认不带自动更新;升级需重新打包并覆盖安装。
- 首次运行若被 SmartScreen 拦截,需手动「仍要运行」;生产分发应做代码签名。
- Agent 的增量是**文件级**(大小/mtime/首末块哈希),非块级增量;大文件改动会整文件重传。
- 本里程碑未实现 Windows 服务封装(仅登录任务),M4 后续如需可再评估。
