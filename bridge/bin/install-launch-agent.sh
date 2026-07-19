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
HOST_APP="$CONFIG_DIR/Vibe Pocket Bridge Host.app"
HOST_CONTENTS="$HOST_APP/Contents"
HOST_PATH="$HOST_CONTENTS/MacOS/Vibe Pocket Bridge Host"
HOST_HASH_FILE="$CONFIG_DIR/bridge-host.sha256"
PAIR_APP="$HOME/Applications/Pair Vibe Pocket.app"
LOG_DIR="$HOME/Library/Logs/Vibe Pocket"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
NODE_PATH=${VIBE_POCKET_NODE:-$(command -v node)}
CODEX_PATH=${VIBE_POCKET_CODEX_COMMAND:-$(command -v codex)}
SWIFTC_PATH=${VIBE_POCKET_SWIFTC:-/usr/bin/swiftc}
TOKEN=${VIBE_POCKET_TOKEN:-}
PORT=${VIBE_POCKET_PORT:-4320}
WORKSPACE=${VIBE_POCKET_WORKSPACE:-${BRIDGE_DIR:h}}
PUBLIC_URL=${VIBE_POCKET_PUBLIC_URL:-}

if [[ -z "$TOKEN" && -r "$CONFIG_FILE" ]]; then
  TOKEN=$(zsh -c 'source "$1"; print -rn -- "$VIBE_POCKET_TOKEN"' zsh "$CONFIG_FILE")
fi
if [[ -z "$TOKEN" ]]; then
  TOKEN=$(openssl rand -hex 32)
  print "Generated a new Bridge administration secret."
fi
if (( ${#TOKEN} < 24 )); then
  print -u2 "VIBE_POCKET_TOKEN must contain at least 24 characters."
  exit 1
fi
umask 077
mkdir -p "$CONFIG_DIR" "$LOG_DIR" "$HOME/Library/LaunchAgents"
mkdir -p "$RUNTIME_DIR"

# Stop the previous job before replacing its runtime. Older releases launched
# the Host through `open`, so clean up only that exact detached command line.
launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
LEGACY_COMMAND="$HOST_PATH run $RUNTIME_DIR/bin/run-launchd.sh"
LEGACY_PIDS=$(ps -axo pid=,command= | awk -v expected="$LEGACY_COMMAND" '{
  pid = $1
  sub(/^[[:space:]]*[0-9]+[[:space:]]+/, "", $0)
  if ($0 == expected) print pid
}')
if [[ -n "$LEGACY_PIDS" ]]; then
  kill $=LEGACY_PIDS 2>/dev/null || true
  sleep 2
  REMAINING_PIDS=$(ps -axo pid=,command= | awk -v expected="$LEGACY_COMMAND" '{
    pid = $1
    sub(/^[[:space:]]*[0-9]+[[:space:]]+/, "", $0)
    if ($0 == expected) print pid
  }')
  [[ -z "$REMAINING_PIDS" ]] || kill -KILL $=REMAINING_PIDS 2>/dev/null || true
fi

# A killed Host can leave its Node child behind. The shared cleanup helper
# stops only a listener whose command and working directory match this runtime.
/bin/zsh "$BRIDGE_DIR/bin/cleanup-stale-listener.sh" "$PORT" "$RUNTIME_DIR" --all-exact

rm -rf "$RUNTIME_DIR/node_modules"
ditto "$BRIDGE_DIR" "$RUNTIME_DIR"
chmod +x "$RUNTIME_DIR/bin/run-launchd.sh"
chmod +x "$RUNTIME_DIR/bin/cleanup-stale-listener.sh"
chmod +x "$RUNTIME_DIR/bin/attach-current-task.sh"
chmod +x "$RUNTIME_DIR/bin/report-codex-hook.sh"
chmod +x "$RUNTIME_DIR/bin/install-codex-hooks.mjs"
chmod +x "$RUNTIME_DIR/bin/install-codex-keybindings.mjs"
chmod +x "$RUNTIME_DIR/bin/public-url.mjs"
chmod +x "$RUNTIME_DIR/bin/pair-phone.sh"
# Remove obsolete direct-control artifacts from earlier releases.
rm -rf "$RUNTIME_DIR/Vibe Pocket Bridge Host.app"
rm -f \
  "$RUNTIME_DIR/src/pocket-controller-service.mjs" \
  "$RUNTIME_DIR/src/pocket-service.mjs" \
  "$RUNTIME_DIR/test/pocket-controller-service.test.mjs" \
  "$RUNTIME_DIR/test/pocket-service.test.mjs"

HOST_SOURCE="$RUNTIME_DIR/src/macos/host.swift"
CONTROL_SOURCE="$RUNTIME_DIR/src/macos/helper.swift"
PAIRING_SOURCE="$RUNTIME_DIR/src/macos/pairing.swift"
HOST_SOURCE_HASH=$(
  {
    shasum -a 256 "$HOST_SOURCE" "$CONTROL_SOURCE" "$PAIRING_SOURCE"
    printf '%s\n' 'signing-profile:stable-designated-requirement-v1'
    printf '%s\n' 'bundle-version:0.10.0-16'
  } | shasum -a 256 | awk '{print $1}'
)
INSTALLED_HOST_HASH=$(cat "$HOST_HASH_FILE" 2>/dev/null || true)
if [[ ! -x "$HOST_PATH" || "$HOST_SOURCE_HASH" != "$INSTALLED_HOST_HASH" ]]; then
  HOST_TEMP="$CONFIG_DIR/Vibe Pocket Bridge Host.app.$$.tmp"
  rm -rf "$HOST_TEMP"
  mkdir -p "$HOST_TEMP/Contents/MacOS"
  "$SWIFTC_PATH" "$HOST_SOURCE" "$CONTROL_SOURCE" "$PAIRING_SOURCE" -O -o "$HOST_TEMP/Contents/MacOS/Vibe Pocket Bridge Host"
  cat > "$HOST_TEMP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>Vibe Pocket Bridge Host</string>
  <key>CFBundleIdentifier</key>
  <string>au.edu.uts.vibepocket.bridge-host</string>
  <key>CFBundleName</key>
  <string>Vibe Pocket Bridge Host</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.10.0</string>
  <key>CFBundleVersion</key>
  <string>16</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
  <key>LSUIElement</key>
  <true/>
</dict>
</plist>
EOF
  plutil -lint "$HOST_TEMP/Contents/Info.plist" >/dev/null
  codesign \
    --force \
    --deep \
    --sign - \
    --identifier au.edu.uts.vibepocket.bridge-host \
    --requirements '=designated => identifier "au.edu.uts.vibepocket.bridge-host"' \
    "$HOST_TEMP" >/dev/null
  rm -rf "$HOST_APP"
  mv "$HOST_TEMP" "$HOST_APP"
  printf '%s\n' "$HOST_SOURCE_HASH" > "$HOST_HASH_FILE.tmp"
  mv "$HOST_HASH_FILE.tmp" "$HOST_HASH_FILE"
fi
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
  printf 'VIBE_POCKET_SWIFTC=%q\n' "$SWIFTC_PATH"
  [[ -z "$PUBLIC_URL" ]] || printf 'VIBE_POCKET_PUBLIC_URL=%q\n' "$PUBLIC_URL"
} > "$TEMP_CONFIG"
chmod 600 "$TEMP_CONFIG"
mv "$TEMP_CONFIG" "$CONFIG_FILE"

HOOKS_RESULT=$("$NODE_PATH" \
  "$RUNTIME_DIR/bin/install-codex-hooks.mjs" \
  --remove \
  "$HOME/.codex/hooks.json")
KEYBINDINGS_RESULT=$("$NODE_PATH" \
  "$RUNTIME_DIR/bin/install-codex-keybindings.mjs" \
  "$HOME/.codex/keybindings.json")

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$HOST_PATH</string>
    <string>run</string>
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
ln -sfn "$RUNTIME_DIR/bin/pair-phone.sh" "$HOME/.local/bin/vibe-pocket-pair"

PAIR_TEMP="$CONFIG_DIR/Pair Vibe Pocket.app.$$.tmp"
rm -rf "$PAIR_TEMP"
mkdir -p "$PAIR_TEMP/Contents/MacOS" "$HOME/Applications"
cat > "$PAIR_TEMP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>Pair Vibe Pocket</string>
  <key>CFBundleIdentifier</key>
  <string>au.edu.uts.vibepocket.pair</string>
  <key>CFBundleName</key>
  <string>Pair Vibe Pocket</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.10.0</string>
  <key>CFBundleVersion</key>
  <string>2</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
</dict>
</plist>
EOF
cat > "$PAIR_TEMP/Contents/MacOS/Pair Vibe Pocket" <<'EOF'
#!/bin/zsh
set -u
OUTPUT=$("$HOME/.local/bin/vibe-pocket-pair" 2>&1)
STATUS=$?
if (( STATUS != 0 )); then
  /usr/bin/osascript - "$OUTPUT" <<'APPLESCRIPT'
on run argv
  display alert "Pairing unavailable" message (item 1 of argv) as critical buttons {"OK"} default button "OK"
end run
APPLESCRIPT
fi
exit $STATUS
EOF
chmod +x "$PAIR_TEMP/Contents/MacOS/Pair Vibe Pocket"
plutil -lint "$PAIR_TEMP/Contents/Info.plist" >/dev/null
codesign --force --deep --sign - --identifier au.edu.uts.vibepocket.pair "$PAIR_TEMP" >/dev/null
rm -rf "$PAIR_APP"
mv "$PAIR_TEMP" "$PAIR_APP"

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
print "Codex control engine: HID and semantic shortcuts first; no pointer synthesis."
print "Legacy Codex lifecycle hooks: $HOOKS_RESULT."
print "Codex semantic shortcuts: $KEYBINDINGS_RESULT."
print "Reload Codex once after the first shortcut installation."
print "The Bridge administration secret is stored in $CONFIG_FILE with mode 0600."
DISCOVERED_URL=${PUBLIC_URL:-$($NODE_PATH "$RUNTIME_DIR/bin/public-url.mjs" "$PORT" 2>/dev/null || true)}
if [[ -n "$DISCOVERED_URL" ]]; then
  print "Pair phones without typing credentials by opening: $PAIR_APP"
  print "Command-line equivalent: $HOME/.local/bin/vibe-pocket-pair"
else
  print "No Tailscale Serve URL was detected. Set VIBE_POCKET_PUBLIC_URL before pairing."
fi
print "Grant Accessibility permission to this signed background host:"
printf '  %q\n' "$HOST_APP"
print "Request the macOS prompt with:"
printf '  open -n -W -a %q --args request-accessibility\n' "$HOST_APP"
