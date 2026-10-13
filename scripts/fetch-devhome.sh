#!/bin/bash
# fetch-devhome.sh —— 从上游 release APK 里挖出构建所需的 devhome。
#
# 为什么需要它：本仓库只收源码，**不含** arm64 的 node 运行时与 DSH 内核树
# （那两坨共 ~500MB，且是二进制）。它们就打在 woaiys3 的 release APK 的
# assets/payload.zip 里 —— 这个脚本把它挖出来摆成构建脚本认的布局。
#
# 用法: bash scripts/fetch-devhome.sh [上游 tag，默认 v1.19.0]
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:-v1.19.0}"
cd "$ROOT"
mkdir -p .cache && cd .cache

if [ ! -f DeepSeekHarness.apk ]; then
  echo "== 下载上游 APK（$TAG）=="
  gh release download "$TAG" --repo woaiys3/deepseek-harness-android-app \
     --pattern 'DeepSeekHarness.apk' --dir . --clobber
fi

echo "== 从 APK 提取 payload.zip =="
unzip -o -q DeepSeekHarness.apk assets/payload.zip

echo "== 解压成 devhome/payload =="
rm -rf "$ROOT/devhome"
mkdir -p "$ROOT/devhome/payload"
unzip -o -q assets/payload.zip -d "$ROOT/devhome/payload"

# dsh 家目录：payload 里叫 dshhome，构建脚本要 .dsh
cd "$ROOT/devhome/payload"
if [ -d dshhome ] && [ ! -d .dsh ]; then mv dshhome .dsh; fi

echo
echo "✅ devhome 就绪：$ROOT/devhome/payload"
echo "   下一步：生成签名密钥 + 构建"
echo "     keytool -genkeypair -keystore woaiys3-app/android-app/release.jks -alias dsh -keyalg RSA -keysize 2048 -validity 10950"
echo "     KEYSTORE_PASS=<你设的口令> bash build.sh"
