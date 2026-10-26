#!/usr/bin/env bash
# 把上游 dsh-memory 同步进打包源 memory/。
#
# 为什么需要这个脚本：memory/ 是 G:\code\dsh-memory 的**手工副本**——build.sh 从它
# 拷进 APK 的 node_modules。忘了同步 = 装进手机的是老代码，而且在 PC 上测不出来。
# 2026-10-25 就这样漏过一次：主仓库 0.2.4，打包源还停在 0.1.2。
#
# 用法：./sync-dsh-memory.sh [上游路径]
# 默认上游：G:\code\dsh-memory（可用环境变量 DSH_MEMORY_SRC 覆盖）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:-${DSH_MEMORY_SRC:-/g/code/dsh-memory}}"
DST="$HERE/memory"

# 只同步运行期与构建期需要的东西；内部文档（任务书/验收报告/TODO）不进仓库
ITEMS=(src lib e2e README.md package.json cordis.patch.yml tsconfig.json)

if [ ! -f "$SRC/package.json" ]; then
  echo "!! 上游不存在：$SRC/package.json" >&2
  exit 1
fi

SRC_VER=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SRC/package.json" | head -1)
OLD_VER=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$DST/package.json" | head -1)
echo "上游 $SRC → $DST"
echo "版本 $OLD_VER → $SRC_VER"

for item in "${ITEMS[@]}"; do
  [ -e "$SRC/$item" ] || continue
  rm -rf "$DST/$item"
  cp -r "$SRC/$item" "$DST/$item"
done

# 校验：源码必须逐字节一致，否则说明复制中途出了问题
if ! diff -rq "$SRC/src" "$DST/src" >/dev/null; then
  echo "!! src/ 与上游不一致，同步失败" >&2
  exit 1
fi
if ! diff -rq "$SRC/lib" "$DST/lib" >/dev/null; then
  echo "!! lib/ 与上游不一致（上游是不是没构建？先 npm run build）" >&2
  exit 1
fi

# 记下同步来源，方便事后追溯是哪一版进的包
UPSTREAM_HEAD=$(git -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo 'unknown')
printf '%s v%s @ %s\n' "$(date +%Y-%m-%d)" "$SRC_VER" "$UPSTREAM_HEAD" > "$DST/.synced-from"
echo "已记录来源：$(cat "$DST/.synced-from")"

cd "$HERE"
git add memory/ 2>/dev/null || true
echo
echo "已暂存 memory/。别忘了提交："
echo "  git commit -m '同步 dsh-memory 到 $SRC_VER'"
