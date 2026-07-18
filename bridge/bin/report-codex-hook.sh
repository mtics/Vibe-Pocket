#!/bin/zsh

set -u

EVENT=${1:-}
CONFIG_FILE=${VIBE_POCKET_CONFIG_FILE:-"$HOME/Library/Application Support/Vibe Pocket/bridge.env"}

case "$EVENT" in
  UserPromptSubmit|PreToolUse|PermissionRequest|PostToolUse|Stop) ;;
  *) print -rn -- '{}'; exit 0 ;;
esac

if [[ ! -r "$CONFIG_FILE" ]]; then
  print -rn -- '{}'
  exit 0
fi

source "$CONFIG_FILE"
TOKEN=${VIBE_POCKET_TOKEN:-}
PORT=${VIBE_POCKET_PORT:-4320}
if [[ -z "$TOKEN" || "$PORT" != <1-65535> ]]; then
  print -rn -- '{}'
  exit 0
fi

MAX_TIME=3
[[ "$EVENT" == PermissionRequest ]] && MAX_TIME=130
if RESPONSE=$(/usr/bin/curl -fsS \
  --connect-timeout 1 \
  --max-time "$MAX_TIME" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @- \
  "http://127.0.0.1:$PORT/v1/pocket/codex-hooks/$EVENT" 2>/dev/null); then
  print -rn -- "${RESPONSE:-{}}"
else
  print -rn -- '{}'
fi
