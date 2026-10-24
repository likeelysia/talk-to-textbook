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
ARCHIVE_ERROR=""
RECOVERY_ERROR=""

finish() {
  local status=$?
  set +e
  trap - EXIT

  if [[ "$STOPPED" == "1" ]]; then
    echo "恢复服务: $START_CMD"
    if ! eval "$START_CMD"; then
      RECOVERY_ERROR="服务恢复命令失败"
      echo "错误:服务恢复失败,请立即手动检查服务状态!" >&2
    else
      echo "服务已恢复。"
    fi
  fi

  # 两类错误都打印,不能用 finally 的恢复错误盖掉原来的归档错误
  if [[ -n "$ARCHIVE_ERROR" ]]; then
    echo "错误:归档失败 — $ARCHIVE_ERROR" >&2
  fi
  if [[ -n "$RECOVERY_ERROR" ]]; then
    echo "错误:恢复失败 — $RECOVERY_ERROR" >&2
  fi

  if [[ "$status" -eq 0 && -n "$RECOVERY_ERROR" ]]; then
    status=6
  fi
  if [[ "$status" -ne 0 ]]; then
    echo "备份未完整成功。归档若已生成会保留: $ARCHIVE" >&2
    echo "人工检查:确认应用/容器是否在运行;恢复服务命令: $START_CMD" >&2
  fi
  exit "$status"
}
trap finish EXIT

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
    ARCHIVE_ERROR="缺少目录 $ROOT/$item"
    echo "$ARCHIVE_ERROR" >&2
    exit 4
  fi
done

echo "归档: $ROOT -> $ARCHIVE"
if ! tar -czf "$ARCHIVE" -C "$ROOT" "${ITEMS[@]}"; then
  ARCHIVE_ERROR="tar 归档失败,未生成有效备份"
  echo "$ARCHIVE_ERROR" >&2
  exit 5
fi

tar -tzf "$ARCHIVE" > "$ARCHIVE.manifest.txt"
if command -v sha256sum >/dev/null 2>&1; then
  # 只写 basename,sidecar 和归档一起搬目录/搬机器仍能校验
  (cd "$OUT" && sha256sum "$(basename "$ARCHIVE")") > "$ARCHIVE.sha256"
  cat "$ARCHIVE.sha256"
else
  echo "警告:没有 sha256sum,未生成校验文件;restore.sh 将拒绝恢复。" >&2
fi
echo "完成: $ARCHIVE"
du -h "$ARCHIVE"
