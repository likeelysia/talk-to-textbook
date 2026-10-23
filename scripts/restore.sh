#!/usr/bin/env bash
# 恢复备份到隔离目录:先校验 checksum,再解压;绝不覆盖非空目录。
# 用法: bash scripts/restore.sh <备份.tar.gz> <空目标目录>
set -euo pipefail

ARCHIVE="${1:?用法: restore.sh <备份.tar.gz> <空目标目录>}"
TARGET="${2:?必须指定空的隔离目标目录}"

if [[ ! -f "$ARCHIVE" ]]; then
  echo "备份不存在: $ARCHIVE" >&2
  exit 1
fi
if [[ ! -f "$ARCHIVE.sha256" ]]; then
  echo "缺少校验文件: $ARCHIVE.sha256;拒绝恢复。" >&2
  exit 2
fi
if [[ -e "$TARGET" && -n "$(ls -A "$TARGET" 2>/dev/null || true)" ]]; then
  echo "目标目录非空: $TARGET(为了安全,请换一个空目录)" >&2
  exit 3
fi

echo "校验归档..."
if ! sha256sum -c "$ARCHIVE.sha256"; then
  echo "校验失败,拒绝恢复。" >&2
  exit 4
fi

mkdir -p "$TARGET"
if ! tar -xzf "$ARCHIVE" -C "$TARGET"; then
  echo "解压失败。" >&2
  exit 5
fi

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
