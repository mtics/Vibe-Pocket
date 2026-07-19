#!/bin/zsh

set -euo pipefail

CONFIG_DIR="$HOME/Library/Application Support/Vibe Pocket"
CONFIG_FILE=${VIBE_POCKET_CONFIG_FILE:-"$CONFIG_DIR/bridge.env"}
HOST_APP="$CONFIG_DIR/Vibe Pocket Bridge Host.app"

if [[ ! -r "$CONFIG_FILE" ]]; then
  print -u2 "Vibe Pocket Bridge is not installed."
  exit 1
fi

set -a
source "$CONFIG_FILE"
set +a

SCRIPT_DIR=${0:A:h}
NODE_BIN=${VIBE_POCKET_NODE:-$(command -v node)}
CURL_BIN=${VIBE_POCKET_CURL:-/usr/bin/curl}
OPEN_BIN=${VIBE_POCKET_OPEN:-/usr/bin/open}
PUBLIC_URL=${VIBE_POCKET_PUBLIC_URL:-}
if [[ -z "$PUBLIC_URL" ]]; then
  PUBLIC_URL=$("$NODE_BIN" "$SCRIPT_DIR/public-url.mjs" "$VIBE_POCKET_PORT" 2>/dev/null || true)
fi
if [[ -z "$PUBLIC_URL" ]]; then
  print -u2 "No HTTPS Tailscale Serve address was found for the Vibe Pocket Bridge."
  print -u2 "Configure Tailscale Serve or set VIBE_POCKET_PUBLIC_URL, then retry."
  exit 1
fi

PAYLOAD=$("$NODE_BIN" -e 'process.stdout.write(JSON.stringify({ origin: process.argv[1] }))' "$PUBLIC_URL")
LOCAL_PROTOCOL=$("$CURL_BIN" -fsS "http://127.0.0.1:$VIBE_POCKET_PORT/healthz" | \
  "$NODE_BIN" -e 'let body=""; process.stdin.on("data", c => body += c); process.stdin.on("end", () => process.stdout.write(String(JSON.parse(body).protocolVersion)))')
REMOTE_PROTOCOL=$("$CURL_BIN" -fsS "$PUBLIC_URL/healthz" | \
  "$NODE_BIN" -e 'let body=""; process.stdin.on("data", c => body += c); process.stdin.on("end", () => process.stdout.write(String(JSON.parse(body).protocolVersion)))')
if [[ "$LOCAL_PROTOCOL" != "6" || "$REMOTE_PROTOCOL" != "6" ]]; then
  print -u2 "The local Bridge and Tailscale Serve endpoint must both expose pairing protocol 6."
  exit 1
fi

PAIRING_SOCKET="$CONFIG_DIR/pairing.sock"
RESPONSE=$("$CURL_BIN" -fsS \
  --unix-socket "$PAIRING_SOCKET" \
  -X POST \
  -H "Content-Type: application/json" \
  --data "$PAYLOAD" \
  "http://localhost/v1/pairing/invitations")
ADB_COMMAND=$(print -rn -- "$RESPONSE" | "$NODE_BIN" "$SCRIPT_DIR/pairing-response.mjs")

ADB_BIN=${VIBE_POCKET_ADB:-$(command -v adb 2>/dev/null || true)}
if [[ -n "$ADB_BIN" ]]; then
  DEVICES=(${(f)"$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"})
  if (( ${#DEVICES} == 1 )); then
    if print -rn -- "$ADB_COMMAND" | \
      "$ADB_BIN" -s "$DEVICES[1]" shell sh >/dev/null 2>&1; then
      print "Pairing invitation sent to ${DEVICES[1]}."
      exit 0
    fi
  fi
fi

if [[ ! -d "$HOST_APP" ]]; then
  print -u2 "Vibe Pocket Bridge Host is missing: $HOST_APP"
  exit 1
fi
PAIRING_DIR="$CONFIG_DIR/invitations"
mkdir -p "$PAIRING_DIR"
chmod 700 "$PAIRING_DIR"
/usr/bin/find "$PAIRING_DIR" -type f -name 'invitation.*' -mmin +10 -delete 2>/dev/null || true
PAIRING_FILE=$(/usr/bin/mktemp "$PAIRING_DIR/invitation.XXXXXX")
chmod 600 "$PAIRING_FILE"
trap '[[ -z "${PAIRING_FILE:-}" ]] || rm -f -- "$PAIRING_FILE"' EXIT
print -rn -- "$RESPONSE" > "$PAIRING_FILE"
if ! "$OPEN_BIN" -n -a "$HOST_APP" --args show-pairing-file "$PAIRING_FILE"; then
  print -u2 "The Vibe Pocket pairing window could not be opened."
  exit 1
fi
PAIRING_FILE=""
print "Pairing window opened. The invitation expires in five minutes."
