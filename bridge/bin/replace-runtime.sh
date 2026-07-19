#!/bin/zsh

set -euo pipefail

if (( $# != 2 )); then
  print -u2 "usage: replace-runtime.sh SOURCE TARGET"
  exit 64
fi

SOURCE=${1:A}
TARGET=${2:A}
PARENT=${TARGET:h}
STAGING="$PARENT/.${TARGET:t}.stage.$$"
BACKUP="$PARENT/.${TARGET:t}.previous.$$"

if [[ ! -d "$SOURCE" || "$SOURCE" == "$TARGET" ]]; then
  print -u2 "Runtime source and target must be distinct directories."
  exit 64
fi

cleanup() {
  rm -rf "$STAGING"
  if [[ -e "$BACKUP" ]]; then
    if [[ ! -e "$TARGET" ]]; then
      mv "$BACKUP" "$TARGET" 2>/dev/null || true
    else
      rm -rf "$BACKUP"
    fi
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$PARENT"
rm -rf "$STAGING" "$BACKUP"
ditto "$SOURCE" "$STAGING"
rm -rf "$STAGING/node_modules"

REQUIRED=(
  package.json
  src/index.mjs
  bin/run-launchd.sh
  src/macos/host.swift
  src/macos/helper.swift
  src/macos/pairing.swift
)
for requiredPath in $REQUIRED; do
  if [[ ! -f "$STAGING/$requiredPath" || -L "$STAGING/$requiredPath" ]]; then
    print -u2 "Runtime source is incomplete or unsafe: $requiredPath"
    exit 65
  fi
done
if [[ -n "$(find "$STAGING" -type l -print -quit)" ]]; then
  print -u2 "Runtime source must not contain symbolic links."
  exit 65
fi

# The service is stopped before this helper runs. Publish only a complete
# source snapshot so removed modules cannot survive an upgrade.
if [[ -e "$TARGET" ]]; then mv "$TARGET" "$BACKUP"; fi
if ! mv "$STAGING" "$TARGET"; then
  [[ ! -e "$BACKUP" ]] || mv "$BACKUP" "$TARGET"
  exit 1
fi
rm -rf "$BACKUP"
trap - EXIT INT TERM
