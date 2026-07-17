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
exec "${VIBE_POCKET_NODE:-$(command -v node)}" src/index.mjs
