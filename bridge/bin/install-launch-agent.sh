#!/bin/zsh

set -euo pipefail

LABEL=au.edu.uts.vibepocket.bridge
SCRIPT_DIR=${0:A:h}
BRIDGE_DIR=${SCRIPT_DIR:h}
CONFIG_DIR="$HOME/Library/Application Support/Vibe Pocket"
CONFIG_FILE="$CONFIG_DIR/bridge.env"
PROFILE_FILE="$CONFIG_DIR/controller-profile.json"
RUNTIME_DIR="$CONFIG_DIR/runtime"
HELPER_PATH="$RUNTIME_DIR/bin/vibe-pocket-codex-helper"
HOST_APP="$RUNTIME_DIR/Vibe Pocket Bridge Host.app"
HOST_CONTENTS="$HOST_APP/Contents"
HOST_PATH="$HOST_CONTENTS/MacOS/Vibe Pocket Bridge Host"
LOG_DIR="$HOME/Library/Logs/Vibe Pocket"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
NODE_PATH=${VIBE_POCKET_NODE:-$(command -v node)}
SWIFTC_PATH=${VIBE_POCKET_SWIFTC:-/usr/bin/swiftc}
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
ditto "$BRIDGE_DIR" "$RUNTIME_DIR"
chmod +x "$RUNTIME_DIR/bin/run-launchd.sh"
"$SWIFTC_PATH" "$RUNTIME_DIR/src/macos-codex-helper.swift" -O -o "$HELPER_PATH"
codesign --force --sign - --identifier au.edu.uts.vibepocket.helper "$HELPER_PATH" >/dev/null
mkdir -p "$HOST_CONTENTS/MacOS"
"$SWIFTC_PATH" "$RUNTIME_DIR/src/macos-bridge-host.swift" -O -o "$HOST_PATH"
cat > "$HOST_CONTENTS/Info.plist" <<'EOF'
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
  <string>0.1.0</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
  <key>LSUIElement</key>
  <true/>
</dict>
</plist>
EOF
plutil -lint "$HOST_CONTENTS/Info.plist" >/dev/null
codesign --force --deep --sign - --identifier au.edu.uts.vibepocket.bridge-host "$HOST_APP" >/dev/null
TEMP_CONFIG="$CONFIG_FILE.$$.tmp"
{
  printf 'VIBE_POCKET_TOKEN=%q\n' "$TOKEN"
  printf 'VIBE_POCKET_HOST=%q\n' "127.0.0.1"
  printf 'VIBE_POCKET_PORT=%q\n' "$PORT"
  printf 'VIBE_POCKET_WORKSPACE=%q\n' "$WORKSPACE"
  printf 'VIBE_POCKET_PROFILE_PATH=%q\n' "$PROFILE_FILE"
  printf 'VIBE_POCKET_NODE=%q\n' "$NODE_PATH"
  printf 'VIBE_POCKET_SWIFTC=%q\n' "$SWIFTC_PATH"
  printf 'VIBE_POCKET_HELPER_PATH=%q\n' "$HELPER_PATH"
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

print "Vibe Pocket LaunchAgent installed on 127.0.0.1:$PORT."
print "Pairing token is stored in $CONFIG_FILE with mode 0600."
print "Grant Accessibility permission to this signed background host:"
printf '  %q\n' "$HOST_APP"
print "Request the macOS prompt with:"
printf '  open -n -W -a %q --args request-accessibility\n' "$HOST_APP"
