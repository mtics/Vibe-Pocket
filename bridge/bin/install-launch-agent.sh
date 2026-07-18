#!/bin/zsh

set -euo pipefail

LABEL=au.edu.uts.vibepocket.bridge
SCRIPT_DIR=${0:A:h}
BRIDGE_DIR=${SCRIPT_DIR:h}
CONFIG_DIR="$HOME/Library/Application Support/Vibe Pocket"
CONFIG_FILE="$CONFIG_DIR/bridge.env"
PROFILE_FILE="$CONFIG_DIR/controller-profile.json"
OWNED_THREADS_FILE="$CONFIG_DIR/owned-threads.json"
RUNTIME_DIR="$CONFIG_DIR/runtime"
LOG_DIR="$HOME/Library/Logs/Vibe Pocket"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
NODE_PATH=${VIBE_POCKET_NODE:-$(command -v node)}
CODEX_PATH=${VIBE_POCKET_CODEX_COMMAND:-$(command -v codex)}
TOKEN=${VIBE_POCKET_TOKEN:-}
PORT=${VIBE_POCKET_PORT:-4320}
WORKSPACE=${VIBE_POCKET_WORKSPACE:-${BRIDGE_DIR:h}}

if [[ -z "$TOKEN" && -r "$CONFIG_FILE" ]]; then
  TOKEN=$(zsh -c 'source "$1"; print -rn -- "$VIBE_POCKET_TOKEN"' zsh "$CONFIG_FILE")
fi
if [[ -z "$TOKEN" ]]; then
  TOKEN=$(openssl rand -hex 32)
  print "Generated a new pairing token. Run with VIBE_POCKET_TOKEN set to preserve an existing phone pairing."
fi
if (( ${#TOKEN} < 24 )); then
  print -u2 "VIBE_POCKET_TOKEN must contain at least 24 characters."
  exit 1
fi
umask 077
mkdir -p "$CONFIG_DIR" "$LOG_DIR" "$HOME/Library/LaunchAgents"
mkdir -p "$RUNTIME_DIR"
rm -rf "$RUNTIME_DIR/node_modules"
ditto "$BRIDGE_DIR" "$RUNTIME_DIR"
chmod +x "$RUNTIME_DIR/bin/run-launchd.sh"
chmod +x "$RUNTIME_DIR/bin/attach-current-task.sh"
# Remove artifacts left by releases that supported macOS Accessibility control.
rm -rf "$RUNTIME_DIR/Vibe Pocket Bridge Host.app" "$RUNTIME_DIR/bin/vibe-pocket-codex-helper"
rm -f \
  "$RUNTIME_DIR/src/macos-bridge-host.swift" \
  "$RUNTIME_DIR/src/macos-codex-desktop.mjs" \
  "$RUNTIME_DIR/src/macos-codex-helper.swift" \
  "$RUNTIME_DIR/src/pocket-controller-service.mjs" \
  "$RUNTIME_DIR/src/pocket-service.mjs" \
  "$RUNTIME_DIR/test/pocket-controller-service.test.mjs" \
  "$RUNTIME_DIR/test/pocket-service.test.mjs"
TEMP_CONFIG="$CONFIG_FILE.$$.tmp"
{
  printf 'VIBE_POCKET_TOKEN=%q\n' "$TOKEN"
  printf 'VIBE_POCKET_HOST=%q\n' "127.0.0.1"
  printf 'VIBE_POCKET_PORT=%q\n' "$PORT"
  printf 'VIBE_POCKET_WORKSPACE=%q\n' "$WORKSPACE"
  printf 'VIBE_POCKET_PROFILE_PATH=%q\n' "$PROFILE_FILE"
  printf 'VIBE_POCKET_OWNED_THREADS_PATH=%q\n' "$OWNED_THREADS_FILE"
  printf 'VIBE_POCKET_CODEX_COMMAND=%q\n' "$CODEX_PATH"
  printf 'VIBE_POCKET_NODE=%q\n' "$NODE_PATH"
} > "$TEMP_CONFIG"
chmod 600 "$TEMP_CONFIG"
mv "$TEMP_CONFIG" "$CONFIG_FILE"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$RUNTIME_DIR/bin/run-launchd.sh</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$RUNTIME_DIR</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>5</integer>
  <key>StandardOutPath</key>
  <string>$LOG_DIR/bridge.log</string>
  <key>StandardErrorPath</key>
  <string>$LOG_DIR/bridge-error.log</string>
</dict>
</plist>
EOF
chmod 600 "$PLIST"
plutil -lint "$PLIST" >/dev/null

launchctl bootout "gui/$UID" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$UID" "$PLIST"
launchctl kickstart -k "gui/$UID/$LABEL"

mkdir -p "$HOME/.local/bin"
ln -sfn "$RUNTIME_DIR/bin/attach-current-task.sh" "$HOME/.local/bin/vibe-pocket-attach"

READY=0
for _ in {1..80}; do
  if /usr/bin/curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 0.25
done
if (( ! READY )); then
  print -u2 "Vibe Pocket LaunchAgent did not become healthy on 127.0.0.1:$PORT."
  print -u2 "Check $LOG_DIR/bridge-error.log for details."
  exit 1
fi

print "Vibe Pocket LaunchAgent installed on 127.0.0.1:$PORT."
print "Codex control engine: app-server (direct JSON-RPC)."
print "Pairing token is stored in $CONFIG_FILE with mode 0600."
print "macOS Accessibility permission is not used."
