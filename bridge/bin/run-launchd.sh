#!/bin/zsh

set -euo pipefail

SCRIPT_DIR=${0:A:h}
BRIDGE_DIR=${SCRIPT_DIR:h}
CONFIG_FILE=${VIBE_POCKET_CONFIG_FILE:-"$HOME/Library/Application Support/Vibe Pocket/bridge.env"}

if [[ ! -r "$CONFIG_FILE" ]]; then
  print -u2 "Vibe Pocket bridge config is missing: $CONFIG_FILE"
  exit 1
fi

set -a
source "$CONFIG_FILE"
set +a

cd "$BRIDGE_DIR"
NODE_BIN=${VIBE_POCKET_NODE:-$(command -v node)}
exec /usr/bin/env -i \
  HOME="$HOME" \
  USER="$USER" \
  LOGNAME="${LOGNAME:-$USER}" \
  PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
  TMPDIR="${TMPDIR:-/tmp}" \
  LANG="${LANG:-en_US.UTF-8}" \
  VIBE_POCKET_TOKEN="$VIBE_POCKET_TOKEN" \
  VIBE_POCKET_HOST="$VIBE_POCKET_HOST" \
  VIBE_POCKET_HOST_SOCKET="$VIBE_POCKET_HOST_SOCKET" \
  VIBE_POCKET_PORT="$VIBE_POCKET_PORT" \
  VIBE_POCKET_WORKSPACE="$VIBE_POCKET_WORKSPACE" \
  VIBE_POCKET_PROFILE_PATH="$VIBE_POCKET_PROFILE_PATH" \
  VIBE_POCKET_OWNED_THREADS_PATH="$VIBE_POCKET_OWNED_THREADS_PATH" \
  VIBE_POCKET_CODEX_COMMAND="$VIBE_POCKET_CODEX_COMMAND" \
  VIBE_POCKET_NODE="$VIBE_POCKET_NODE" \
  "$NODE_BIN" src/index.mjs
