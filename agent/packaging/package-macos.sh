#!/usr/bin/env bash
# 构建 macOS .dmg:jpackage + 手工 bundle(含 Jackson 依赖的 Class-Path)
# 用法: bash agent/packaging/package-macos.sh
# 产物: agent/target/package/PNAS Agent-1.0.0.dmg
#
# 说明:
#   - 只做 package,不做 clean(避免清掉已构建产物);
#   - jpackage 只把 --main-jar 放进应用 classpath,Jackson 依赖需拷贝到
#     bundle/lib 并通过 jar 清单的 Class-Path 声明;
#   - 本机首次运行 jpackage 会生成 dmg,耗时约数十秒。
#
# 限制:在受限沙箱(如 DSH workspace-write)中,jpackage 的 dmg 阶段会调用 hdiutil
# 并报 "hdiutil: create failed - 操作不被允许";这不是脚本错误。可在普通 macOS
# 终端重跑,或临时改用 --type app-image 验证 bundle/依赖(见 docs/progress.md)。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MVN="${MVN:-/Users/xiao/hui/soft/maven3.8/bin/mvn}"
OUT="$ROOT/agent/target/package"
BUNDLE="$OUT/bundle"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "此脚本仅适用于 macOS(当前: $(uname -s));Windows 见 windows.md" >&2
  exit 1
fi

# 保证 JAVA_HOME 是带 jpackage 的 JDK 21:本机默认 JDK 8 时回退到仓库内便携 JDK。
java_major() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1
}
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jpackage" ] \
    && [ "$(java_major "$JAVA_HOME/bin/java")" -ge 21 ] 2>/dev/null; then
  :
else
  JAVA_HOME="$ROOT/.tools/jdk-21.0.12.1+1/Contents/Home"
fi
export JAVA_HOME
if [ ! -x "$JAVA_HOME/bin/jpackage" ]; then
  echo "未找到 jpackage: $JAVA_HOME/bin/jpackage(需要 JDK 21)" >&2
  exit 1
fi

cd "$ROOT/agent"
"$MVN" -o -s ../.tools/maven-settings.xml package -DskipTests

# pom 未固定 finalName,构建产物为 pnas-agent-<version>.jar;兼容手工构建的 pnas-agent.jar。
JAR="$(ls -1 target/pnas-agent.jar target/pnas-agent-*.jar 2>/dev/null \
  | grep -v '\.original$' | head -1 || true)"
if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
  echo "未找到 agent jar(target/pnas-agent*.jar)" >&2
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$BUNDLE/lib"
cp "$JAR" "$BUNDLE/pnas-agent.jar"
for j in jackson-databind jackson-core jackson-annotations; do
  found=""
  for base in "$ROOT/.tools/m2repo" "$HOME/.m2/repository"; do
    [ -d "$base/com/fasterxml/jackson" ] || continue
    found="$(find "$base/com/fasterxml/jackson" -name "$j-2.17.2.jar" 2>/dev/null | head -1 || true)"
    [ -n "$found" ] && break
  done
  if [ -z "$found" ]; then
    echo "缺少依赖 jar: $j-2.17.2.jar(检查 .tools/m2repo 或 ~/.m2/repository)" >&2
    exit 1
  fi
  cp "$found" "$BUNDLE/lib/"
done

# 让 jar 的 Class-Path 指向 lib/(jpackage 只把 main jar 放进 classpath)。
MANIFEST="$(mktemp)"
trap 'rm -f "$MANIFEST"' EXIT
printf 'Class-Path: lib/jackson-databind-2.17.2.jar lib/jackson-core-2.17.2.jar lib/jackson-annotations-2.17.2.jar\n' \
  > "$MANIFEST"
"$JAVA_HOME/bin/jar" --update --manifest="$MANIFEST" --file "$BUNDLE/pnas-agent.jar"

# jpackage 要求 app-version 首段为 >0 的整数(项目版本 0.1.0-SNAPSHOT 会被拒绝),
# 因此打包版本固定为 1.0.0;jar 内部版本仍由 pom 管理。
"$JAVA_HOME/bin/jpackage" \
  --type dmg --name "PNAS Agent" --app-version 1.0.0 \
  --input "$BUNDLE" --main-jar pnas-agent.jar --main-class com.pnas.agent.Main \
  --dest "$OUT" --java-options "-Xmx256m"
echo "打包完成: $OUT"
