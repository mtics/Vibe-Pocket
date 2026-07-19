#!/bin/zsh

set -euo pipefail

if (( $# < 2 || $# > 3 )); then
  print -u2 "Usage: cleanup-stale-listener.sh <port> <runtime-dir> [--all-exact]"
  exit 64
fi

PORT=$1
RUNTIME_DIR=${2:A}
MODE=${3:---orphan-only}

if [[ "$PORT" != <-> ]] || (( PORT < 1 || PORT > 65535 )); then
  print -u2 "Vibe Pocket listener cleanup requires a valid TCP port."
  exit 64
fi
if [[ "$MODE" != "--orphan-only" && "$MODE" != "--all-exact" ]]; then
  print -u2 "Unknown Vibe Pocket listener cleanup mode: $MODE"
  exit 64
fi

isExactBridgeListener() {
  local pid=$1
  local processCommand
  local processCwd
  processCommand=$(/bin/ps -p "$pid" -o command= 2>/dev/null || true)
  processCwd=$(/usr/sbin/lsof -a -p "$pid" -d cwd -Fn 2>/dev/null \
    | /usr/bin/sed -n 's/^n//p' \
    | /usr/bin/head -n 1)
  [[ "$processCwd" == "$RUNTIME_DIR" ]] \
    && [[ "$processCommand" == */node\ src/index.mjs || "$processCommand" == node\ src/index.mjs ]]
}

STALE_NODE_PIDS=()
for PID in $(/usr/sbin/lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true); do
  isExactBridgeListener "$PID" || continue
  PROCESS_PARENT=$(/bin/ps -p "$PID" -o ppid= 2>/dev/null | /usr/bin/tr -d '[:space:]')
  if [[ "$MODE" == "--all-exact" || "$PROCESS_PARENT" == "1" ]]; then
    STALE_NODE_PIDS+=("$PID")
  fi
done

(( ${#STALE_NODE_PIDS[@]} > 0 )) || exit 0
/bin/kill $STALE_NODE_PIDS 2>/dev/null || true

for _ in {1..20}; do
  REMAINING_NODE_PIDS=()
  for PID in $STALE_NODE_PIDS; do
    if /bin/kill -0 "$PID" 2>/dev/null && isExactBridgeListener "$PID"; then
      REMAINING_NODE_PIDS+=("$PID")
    fi
  done
  (( ${#REMAINING_NODE_PIDS[@]} == 0 )) && exit 0
  /bin/sleep 0.1
done

/bin/kill -KILL $REMAINING_NODE_PIDS 2>/dev/null || true
