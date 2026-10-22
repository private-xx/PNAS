#!/usr/bin/env bash
# PNAS M3 媒体验收:入库 / 缩略图 / 时间线 / 日期桶 / 跨用户隔离 / 回收站 / 视频 Range / 重建幂等
# 用法: bash scripts/acceptance-m3.sh [BASE_URL]      默认 http://localhost:8080
#
# 前置:
#   1) 服务以 PNAS_JOBS_SCHEDULER_ENABLED=true 启动,否则 media.scan/media.thumb 不会被领取;
#   2) 本机有 Java 11+(用 JDK 自带 ImageIO 生成真实 JPEG)与 python3;
#   3) 账号 admin/admin-secret(bootstrap)。
# 脚本幂等:每轮自建随机成员与随机文件名,所有断言只针对本轮上传的节点。
set -uo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PASS=0
FAIL=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
check() { # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1 (期望 $3,实际 $2)"; fi
}
ge() { # ge <数字> <下界> -> yes/no(空值安全)
  if [ -n "${1:-}" ] && [ "$1" -ge "$2" ] 2>/dev/null; then echo yes; else echo no; fi
}

echo "== PNAS M3 验收: $BASE =="

# ---------------------------------------------------------------- Java(生成真实 JPEG)
JAVA_BIN=""
for cand in "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
            "$ROOT/.tools/jdk-21.0.12.1+1/Contents/Home/bin/java" \
            "$(command -v java 2>/dev/null || true)"; do
  [ -n "$cand" ] && [ -x "$cand" ] || continue
  major=$("$cand" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)
  if [ "${major:-0}" -ge 11 ]; then JAVA_BIN="$cand"; break; fi
done
if [ -z "$JAVA_BIN" ]; then
  bad "需要 Java 11+ 生成测试 JPEG(可设 JAVA_HOME)"
  echo
  echo "== 结果: PASS=$PASS FAIL=$FAIL =="
  exit 1
fi

cat > "$WORK/Gen.java" <<'JAVA'
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 生成一张真实可解码的 JPEG(64x48);ImageIO 保证 Huffman 表等标记完整。 */
public class Gen {
    public static void main(String[] a) throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 48; y++) {
                img.setRGB(x, y, ((x * 4) << 16) | ((y * 5) << 8) | 0x40);
            }
        }
        ImageIO.write(img, "jpg", new File(a[0]));
    }
}
JAVA

gen_jpeg() { "$JAVA_BIN" "$WORK/Gen.java" "$1"; }

echo "[1] 健康检查"
check "GET /actuator/health" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")" "200"

echo "[2] 登录 / 建成员"
login() { # login <user> <pass> <cookiejar> <tag> -> http_code(CSRF 写入 $WORK/<tag>.csrf)
  local code
  code=$(curl -s -D "$WORK/$4.hdr" -c "$3" -o /dev/null -X POST "$API/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" -w '%{http_code}')
  grep -i '^x-csrf-token:' "$WORK/$4.hdr" | awk '{print $2}' | tr -d '\r' > "$WORK/$4.csrf"
  printf '%s' "$code"
}

COOKIE_ADMIN="$WORK/admin.cookie"
COOKIE_MEMBER="$WORK/member.cookie"
check "管理员登录" "$(login admin admin-secret "$COOKIE_ADMIN" admin)" "200"
CSRF_ADMIN=$(cat "$WORK/admin.csrf")
check "取得管理员 CSRF" "$([ -n "$CSRF_ADMIN" ] && echo yes || echo no)" "yes"

MEMBER="m3-bob-$RANDOM"
# 注:JSON 体先落到变量再传给 curl。macOS 自带 bash 3.2 解析 "$( ... )" 内嵌转义双引号时
# 会错误断词(把 body 拆成多个参数、丢掉花括号与逗号),因此不在命令替换里写内联 JSON。
create_member() { # create_member <username> -> http_code
  local body
  body=$(printf '{"username":"%s","displayName":"Bob","password":"secret123"}' "$1")
  curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" -X POST "$API/users" \
    -H 'Content-Type: application/json' -H "X-CSRF: $CSRF_ADMIN" -d "$body"
}
MEMBER_CODE=$(create_member "$MEMBER")
check "POST /users(建成员)" "$MEMBER_CODE" "200"
check "成员登录" "$(login "$MEMBER" secret123 "$COOKIE_MEMBER" member)" "200"

# 家目录:建一个临时目录,响应里的 parentId 即"我的文件"根(M2 已验证的行为)
CREATE=$(curl -s -b "$COOKIE_ADMIN" -X POST "$API/nodes" -H 'Content-Type: application/json' \
  -H "X-CSRF: $CSRF_ADMIN" -d "{\"parentId\":null,\"name\":\"m3-acc-$RANDOM\"}")
ADMIN_HOME=$(printf '%s' "$CREATE" | python3 -c \
  'import sys,json;print(json.load(sys.stdin).get("parentId") or "")' 2>/dev/null)
check "取得管理员家目录 id" "$([ -n "$ADMIN_HOME" ] && echo yes || echo no)" "yes"

echo "[3] 上传 3 张真实 JPEG + 1 个 MP4"
gen_jpeg "$WORK/a.jpg"; gen_jpeg "$WORK/b.jpg"; gen_jpeg "$WORK/c.jpg"
cp "$WORK/a.jpg" "$WORK/d.jpg"          # 第三张独立文件(内容相同 → 顺带覆盖内容寻址去重)
head -c 2048 /dev/zero > "$WORK/clip.mp4"
check "测试 JPEG 是合法图片" "$(file -b --mime-type "$WORK/a.jpg")" "image/jpeg"

upload_one() { # upload_one <jar> <csrf> <file> <name> -> http_code(complete 体写入 $WORK/last.json)
  local jar="$1" tok="$2" file="$3" name="$4" size id body up
  size=$(wc -c < "$file" | tr -d ' ')
  up=$(curl -s -b "$jar" -H "X-CSRF: $tok" -H 'Content-Type: application/json' \
        -X POST "$API/uploads" -d "{\"destParentId\":\"$ADMIN_HOME\",\"filename\":\"$name\",\"size\":$size}")
  id=$(printf '%s' "$up" | python3 -c 'import sys,json;print(json.load(sys.stdin)["uploadId"])')
  curl -s -o /dev/null -b "$jar" -H "X-CSRF: $tok" \
    -H 'Content-Type: application/octet-stream' \
    -H "X-Sha256: $(shasum -a 256 "$file" | awk '{print $1}')" \
    -X PUT "$API/uploads/$id/chunks/0" --data-binary "@$file"
  body=$(curl -s -b "$jar" -H "X-CSRF: $tok" -X POST "$API/uploads/$id/complete" \
    -H 'Content-Type: application/json' -w $'\n%{http_code}')
  printf '%s' "${body%$'\n'*}" > "$WORK/last.json"
  printf '%s' "${body##*$'\n'}"
}
node_of_last() {
  python3 -c 'import sys,json;print(json.load(open(sys.argv[1]))["nodeId"])' "$WORK/last.json"
}

check "上传照片 a" "$(upload_one "$COOKIE_ADMIN" "$CSRF_ADMIN" "$WORK/a.jpg" "m3-a-$RANDOM.jpg")" "200"
NODE_A=$(node_of_last)
check "上传照片 b" "$(upload_one "$COOKIE_ADMIN" "$CSRF_ADMIN" "$WORK/b.jpg" "m3-b-$RANDOM.jpg")" "200"
NODE_B=$(node_of_last)
check "上传照片 c" "$(upload_one "$COOKIE_ADMIN" "$CSRF_ADMIN" "$WORK/c.jpg" "m3-c-$RANDOM.jpg")" "200"
NODE_C=$(node_of_last)
check "上传视频"   "$(upload_one "$COOKIE_ADMIN" "$CSRF_ADMIN" "$WORK/clip.mp4" "m3-v-$RANDOM.mp4")" "200"
NODE_V=$(node_of_last)
OUR_NODES="$NODE_A,$NODE_B,$NODE_C,$NODE_V"

echo "[4] 等待媒体任务(media.scan / media.thumb,≤60s)"
drain() { # drain <秒> -> 0 已排空
  local deadline=$((SECONDS + ${1:-60})) q=unknown r=unknown
  while [ "$SECONDS" -lt "$deadline" ]; do
    q=$(curl -s -b "$COOKIE_ADMIN" "$API/jobs?state=QUEUED" \
      | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null || echo "?")
    r=$(curl -s -b "$COOKIE_ADMIN" "$API/jobs?state=RUNNING" \
      | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null || echo "?")
    [ "$q" = "0" ] && [ "$r" = "0" ] && return 0
    sleep 2
  done
  echo "  队列未排空: QUEUED=$q RUNNING=$r"
  return 1
}
if drain 60; then ok "媒体任务队列已排空"; else bad "媒体任务 60s 内未排空"; fi

curl -s -b "$COOKIE_ADMIN" "$API/jobs" > "$WORK/jobs.json"
FAILED_JOBS=$(OUR_NODES="$OUR_NODES" python3 - "$WORK/jobs.json" <<'PY'
import json, os, sys
ours = set(os.environ["OUR_NODES"].split(","))
bad = []
for j in json.load(open(sys.argv[1])):
    if j.get("type") not in ("media.scan", "media.thumb"):
        continue
    if j.get("state") not in ("FAILED", "DEAD"):
        continue
    payload = j.get("payload") or {}
    if str(payload.get("nodeId")) in ours:
        bad.append("%s/%s: %s" % (j["type"], j["state"], (j.get("error") or "")[:200]))
print("; ".join(bad))
PY
)
if [ -z "$FAILED_JOBS" ]; then ok "无失败的媒体任务"; else bad "媒体任务失败: $FAILED_JOBS"; fi

echo "[5] 时间线 / 缩略图 / 日期桶 / 日期相册"
curl -s -b "$COOKIE_ADMIN" "$API/media/timeline?limit=200" > "$WORK/timeline.json"
ITEMS=$(OUR_NODES="$OUR_NODES" python3 - "$WORK/timeline.json" <<'PY'
import json, os, sys
ours = set(os.environ["OUR_NODES"].split(","))
print(sum(1 for i in json.load(open(sys.argv[1])) if i["nodeId"] in ours))
PY
)
check "时间线含本次 4 项" "$ITEMS" "4"

asset_of() { # asset_of <nodeId>
  python3 - "$WORK/timeline.json" "$1" <<'PY'
import json, sys
items = json.load(open(sys.argv[1]))
node = sys.argv[2]
hit = [i["assetId"] for i in items if i["nodeId"] == node]
print(hit[0] if hit else "")
PY
}
ASSET_IMG=$(asset_of "$NODE_A")
ASSET_VID=$(asset_of "$NODE_V")
check "取到图片 assetId" "$([ -n "$ASSET_IMG" ] && echo yes || echo no)" "yes"
check "取到视频 assetId" "$([ -n "$ASSET_VID" ] && echo yes || echo no)" "yes"

THUMB_CODE=$(curl -s -o "$WORK/thumb.jpg" -w '%{http_code}' \
  -b "$COOKIE_ADMIN" "$API/media/assets/$ASSET_IMG/thumb?size=256")
check "缩略图 200" "$THUMB_CODE" "200"
check "缩略图是 JPEG" "$(file -b --mime-type "$WORK/thumb.jpg" 2>/dev/null)" "image/jpeg"

BUCKET_SUM=$(curl -s -b "$COOKIE_ADMIN" "$API/media/timeline/buckets?year=$(date +%Y)" \
  | python3 -c 'import sys,json;print(sum(b["count"] for b in json.load(sys.stdin)))' 2>/dev/null)
check "本年月桶合计 ≥4" "$(ge "$BUCKET_SUM" 4)" "yes"

ALBUM_YEARS=$(curl -s -b "$COOKIE_ADMIN" "$API/media/albums/dates" \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null)
check "日期相册非空" "$(ge "$ALBUM_YEARS" 1)" "yes"

echo "[6] 跨用户隔离"
check "成员读管理员缩略图 404" "$(curl -s -o /dev/null -w '%{http_code}' \
  -b "$COOKIE_MEMBER" "$API/media/assets/$ASSET_IMG/thumb?size=256")" "404"
check "成员时间线不含管理员媒体" \
  "$(curl -s -b "$COOKIE_MEMBER" "$API/media/timeline?limit=200" | grep -c "$ASSET_IMG")" "0"

echo "[7] 视频 Range"
check "视频 Range 返回 206" "$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" \
  -H 'Range: bytes=0-15' "$API/media/assets/$ASSET_VID/content")" "206"
check "Content-Range 为 bytes 0-15/" \
  "$(curl -s -D - -o /dev/null -b "$COOKIE_ADMIN" -H 'Range: bytes=0-15' \
     "$API/media/assets/$ASSET_VID/content" | grep -ci '^content-range: bytes 0-15/')" "1"

echo "[8] 回收站排除与恢复"
check "删除媒体节点" "$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" \
  -H "X-CSRF: $CSRF_ADMIN" -X DELETE "$API/nodes/$NODE_A")" "204"
check "删除后不在时间线" \
  "$(curl -s -b "$COOKIE_ADMIN" "$API/media/timeline?limit=200" | grep -c "$NODE_A")" "0"
check "从回收站恢复" "$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" \
  -H "X-CSRF: $CSRF_ADMIN" -X POST "$API/nodes/$NODE_A/restore")" "200"
check "恢复后回到时间线" \
  "$(curl -s -b "$COOKIE_ADMIN" "$API/media/timeline?limit=200" | grep -c "$NODE_A")" "1"

echo "[9] 重建索引幂等"
BEFORE=$(OUR_NODES="$OUR_NODES" python3 - "$WORK/timeline.json" <<'PY'
import json, os, sys
ours = set(os.environ["OUR_NODES"].split(","))
print(sum(1 for i in json.load(open(sys.argv[1])) if i["nodeId"] in ours))
PY
)
check "触发重建 202" "$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" \
  -H "X-CSRF: $CSRF_ADMIN" -X POST "$API/media/reindex")" "202"
if drain 60; then ok "重建任务已完成"; else bad "重建任务 60s 内未完成"; fi
curl -s -b "$COOKIE_ADMIN" "$API/media/timeline?limit=200" > "$WORK/timeline2.json"
AFTER=$(OUR_NODES="$OUR_NODES" python3 - "$WORK/timeline2.json" <<'PY'
import json, os, sys
ours = set(os.environ["OUR_NODES"].split(","))
print(sum(1 for i in json.load(open(sys.argv[1])) if i["nodeId"] in ours))
PY
)
check "重建后条目数不变" "$AFTER" "$BEFORE"

echo
echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ] || exit 1
