#!/bin/bash
# dsh-android 一键构建（PC / Git Bash 入口）
# 用法: KEYSTORE_PASS=<keystore 口令> bash build.sh
# 工具链路径在 devhome/payload/build/env.sh；本文件只管入口 + 签名口令。
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
export DSH_DEV_HOME="${DSH_DEV_HOME:-$ROOT/devhome/payload}"
export KEYSTORE_PASS="${KEYSTORE_PASS:?请先设置 keystore 口令: KEYSTORE_PASS=xxx bash build.sh}"
export KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-dsh}"
unset MSYS_NO_PATHCONV
cd "$ROOT"
sh woaiys3-app/android-app/build.sh
