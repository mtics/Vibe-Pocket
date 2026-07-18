#!/bin/zsh

set -euo pipefail

CONFIG_FILE="$HOME/Library/Application Support/Vibe Pocket/bridge.env"
THREAD_ID=${1:-${CODEX_THREAD_ID:-}}

if [[ ! "$THREAD_ID" =~ '^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$' ]]; then
  print -u2 "Run vibe-pocket-attach from inside a Codex desktop task, or pass its task ID explicitly."
  exit 2
fi
if [[ ! -r "$CONFIG_FILE" ]]; then
  print -u2 "Vibe Pocket Bridge is not installed for this macOS account."
  exit 3
fi

source "$CONFIG_FILE"
BRIDGE_URL=${VIBE_POCKET_BRIDGE_URL:-"http://127.0.0.1:${VIBE_POCKET_PORT:-4320}"}

/usr/bin/curl --fail-with-body --silent --show-error \
  --request POST \
  --header "Authorization: Bearer $VIBE_POCKET_TOKEN" \
  --header "Content-Type: application/json" \
  --data "{\"threadId\":\"$THREAD_ID\"}" \
  "$BRIDGE_URL/v1/pocket/desktop/attach" >/dev/null

print "Attached Codex desktop task $THREAD_ID to Vibe Pocket."
