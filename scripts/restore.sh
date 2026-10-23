#!/usr/bin/env bash
# 恢复备份到“隔离目录”,默认不覆盖项目真实数据。
# 用法: ./scripts/restore.sh <备份.tar.gz> <目标目录>
# 真实数据恢复:确认停机后,把目标目录内容同步回项目(或设置 ALLOW_OVERWRITE=1 并直接指向项目)
set -euo pipefail

ARCHIVE="${1:?用法: restore.sh <备份.tar.gz> <目标目录>}"
TARGET="${2:?必须指定隔离目标目录}"

if [[ ! -f "$ARCHIVE" ]]; then
  echo "备份不存在: $ARCHIVE" >&2
  exit 1
fi
if [[ -e "$TARGET" && -n "$(ls -A "$TARGET" 2>/dev/null || true)" ]]; then
  echo "目标目录非空: $TARGET(为了安全,请换一个空目录)" >&2
  exit 1
fi

mkdir -p "$TARGET"
tar -xzf "$ARCHIVE" -C "$TARGET"

echo "恢复完成: $TARGET"
for p in knowledge/base.json data data/conversations data/memory data/notes sessions; do
  if [[ -e "$TARGET/$p" ]]; then
    echo "  ✓ $p"
  else
    echo "  ! 缺少 $p" >&2
  fi
done
echo "文件数: $(find "$TARGET" -type f | wc -l)"
du -sh "$TARGET"
