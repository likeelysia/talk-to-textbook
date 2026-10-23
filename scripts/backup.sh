#!/usr/bin/env bash
# 一致性优先的备份:默认要求先停服务/停写,再归档;结束后自动尝试恢复服务。
#
# 用法:
#   STOP_CMD="docker compose stop app" START_CMD="docker compose start app" \
#     bash scripts/backup.sh [项目根目录] [输出目录]
#
# 只读文件/测试:STOP_CMD=true START_CMD=true bash scripts/backup.sh ...
# 默认不备份 851MB 词典;INCLUDE_DICT=1 时一起打包。
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
OUT="${2:-$ROOT/backups}"
STOP_CMD="${STOP_CMD:-}"
START_CMD="${START_CMD:-}"
STOP_WAIT_SECONDS="${STOP_WAIT_SECONDS:-3}"
STAMP="$(date +%Y%m%d-%H%M%S)-$$"
ARCHIVE="$OUT/talk-to-textbook-$STAMP.tar.gz"

if [[ -z "$STOP_CMD" || -z "$START_CMD" ]]; then
  echo "拒绝在线备份:请设置 STOP_CMD 和 START_CMD,先停服务/停写再归档。" >&2
  echo "例:STOP_CMD='docker compose stop app' START_CMD='docker compose start app' $0" >&2
  exit 2
fi

mkdir -p "$OUT"
STOPPED=0
restore_service() {
  if [[ "$STOPPED" == "1" ]]; then
    echo "恢复服务: $START_CMD"
    if ! eval "$START_CMD"; then
      echo "警告:服务恢复命令失败,请手动检查!" >&2
    fi
  fi
}
trap restore_service EXIT

echo "停止写入: $STOP_CMD"
if ! eval "$STOP_CMD"; then
  echo "停止服务失败,备份中止(未归档)。" >&2
  exit 3
fi
STOPPED=1
sleep "$STOP_WAIT_SECONDS"

ITEMS=(data knowledge sessions)
if [[ "${INCLUDE_DICT:-0}" == "1" ]]; then
  ITEMS+=(dict)
fi
for item in "${ITEMS[@]}"; do
  if [[ ! -e "$ROOT/$item" ]]; then
    echo "缺少目录: $ROOT/$item" >&2
    exit 4
  fi
done

echo "归档: $ROOT -> $ARCHIVE"
if ! tar -czf "$ARCHIVE" -C "$ROOT" "${ITEMS[@]}"; then
  echo "tar 归档失败,未生成有效备份。" >&2
  exit 5
fi

tar -tzf "$ARCHIVE" > "$ARCHIVE.manifest.txt"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$ARCHIVE" | tee "$ARCHIVE.sha256"
fi
echo "完成: $ARCHIVE"
du -h "$ARCHIVE"
