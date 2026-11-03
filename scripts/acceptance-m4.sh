#!/usr/bin/env bash
# M4a 验收:设备令牌 → agent 文件级备份 → 增量 → verify/restore → 吊销
# 用法: bash scripts/acceptance-m4.sh [BASE_URL]      默认 http://localhost:8080
#
# 前置:
#   1) 服务已启动并完成 Flyway V4(backup 表);账号 admin/admin-secret(bootstrap);
#   2) agent jar 已构建:cd agent && mvn -o -s ../.tools/maven-settings.xml package -DskipTests
#      (pom 未固定 finalName,脚本自动接受 target/pnas-agent*.jar);
#   3) 本机有 Java 21 与 python3(仅用 python3 解析 JSON,不依赖 jq)。
#
# 脚本幂等:每轮创建随机设备、随机临时目录;断言全部来自真实 HTTP 响应或 agent 退出码。
set -uo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN=""

WORK=$(mktemp -d) || { echo "无法创建临时目录" >&2; exit 2; }
PASS=0
FAIL=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

ok() { echo "  ✅ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL + 1)); }
check() { # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1 (期望 $3,实际 $2)"; fi
}
check_contains() { # check_contains <描述> <输出文件> <子串>
  if grep -q -- "$3" "$2" 2>/dev/null; then ok "$1"; else bad "$1 (输出未包含: $3)"; fi
}

# ---------------------------------------------------------------- 前置检查
echo "== PNAS M4a 验收: $BASE =="
if ! command -v python3 >/dev/null 2>&1; then
  echo "缺少 python3(用于解析 JSON,不依赖 jq)" >&2
  exit 2
fi

# 选 Java 21:优先 JAVA_HOME,其次仓库内便携 JDK,最后 PATH(兼容本机默认 JDK 8)。
java_major() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1
}
for cand in "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
            "$ROOT/.tools/jdk-21.0.12.1+1/Contents/Home/bin/java" \
            "$(command -v java 2>/dev/null || true)"; do
  [ -n "$cand" ] && [ -x "$cand" ] || continue
  major="$(java_major "$cand")"
  if [ "${major:-0}" -ge 21 ]; then JAVA_BIN="$cand"; break; fi
done
if [ -z "$JAVA_BIN" ]; then
  echo "缺少 Java 21(已尝试 JAVA_HOME、仓库 .tools/JDK、PATH)" >&2
  exit 2
fi
echo "  Java: $JAVA_BIN"

AGENT_JAR="${AGENT_JAR:-}"
if [ -z "$AGENT_JAR" ]; then
  for cand in "$ROOT/agent/target/pnas-agent.jar" "$ROOT"/agent/target/pnas-agent-*.jar; do
    [ -f "$cand" ] || continue
    case "$cand" in *.original) continue ;; esac
    AGENT_JAR="$cand"
    break
  done
fi
if [ -z "$AGENT_JAR" ] || [ ! -f "$AGENT_JAR" ]; then
  echo "未找到 agent jar(先在 agent/ 执行 mvn package -DskipTests)" >&2
  exit 2
fi

# agent jar 未打 fat jar;按 pom 的 2.17.2 版本补 Jackson 运行时 classpath。
AGENT_CP="$AGENT_JAR"
for j in jackson-databind jackson-core jackson-annotations; do
  lib=""
  for base in "$ROOT/.tools/m2repo" "$HOME/.m2/repository"; do
    [ -d "$base/com/fasterxml/jackson" ] || continue
    lib="$(find "$base/com/fasterxml/jackson" -name "$j-2.17.2.jar" 2>/dev/null | head -1 || true)"
    [ -n "$lib" ] && break
  done
  if [ -z "$lib" ]; then
    echo "缺少运行时依赖: $j-2.17.2.jar(检查 .tools/m2repo 或 ~/.m2/repository)" >&2
    exit 2
  fi
  AGENT_CP="$AGENT_CP:$lib"
done

agent() { "$JAVA_BIN" -cp "$AGENT_CP" com.pnas.agent.Main "$@"; }
run_agent() { # run_agent <参数...>:输出到 $WORK/agent.out,返回 agent 退出码
  agent "$@" >"$WORK/agent.out" 2>&1
  local rc=$?
  echo "  → pnas-agent $* (退出码 $rc)"
  sed 's/^/    /' "$WORK/agent.out" | tail -n 20
  return "$rc"
}

# ---------------------------------------------------------------- 1) 登录 + 建设备
echo "[1] 登录并创建设备"
LOGIN_CODE=$(curl -s -c "$WORK/admin.jar" -D "$WORK/login.hdr" -o "$WORK/login.json" \
  -w '%{http_code}' -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin-secret"}')
check "登录 HTTP 200" "$LOGIN_CODE" "200"
CSRF=$(grep -i '^x-csrf-token:' "$WORK/login.hdr" | awk '{print $2}' | tr -d '\r' | head -1)
check "登录返回 CSRF 头" "$([ -n "$CSRF" ] && echo yes || echo no)" "yes"

DEV_CODE=$(curl -s -b "$WORK/admin.jar" -H "X-CSRF: $CSRF" -H 'Content-Type: application/json' \
  -o "$WORK/device.json" -w '%{http_code}' -X POST "$API/devices" \
  -d "{\"name\":\"acc-$RANDOM\",\"platform\":\"MAC\"}")
check "创建设备 HTTP 200" "$DEV_CODE" "200"
DEVICE_ID=$(python3 - "$WORK/device.json" <<'PY'
import json, sys
try:
    print(json.load(open(sys.argv[1]))["device"]["id"])
except Exception:
    print("")
PY
)
TOKEN=$(python3 - "$WORK/device.json" <<'PY'
import json, sys
try:
    print(json.load(open(sys.argv[1]))["token"])
except Exception:
    print("")
PY
)
check "返回设备 ID" "$([ -n "$DEVICE_ID" ] && echo yes || echo no)" "yes"
check "返回设备令牌" "$([ -n "$TOKEN" ] && echo yes || echo no)" "yes"

# ---------------------------------------------------------------- 2) 生成源文件与配置
echo "[2] 准备 4 个源文件(含 5,000,000 字节大文件)"
SRC="$WORK/src"
mkdir -p "$SRC/sub"
printf 'alpha' > "$SRC/a.txt"
printf 'beta' > "$SRC/b.txt"
head -c 5000000 /dev/urandom > "$SRC/big.bin"
printf 'gamma' > "$SRC/sub/c.txt"
cat > "$WORK/config.json" <<EOF
{"serverUrl":"$BASE","token":"$TOKEN","deviceId":"$DEVICE_ID",
 "roots":[{"path":"$SRC","remoteName":"Files"}],
 "intervalMinutes":360,"realtime":false,"exclude":[],"maxRetries":3,"retryBackoffMs":100}
EOF

# ---------------------------------------------------------------- 3) 首次备份
echo "[3] 首次备份"
run_agent backup --once --config "$WORK/config.json"
RC=$?
check "首次备份退出码" "$RC" "0"
check_contains "首次备份上传 4 个文件" "$WORK/agent.out" "uploaded=4"
check_contains "首次备份无失败" "$WORK/agent.out" "failed=0"

# ---------------------------------------------------------------- 4) 校验节点树
echo "[4] 服务端节点树与版本"
http_get() { curl -s -b "$WORK/admin.jar" -o "$2" -w '%{http_code}' "$1"; }
NODES_CODE=$(http_get "$API/nodes" "$WORK/nodes.json")
check "根节点列表 HTTP 200" "$NODES_CODE" "200"
BACKUP_ID=$(python3 - "$WORK/nodes.json" <<'PY'
import json, sys
try:
    print(next((n["id"] for n in json.load(open(sys.argv[1])) if n.get("name") == "自动备份"), ""))
except Exception:
    print("")
PY
)
check "备份根「自动备份」存在" "$([ -n "$BACKUP_ID" ] && echo yes || echo no)" "yes"

NODES_CODE=$(http_get "$API/nodes?parentId=$BACKUP_ID" "$WORK/nodes-dev.json")
check "备份根子目录 HTTP 200" "$NODES_CODE" "200"
DEVICE_NODE=$(python3 - "$WORK/nodes-dev.json" "$DEVICE_ID" <<'PY'
import json, sys
try:
    print(next((n["id"] for n in json.load(open(sys.argv[1])) if n.get("name") == sys.argv[2]), ""))
except Exception:
    print("")
PY
)
check "备份根下有设备目录" "$([ -n "$DEVICE_NODE" ] && echo yes || echo no)" "yes"

NODES_CODE=$(http_get "$API/nodes?parentId=$DEVICE_NODE" "$WORK/nodes-remote.json")
check "设备目录子节点 HTTP 200" "$NODES_CODE" "200"
FILES_ID=$(python3 - "$WORK/nodes-remote.json" <<'PY'
import json, sys
try:
    print(next((n["id"] for n in json.load(open(sys.argv[1])) if n.get("name") == "Files"), ""))
except Exception:
    print("")
PY
)
check "远程名「Files」目录存在" "$([ -n "$FILES_ID" ] && echo yes || echo no)" "yes"

NODES_CODE=$(http_get "$API/nodes?parentId=$FILES_ID" "$WORK/nodes-files.json")
check "Files 目录列表 HTTP 200" "$NODES_CODE" "200"
python3 - "$WORK/nodes-files.json" "$WORK/files.names" <<'PY'
import json, sys
try:
    names = sorted(n["name"] for n in json.load(open(sys.argv[1])))
except Exception:
    names = []
open(sys.argv[2], "w").write("\n".join(names) + ("\n" if names else ""))
PY
check "备份 4 个条目" "$(wc -l < "$WORK/files.names" | tr -d ' ')" "4"
for name in a.txt b.txt big.bin sub; do
  check "备份包含 $name" "$(grep -Fxq "$name" "$WORK/files.names" && echo yes || echo no)" "yes"
done
BIG_SIZE=$(python3 - "$WORK/nodes-files.json" <<'PY'
import json, sys
try:
    print(next((str(n["sizeBytes"]) for n in json.load(open(sys.argv[1])) if n.get("name") == "big.bin"), ""))
except Exception:
    print("")
PY
)
check "大文件大小 5,000,000" "$BIG_SIZE" "5000000"
A_ID=$(python3 - "$WORK/nodes-files.json" <<'PY'
import json, sys
try:
    print(next((n["id"] for n in json.load(open(sys.argv[1])) if n.get("name") == "a.txt"), ""))
except Exception:
    print("")
PY
)

# ---------------------------------------------------------------- 5) 修改 + 二次备份
echo "[5] 修改 a.txt 后二次备份(文件级增量)"
printf 'alpha2' > "$SRC/a.txt"
touch -t 203001010000 "$SRC/a.txt" 2>/dev/null || true
run_agent backup --once --config "$WORK/config.json"
RC=$?
check "二次备份退出码" "$RC" "0"
check_contains "二次仅上传 1 个文件" "$WORK/agent.out" "uploaded=1"
check_contains "二次跳过 3 个文件" "$WORK/agent.out" "skipped=3"

VERS_CODE=$(http_get "$API/nodes/$A_ID/versions" "$WORK/versions.json")
check "a.txt 版本列表 HTTP 200" "$VERS_CODE" "200"
LATEST=$(python3 - "$WORK/versions.json" <<'PY'
import json, sys
try:
    print(max(v["versionNo"] for v in json.load(open(sys.argv[1]))))
except Exception:
    print("")
PY
)
check "a.txt 最新版本为 2" "$LATEST" "2"

# ---------------------------------------------------------------- 6) verify / restore
echo "[6] verify 与 restore"
run_agent verify --config "$WORK/config.json" --all
RC=$?
check "verify 退出码" "$RC" "0"
VERIFY_LINE=$(grep -o 'checked=[0-9]* passed=[0-9]* failed=[0-9]*' "$WORK/agent.out" | head -1)
echo "  verify 输出: ${VERIFY_LINE:-<未找到>}"
check_contains "verify 无失败" "$WORK/agent.out" "failed=0"
check_contains "verify 实际检查 4 个文件" "$WORK/agent.out" "checked=4"
check_contains "verify 4 个文件全部通过" "$WORK/agent.out" "passed=4"

rm -rf "$WORK/restored"
run_agent restore --config "$WORK/config.json" --path Files/a.txt --dest "$WORK/restored"
RC=$?
check "restore 退出码" "$RC" "0"
check "恢复文件存在" "$([ -f "$WORK/restored/a.txt" ] && echo yes || echo no)" "yes"
check "恢复内容与源一致(cmp)" "$(cmp -s "$SRC/a.txt" "$WORK/restored/a.txt" && echo yes || echo no)" "yes"

# ---------------------------------------------------------------- 7) 吊销后失败
echo "[7] 吊销设备后备份必须失败"
REVOKE_CODE=$(curl -s -b "$WORK/admin.jar" -H "X-CSRF: $CSRF" -o /dev/null \
  -w '%{http_code}' -X DELETE "$API/devices/$DEVICE_ID")
check "吊销设备 HTTP 204" "$REVOKE_CODE" "204"
run_agent backup --once --config "$WORK/config.json"
RC=$?
check "吊销后备份退出码非 0" "$([ "$RC" -ne 0 ] && echo yes || echo no)" "yes"
check_contains "吊销后鉴权失败信息" "$WORK/agent.out" "401"

echo
echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ] || exit 1
