#!/bin/bash
# 权限规则的主机侧自测（不需要手机、不需要完整 APK 构建）。
#
# 用法：bash woaiys3-app/android-app/tools/guard-tests/run.sh
#
# 它做两件事：
#   1. GuardRuleTest —— 分级表逐条跑（confirm 下 37 条，plain 下 35 条）；
#   2. ReverseTest  —— confirm 下把 GuardImpl 换成"一加载就抛异常"，断言仍然 deny（fail-closed）。
#
# 需要 ANDROID_JAR（默认 /c/tmp/dsmod_build/android.jar）和 JDK 17+（默认用 JAVA_BIN）。
set -e

P="$(cd "$(dirname "$0")/../.." && pwd)"     # android-app/
ANDROID_JAR="${ANDROID_JAR:-/c/tmp/dsmod_build/android.jar}"
JAVA_BIN="${JAVA_BIN:-/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin}"
[ -x "$JAVA_BIN/javac" ] || { echo "!! 找不到 javac：$JAVA_BIN/javac（export JAVA_BIN=...）"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "!! 找不到 android.jar：$ANDROID_JAR（export ANDROID_JAR=...）"; exit 1; }

# ⚠ Windows(Git Bash)：MSYS 只转换单条路径参数，不转换"分号分隔的 path 列表"→
#   java -cp 必须给 Windows 形式的绝对路径，否则整条 classpath 被当成一个相对路径。
WIN_OUT="$(cygpath -w "${TMPDIR:-/tmp}/dsh-guard-tests" 2>/dev/null || echo "${TMPDIR:-/tmp}/dsh-guard-tests")"
OUT="${TMPDIR:-/tmp}/dsh-guard-tests"
WIN_AJ="$(cygpath -w "$ANDROID_JAR" 2>/dev/null || echo "$ANDROID_JAR")"
rm -rf "$OUT"; mkdir -p "$OUT"

run_flavor() {
  _fl="$1"
  _d="$OUT/$_fl"
  mkdir -p "$_d/src/com/deepseek/harness"
  cp "$P/src/com/deepseek/harness/flavor/BuildInfo.$_fl.java" "$_d/src/com/deepseek/harness/BuildInfo.java"
  "$JAVA_BIN/javac" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
    -bootclasspath "$ANDROID_JAR" -d "$_d/classes" \
    "$P/src/com/deepseek/harness/guard"/*.java \
    "$P/src/com/deepseek/harness/LocalAuth.java" \
    "$P/src/com/deepseek/harness/Grant.java" \
    "$_d/src/com/deepseek/harness/BuildInfo.java" \
    "$P/tools/guard-tests/GuardRuleTest.java" 2>/dev/null
  echo "== GuardRuleTest: $_fl =="
  "$JAVA_BIN/java" -cp "$WIN_OUT\\$_fl\\classes;$WIN_AJ" com.deepseek.harness.guard.GuardRuleTest
}

run_flavor confirm
run_flavor plain

# ---- 反例：规则模块加载即抛异常 → 必须 deny ----
BAD="$OUT/throwing"
mkdir -p "$BAD/src/com/deepseek/harness/guard"
cat > "$BAD/src/com/deepseek/harness/guard/GuardImpl.java" <<'EOF'
package com.deepseek.harness.guard;

/** 测试替身：模拟「规则模块坏了」（静态初始化抛异常）。 */
final class GuardImpl {
    static { if (true) throw new RuntimeException("simulated broken rules"); }
    private GuardImpl() {}
    static Decision check(Guard.Request r) { return Decision.allow(); }
    static void noteClipboard(String text) { }
}
EOF
for _fl in confirm plain; do
  mkdir -p "$BAD/$_fl" "$BAD/$_fl-src/com/deepseek/harness"
  # BuildInfo 模板文件名带 flavor 后缀，javac 要求 public 类与文件名一致 → 先拷成 BuildInfo.java
  cp "$P/src/com/deepseek/harness/flavor/BuildInfo.$_fl.java" "$BAD/$_fl-src/com/deepseek/harness/BuildInfo.java"
  "$JAVA_BIN/javac" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
    -bootclasspath "$ANDROID_JAR" -d "$BAD/$_fl" \
    "$BAD/src/com/deepseek/harness/guard/GuardImpl.java" \
    "$P/src/com/deepseek/harness/guard/Guard.java" \
    "$P/src/com/deepseek/harness/guard/Decision.java" \
    "$P/src/com/deepseek/harness/guard/Channel.java" \
    "$P/src/com/deepseek/harness/guard/Consent.java" \
    "$P/src/com/deepseek/harness/LocalAuth.java" \
    "$P/src/com/deepseek/harness/Grant.java" \
    "$BAD/$_fl-src/com/deepseek/harness/BuildInfo.java" \
    "$P/tools/guard-tests/ReverseTest.java" 2>/dev/null
  echo "== ReverseTest: $_fl + 规则模块抛异常 =="
  "$JAVA_BIN/java" -cp "$WIN_OUT\\throwing\\$_fl;$WIN_AJ" com.deepseek.harness.guard.ReverseTest
done

echo "ALL GUARD TESTS PASSED"
