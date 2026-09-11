#!/usr/bin/env bash
# PNAS M2 验收脚本:两用户隔离 / 分块上传(含"中断后续传") / Range 下载 / 误删恢复
# 用法: bash scripts/acceptance-m2.sh [BASE_URL]   默认 http://localhost:8080
set -uo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
WORK="$(mktemp -d)"
COOKIE_ADMIN="$WORK/admin.cookie"
COOKIE_BOB="$WORK/bob.cookie"
PASS=0
FAIL=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
check(){ # check <desc> <actual> <expected>
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1 (期望 $3,实际 $2)"; fi
}

echo "== PNAS M2 验收: $BASE =="
echo "[1] 健康检查"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")
check "GET /actuator/health" "$code" "200"

echo "[2] 管理员登录(bootstrap 账号)"
login() { # login <user> <pass> <cookiejar> -> 输出 csrf
  local jar="$1" user="$2" pass="$3"
  local tmp="$WORK/login-$$.hdr"
  curl -s -D "$tmp" -c "$jar" -o /dev/null -X POST "$API/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}"
  grep -i '^x-csrf-token:' "$tmp" | awk '{print $2}' | tr -d '\r'
}
CSRF_ADMIN=$(login "$COOKIE_ADMIN" admin admin-secret)
if [ -n "$CSRF_ADMIN" ]; then ok "管理员登录并取得 CSRF"; else bad "管理员登录失败"; fi

echo "[3] 创建成员账号"
BOB="bob$RANDOM"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" -X POST "$API/users" \
  -H 'Content-Type: application/json' -H "X-CSRF: $CSRF_ADMIN" \
  -d "{\"username\":\"$BOB\",\"displayName\":\"Bob\",\"password\":\"secret123\"}")
check "POST /users(建成员)" "$code" "200"

CSRF_BOB=$(login "$COOKIE_BOB" "$BOB" secret123)
if [ -n "$CSRF_BOB" ]; then ok "成员登录并取得 CSRF"; else bad "成员登录失败"; fi

echo "[4] 两用户隔离"
HOME_JSON=$(curl -s -b "$COOKIE_ADMIN" -X POST "$API/nodes" \
  -H 'Content-Type: application/json' -H "X-CSRF: $CSRF_ADMIN" \
  -d '{"parentId":null,"name":"验收-目录"}')
ADMIN_HOME=$(printf '%s' "$HOME_JSON" | sed -n 's/.*"parentId":"\([^"]*\)".*/\1/p')
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_BOB" "$API/nodes?parentId=$ADMIN_HOME")
check "成员读取管理员目录被拒" "$code" "403"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" "$API/nodes")
check "管理员可列自己的目录" "$code" "200"

echo "[5] 分块上传(4 MiB 块)并校验下载一致性"
BIG="$WORK/big.bin"
dd if=/dev/urandom of="$BIG" bs=1024 count=4100 >/dev/null 2>&1   # 4100 KiB > 4 MiB → 2 块
SIZE=$(wc -c < "$BIG" | tr -d ' ')
SHA_LOCAL=$(shasum -a 256 "$BIG" | awk '{print $1}')

start_upload() { # start_upload <name> -> uploadId
  local name="$1"
  curl -s -b "$COOKIE_ADMIN" -X POST "$API/uploads" \
    -H 'Content-Type: application/json' -H "X-CSRF: $CSRF_ADMIN" \
    -d "{\"destParentId\":\"$ADMIN_HOME\",\"filename\":\"$name\",\"size\":$SIZE}" \
    | sed -n 's/.*"uploadId":"\([^"]*\)".*/\1/p'
}
put_chunk() { # put_chunk <uploadId> <seq> <file> <offset> <len>
  local uid="$1" seq="$2" file="$3" off="$4" len="$5"
  dd if="$file" of="$WORK/chunk.bin" bs=1 skip="$off" count="$len" >/dev/null 2>&1
  local sha; sha=$(shasum -a 256 "$WORK/chunk.bin" | awk '{print $1}')
  curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" -X PUT \
    "$API/uploads/$uid/chunks/$seq" \
    -H "X-CSRF: $CSRF_ADMIN" -H "X-Sha256: $sha" \
    -H 'Content-Type: application/octet-stream' --data-binary @"$WORK/chunk.bin"
}

UID1=$(start_upload "验收-大文件.bin")
CHUNK=4194304
code=$(put_chunk "$UID1" 0 "$BIG" 0 "$CHUNK")
check "上传第 0 块" "$code" "204"
code=$(put_chunk "$UID1" 1 "$BIG" "$CHUNK" "$((SIZE-CHUNK))")
check "上传第 1 块" "$code" "204"
NODE_ID=$(curl -s -b "$COOKIE_ADMIN" -X POST "$API/uploads/$UID1/complete" \
  -H "X-CSRF: $CSRF_ADMIN" | sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p')
if [ -n "$NODE_ID" ]; then ok "complete 返回 nodeId"; else bad "complete 失败"; fi

curl -s -b "$COOKIE_ADMIN" -o "$WORK/downloaded.bin" "$API/nodes/$NODE_ID/content"
SHA_REMOTE=$(shasum -a 256 "$WORK/downloaded.bin" | awk '{print $1}')
check "下载内容哈希一致" "$SHA_REMOTE" "$SHA_LOCAL"

echo "[6] 断点续传(中断后仅补传缺失块)"
UID2=$(start_upload "验收-续传.bin")
put_chunk "$UID2" 0 "$BIG" 0 "$CHUNK" >/dev/null   # 传第 0 块后"中断"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" -X POST "$API/uploads/$UID2/complete" \
  -H "X-CSRF: $CSRF_ADMIN")
check "缺少分块时 complete 被拒" "$code" "400"
put_chunk "$UID2" 1 "$BIG" "$CHUNK" "$((SIZE-CHUNK))" >/dev/null
NODE2=$(curl -s -b "$COOKIE_ADMIN" -X POST "$API/uploads/$UID2/complete" \
  -H "X-CSRF: $CSRF_ADMIN" | sed -n 's/.*"nodeId":"\([^"]*\)".*/\1/p')
curl -s -b "$COOKIE_ADMIN" -o "$WORK/resumed.bin" "$API/nodes/$NODE2/content"
check "续传后内容哈希一致" "$(shasum -a 256 "$WORK/resumed.bin" | awk '{print $1}')" "$SHA_LOCAL"

echo "[7] Range 下载"
code=$(curl -s -o "$WORK/range.bin" -w '%{http_code}' -b "$COOKIE_ADMIN" \
  -H 'Range: bytes=0-15' "$API/nodes/$NODE_ID/content")
check "Range 请求返回 206" "$code" "206"
check "Range 返回 16 字节" "$(wc -c < "$WORK/range.bin" | tr -d ' ')" "16"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" \
  -H "Range: bytes=$((SIZE+10))-" "$API/nodes/$NODE_ID/content")
check "越界 Range 返回 416" "$code" "416"

echo "[8] 误删恢复(回收站)"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" -X DELETE "$API/nodes/$NODE_ID" \
  -H "X-CSRF: $CSRF_ADMIN")
check "删除进回收站" "$code" "204"
TRASH=$(curl -s -b "$COOKIE_ADMIN" "$API/nodes?trash=true")
case "$TRASH" in *"$NODE_ID"*) ok "回收站可见该节点";; *) bad "回收站中找不到节点";; esac
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_ADMIN" -X POST "$API/nodes/$NODE_ID/restore" \
  -H "X-CSRF: $CSRF_ADMIN")
check "从回收站恢复" "$code" "200"
curl -s -b "$COOKIE_ADMIN" -o "$WORK/restored.bin" "$API/nodes/$NODE_ID/content"
check "恢复后内容哈希一致" "$(shasum -a 256 "$WORK/restored.bin" | awk '{print $1}')" "$SHA_LOCAL"

echo
echo "== 结果: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ] || exit 1
