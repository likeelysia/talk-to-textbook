#!/usr/bin/env bash
# 恢复备份到隔离目录:校验“本次传入的归档”的摘要,再解压;绝不覆盖非空目录。
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

if ! command -v sha256sum >/dev/null 2>&1; then
  echo "系统没有 sha256sum,无法校验;拒绝恢复。" >&2
  exit 4
fi

# sidecar 只取第一列摘要,直接和本次传入的 ARCHIVE 比;
# 不按 sidecar 里的旧路径找文件,所以归档+sidecar 搬到别的目录/机器仍可用。
EXPECTED="$(awk 'NR==1{print $1}' "$ARCHIVE.sha256" | tr 'A-F' 'a-f')"
if ! [[ "$EXPECTED" =~ ^[0-9a-f]{64}$ ]]; then
  echo "校验文件格式错误(需要 64 位 SHA-256): $ARCHIVE.sha256" >&2
  exit 4
fi
ACTUAL="$(sha256sum "$ARCHIVE" | awk '{print $1}' | tr 'A-F' 'a-f')"
if [[ "$EXPECTED" != "$ACTUAL" ]]; then
  echo "校验失败:期望 $EXPECTED,实际 $ACTUAL;拒绝恢复。" >&2
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
