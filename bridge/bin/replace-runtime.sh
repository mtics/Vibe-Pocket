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

if [[ ! -d "$SOURCE" || "$SOURCE" == "$TARGET" ]]; then
  print -u2 "Runtime source and target must be distinct directories."
  exit 64
fi

cleanup() {
  rm -rf "$STAGING"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$PARENT"
rm -rf "$STAGING"
ditto "$SOURCE" "$STAGING"
rm -rf "$STAGING/node_modules"

# The service is stopped before this helper runs. Publish only a complete
# source snapshot so removed modules cannot survive an upgrade.
rm -rf "$TARGET"
mv "$STAGING" "$TARGET"
trap - EXIT INT TERM
