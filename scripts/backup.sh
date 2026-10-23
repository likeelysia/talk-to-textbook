#!/usr/bin/env bash
# 备份运行时数据。默认不备份 851MB 词典;INCLUDE_DICT=1 时一起打包。
# 用法: ./scripts/backup.sh [项目根目录] [输出目录]
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
OUT="${2:-$ROOT/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="$OUT/talk-to-textbook-$STAMP.tar.gz"

mkdir -p "$OUT"

ITEMS=(data knowledge sessions)
if [[ "${INCLUDE_DICT:-0}" == "1" ]]; then
  ITEMS+=(dict)
fi

echo "备份: $ROOT"
echo "内容: ${ITEMS[*]}"
tar -czf "$ARCHIVE" -C "$ROOT" "${ITEMS[@]}"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$ARCHIVE" | tee "$ARCHIVE.sha256"
fi

echo "完成: $ARCHIVE"
du -h "$ARCHIVE"
