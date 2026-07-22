#!/usr/bin/env bash

set -euo pipefail

usage='Usage: verify-standard-feature-rejected.sh VERSION_CODE VERSION_NAME'
version_code=${1:?$usage}
version_name=${2:?$usage}

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
aapt2=$(find "$sdk/build-tools" -type f -name aapt2 2>/dev/null | LC_ALL=C sort | tail -1)
android_jar=$(find "$sdk/platforms" -mindepth 2 -maxdepth 2 -type f -name android.jar 2>/dev/null |
  LC_ALL=C sort | tail -1)
if [[ -z "$aapt2" || -z "$android_jar" ]]; then
  printf 'Android SDK aapt2 and android.jar are required under %s.\n' "$sdk" >&2
  exit 2
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/vibe-pocket-feature-negative.XXXXXX")
trap 'rm -rf "$work"' EXIT

cat > "$work/AndroidManifest.xml" <<'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="au.edu.uts.vibepocket">
    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />
    <application android:name="au.edu.uts.vibepocket.VibePocket" />
</manifest>
MANIFEST

"$aapt2" link \
  --manifest "$work/AndroidManifest.xml" \
  -I "$android_jar" \
  --rename-manifest-package au.edu.uts.vibepocket \
  --version-code "$version_code" \
  --version-name "$version_name" \
  -o "$work/required-false.apk"

output="$work/rejection.txt"
if bash scripts/verify-standard-apk.sh \
    "$work/required-false.apk" "$version_code" "$version_name" release > "$output" 2>&1; then
  printf 'A standard artifact with android:required=false was incorrectly accepted.\n' >&2
  exit 1
fi

if ! grep -Fq 'Bluetooth feature must explicitly declare android:required=true.' "$output"; then
  printf 'Feature negative control did not exercise the required=true contract. Full output:\n' >&2
  cat "$output" >&2
  exit 1
fi

printf 'Verified android:required=false is rejected by the standard artifact contract.\n'
