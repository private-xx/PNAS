#!/usr/bin/env bash
# PNAS M3 时间线首屏性能(SC-3:1000 条媒体 ≤ 2s)
# 用法: bash scripts/perf-m3-timeline.sh [BASE_URL] [N]   默认 http://localhost:8080 / 1000
#
# 口径:
#   * N 条 media_assets 直接写入数据库(绕开上传/入库链路),taken_at 从 now() 起按 8 小时间隔
#     向过去铺开(跨度由 N 决定,脚本按实际 min/max 计算月数),thumb_status='READY',indexed_at=now();
#   * 全部复用同一个已入库媒体的 file_versions.manifest_sha256/size_bytes(内容寻址去重后
#     blob 与缩略图只有一份),测量目标因此聚焦于"时间线查询 + 首屏 JSON";
#   * 端点 GET /api/v1/media/timeline?limit=60(时间线首屏默认页大小);
#   * WARMUP 次预热 + ROUNDS 次计时(默认 3 + 5),每次断言 HTTP 200,取 curl time_total 中位数,
#     判定 ≤ 2s(单次绝对值,非并发/多实例口径)。
#
# 前置:acceptance-m3.sh 已跑过一次(库里至少有一个 thumb_status='READY' 的图片资产);
#      docker 容器 pnas-acc-db 可用;admin/admin-secret 可登录。
# 清理:默认(含异常退出)由 trap 删除 name LIKE 'perf-m3-%' 的节点(级联 version/asset/chunk);
#      PNAS_PERF_KEEP=1 保留现场,重跑会先删旧 perf-m3-% 再播种。
set -uo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
N="${2:-1000}"
DB_CONTAINER="${PNAS_DB_CONTAINER:-pnas-acc-db}"
DB_USER="${PNAS_DB_USER:-acc}"
DB_NAME="${PNAS_DB_NAME:-pnas_m3}"
DB_PASSWORD="${PNAS_DB_PASSWORD:-acc}"
BUDGET_SECONDS=2.0
WARMUP=3
ROUNDS=5
WORK="$(mktemp -d)"
COOKIE="$WORK/cookie"
BODY="$WORK/timeline.json"
PERF_PREFIX="perf-m3-%"
SEED_STARTED=0

psql_q() { # psql_q <sql> -> 单值(tA)
  docker exec -i -e PGPASSWORD="$DB_PASSWORD" "$DB_CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -tA -v ON_ERROR_STOP=1 -c "$1"
}

cleanup_perf() {
  [ "$SEED_STARTED" = "1" ] || return 0
  if [ "${PNAS_PERF_KEEP:-0}" = "1" ]; then
    echo "  清理: 跳过(PNAS_PERF_KEEP=1),perf-m3-* 数据保留在库中"
    return 0
  fi
  docker exec "$DB_CONTAINER" true >/dev/null 2>&1 || return 0
  local removed
  removed=$(psql_q "WITH d AS (DELETE FROM nodes WHERE name LIKE '$PERF_PREFIX' RETURNING 1) \
    SELECT count(*) FROM d" 2>/dev/null) || return 0
  echo "  清理: 已删除 ${removed:-0} 条 perf-m3-* 节点(级联 version/asset/chunk)"
}
# 早退(登录失败、HTTP 非 200、测量异常)也走清理;保持幂等。
cleanup() { rm -rf "$WORK"; cleanup_perf; }
trap cleanup EXIT

echo "== PNAS M3 时间线性能(SC-3): $BASE =="
if ! docker exec "$DB_CONTAINER" true >/dev/null 2>&1; then
  echo "❌ 数据库容器 $DB_CONTAINER 不可用(可用 PNAS_DB_CONTAINER/PNAS_DB_NAME 覆盖)"
  exit 1
fi

ADMIN_ID=$(psql_q "SELECT id FROM users WHERE username = 'admin'")
if [ -z "$ADMIN_ID" ]; then
  echo "❌ 找不到 admin 账号"
  exit 1
fi
HOME_ID=$(psql_q "SELECT id FROM nodes WHERE owner_id = '$ADMIN_ID' AND parent_id IS NULL \
  AND kind = 'DIR' AND trashed_at IS NULL ORDER BY created_at LIMIT 1")
if [ -z "$HOME_ID" ]; then
  echo "❌ 找不到 admin 家目录"
  exit 1
fi

# 模板:一个已入库的图片资产(version + 单块 chunk),用于复制出 N 条资产
TEMPLATE=$(psql_q "SELECT v.manifest_sha256 || '|' || v.size_bytes || '|' || v.mime_type \
  || '|' || v.version_no || '|' || v.id \
  FROM media_assets ma JOIN file_versions v ON v.id = ma.file_version_id \
  JOIN nodes n ON n.id = ma.node_id \
  WHERE n.owner_id = '$ADMIN_ID' AND ma.kind = 'IMAGE' AND ma.thumb_status = 'READY' \
  ORDER BY ma.indexed_at DESC LIMIT 1")
if [ -z "$TEMPLATE" ]; then
  echo "❌ 库里没有可复用的已入库图片资产,请先跑 bash scripts/acceptance-m3.sh $BASE"
  exit 1
fi
IFS='|' read -r SHA SIZE MIME VERSION_NO VERSION_ID <<< "$TEMPLATE"

CHUNK=$(psql_q "SELECT seq || '|' || blob_hash || '|' || size_bytes \
  FROM version_chunks WHERE version_id = '$VERSION_ID' ORDER BY seq LIMIT 1")
if [ -z "$CHUNK" ]; then
  echo "❌ 模板版本没有 chunk 记录"
  exit 1
fi
IFS='|' read -r CHUNK_SEQ CHUNK_HASH CHUNK_SIZE <<< "$CHUNK"

echo "生成 $N 条 media_assets(psql 直插)…"
echo "  模板: manifest=${SHA:0:12}… size=$SIZE mime=$MIME versionNo=$VERSION_NO chunkSeq=$CHUNK_SEQ"
# 先删旧的 perf-m3-%(含上一次 PNAS_PERF_KEEP=1 留下的现场),保证重跑不会累加/冲突。
SEED_STARTED=1
docker exec -i -e PGPASSWORD="$DB_PASSWORD" "$DB_CONTAINER" \
  psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q <<SQL
BEGIN;
DELETE FROM nodes WHERE name LIKE 'perf-m3-%';
INSERT INTO nodes (id, parent_id, owner_id, kind, name, size_bytes)
SELECT gen_random_uuid(), '$HOME_ID', '$ADMIN_ID', 'FILE', 'perf-m3-' || g, $SIZE
FROM generate_series(1, $N) g;
INSERT INTO file_versions (id, node_id, version_no, size_bytes, mime_type,
                           manifest_sha256, created_by, created_at)
SELECT gen_random_uuid(), n.id, $VERSION_NO, $SIZE, '$MIME', '$SHA', '$ADMIN_ID', now()
FROM nodes n WHERE n.parent_id = '$HOME_ID' AND n.name LIKE 'perf-m3-%';
INSERT INTO version_chunks (id, version_id, seq, blob_hash, size_bytes)
SELECT gen_random_uuid(), v.id, $CHUNK_SEQ, '$CHUNK_HASH', $CHUNK_SIZE
FROM file_versions v JOIN nodes n ON n.id = v.node_id
WHERE n.parent_id = '$HOME_ID' AND n.name LIKE 'perf-m3-%';
INSERT INTO media_assets (id, file_version_id, node_id, kind, taken_at, taken_at_source,
                          width, height, thumb_status, indexed_at)
SELECT gen_random_uuid(), v.id, v.node_id, 'IMAGE',
       now() - ((row_number() OVER (ORDER BY n.name)) * interval '8 hours'),
       'MTIME', 64, 48, 'READY', now()
FROM file_versions v JOIN nodes n ON n.id = v.node_id
WHERE n.parent_id = '$HOME_ID' AND n.name LIKE 'perf-m3-%';
COMMIT;
SQL

SEEDED=$(psql_q "SELECT count(*) FROM media_assets ma JOIN nodes n ON n.id = ma.node_id \
  WHERE n.name LIKE 'perf-m3-%'")
echo "  已写入: $SEEDED 条"
if [ "$SEEDED" != "$N" ]; then
  echo "❌ 期望 $N 条,实际 $SEEDED 条"
  exit 1
fi

# 实际时间跨度(月):8h/条向过去铺开,N=1000 时约 11 个月;不再写死"12 个月"。
SPAN_MONTHS=$(psql_q "SELECT GREATEST(1, ceil(extract(epoch FROM (max(ma.taken_at) - min(ma.taken_at))) \
  / 2629800)::int) FROM media_assets ma JOIN nodes n ON n.id = ma.node_id \
  WHERE n.name LIKE 'perf-m3-%'")
SPAN_MONTHS=${SPAN_MONTHS:-?}

# 登录(只读测量,不需要 CSRF)
LOGIN_CODE=$(curl -s -c "$COOKIE" -o /dev/null -w '%{http_code}' -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin-secret"}')
if [ "$LOGIN_CODE" != "200" ]; then
  echo "❌ 管理员登录失败(HTTP $LOGIN_CODE)"
  exit 1
fi

URL="$API/media/timeline?limit=60"
FIRST_CODE=$(curl -s -o "$BODY" -w '%{http_code}' -b "$COOKIE" "$URL")
if [ "$FIRST_CODE" != "200" ]; then
  echo "❌ 首屏请求失败(HTTP $FIRST_CODE)"
  exit 1
fi
ITEMS=$(python3 - "$BODY" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1]))
except Exception:
    print("ERR"); raise SystemExit
print(len(data) if isinstance(data, list) else "ERR")
PY
)
if [ "$ITEMS" != "60" ]; then
  echo "❌ 首屏应返回 60 条,实际 $ITEMS 条"
  exit 1
fi

timed_call() { # timed_call <标签> -> 0 表示 HTTP 200;时间写入 LAST_TIME
  local res code
  res=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -b "$COOKIE" "$URL")
  code=${res%% *}
  LAST_TIME=${res##* }
  if [ "$code" != "200" ]; then
    echo "  ❌ $1 HTTP $code(期望 200)"
    return 1
  fi
  return 0
}

echo "预热 $WARMUP 次 + 计时 $ROUNDS 次: $URL"
i=0
while [ "$i" -lt "$WARMUP" ]; do
  i=$((i+1))
  timed_call "预热 $i" || exit 1
done

TIMES=()
i=0
while [ "$i" -lt "$ROUNDS" ]; do
  i=$((i+1))
  timed_call "第 $i 次" || exit 1
  TIMES+=("$LAST_TIME")
  printf '  第 %d 次: %ss\n' "$i" "$LAST_TIME"
done

MEDIAN=$(printf '%s\n' "${TIMES[@]}" | python3 -c '
import sys
ts = sorted(float(x) for x in sys.stdin if x.strip())
n = len(ts)
mid = ts[n // 2] if n % 2 else (ts[n // 2 - 1] + ts[n // 2]) / 2
print("%.4f" % mid)
')
WITHIN=$(python3 -c "import sys; print('yes' if float('$MEDIAN') <= $BUDGET_SECONDS else 'no')")

echo
echo "== 口径 =="
echo "  数据: $SEEDED 条 media_assets(taken_at 从 now() 起按 8h 间隔向过去铺开,跨度约 ${SPAN_MONTHS} 个月;thumb_status=READY,indexed_at=now())"
echo "  复用: 全部使用同一已入库媒体的 manifest_sha256/size_bytes(blob 与缩略图各一份)"
echo "  端点: GET /api/v1/media/timeline?limit=60(返回 $ITEMS 条)"
echo "  方法: $WARMUP 次预热 + $ROUNDS 次 curl time_total(每次断言 HTTP 200),取中位数;预算 ≤ ${BUDGET_SECONDS}s"
echo "== 结果: N=$SEEDED 中位数=${MEDIAN}s $ROUNDS 次=[${TIMES[*]}] 判定=$WITHIN =="

# 清理统一由 trap 执行(正常结束与早退一致);PNAS_PERF_KEEP=1 保留现场。
# 保留 1000 行会让下一次 acceptance-m3.sh 的 media.reindex 排空超时(调度器 5s/20 条)。
[ "$WITHIN" = "yes" ] || exit 1
