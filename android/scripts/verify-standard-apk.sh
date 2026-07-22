#!/usr/bin/env bash

set -euo pipefail

usage='Usage: verify-standard-apk.sh APK VERSION_CODE VERSION_NAME debug|release'
apk=${1:?$usage}
expected_version_code=${2:?$usage}
expected_version_name=${3:?$usage}
build_type=${4:?$usage}

if [[ "$build_type" != debug && "$build_type" != release ]]; then
  printf 'Unknown standard build type: %s\n%s\n' "$build_type" "$usage" >&2
  exit 2
fi
if [[ ! -f "$apk" ]]; then
  printf 'Standard APK does not exist: %s\n' "$apk" >&2
  exit 2
fi

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
aapt=$(find "$sdk/build-tools" -type f -name aapt 2>/dev/null | LC_ALL=C sort | tail -1)
dexdump=$(find "$sdk/build-tools" -type f -name dexdump 2>/dev/null | LC_ALL=C sort | tail -1)
if [[ -z "$aapt" || -z "$dexdump" ]]; then
  printf 'Android SDK aapt and dexdump are required under %s/build-tools.\n' "$sdk" >&2
  exit 2
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/vibe-pocket-standard.XXXXXX")
trap 'rm -rf "$work"' EXIT
badging="$work/badging.txt"
manifest="$work/manifest.txt"
archive="$work/archive.txt"
classes="$work/classes.txt"
failures=0

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  failures=$((failures + 1))
}

"$aapt" dump badging "$apk" > "$badging"
"$aapt" dump xmltree "$apk" AndroidManifest.xml > "$manifest"
unzip -Z1 "$apk" | LC_ALL=C sort > "$archive"

package_line=$(sed -n '1p' "$badging")
application_id=$(printf '%s\n' "$package_line" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
version_code=$(printf '%s\n' "$package_line" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")
version_name=$(printf '%s\n' "$package_line" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")

[[ "$application_id" == au.edu.uts.vibepocket ]] ||
  fail "Unexpected applicationId: ${application_id:-<missing>}"
[[ "$version_code" == "$expected_version_code" ]] ||
  fail "Unexpected versionCode: ${version_code:-<missing>}"
[[ "$version_name" == "$expected_version_name" ]] ||
  fail "Unexpected versionName: ${version_name:-<missing>}"

if [[ "$build_type" == release ]]; then
  grep -Fxq 'application-debuggable' "$badging" && fail 'Release APK is debuggable.'
else
  grep -Fxq 'application-debuggable' "$badging" || fail 'Debug APK is not debuggable.'
fi

sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" "$badging" |
  LC_ALL=C sort -u > "$work/actual-permissions.txt"
cat > "$work/expected-permissions.txt" <<'PERMISSIONS'
android.permission.ACCESS_NETWORK_STATE
android.permission.BLUETOOTH
android.permission.BLUETOOTH_ADMIN
android.permission.BLUETOOTH_ADVERTISE
android.permission.BLUETOOTH_CONNECT
android.permission.INTERNET
au.edu.uts.vibepocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
PERMISSIONS
if ! diff -u "$work/expected-permissions.txt" "$work/actual-permissions.txt" > "$work/permission-diff.txt"; then
  fail "Permission contract mismatch:\n$(cat "$work/permission-diff.txt")"
fi
grep -Fqx "uses-permission: name='android.permission.BLUETOOTH' maxSdkVersion='30'" "$badging" ||
  fail 'BLUETOOTH must remain capped at API 30.'
grep -Fqx "uses-permission: name='android.permission.BLUETOOTH_ADMIN' maxSdkVersion='30'" "$badging" ||
  fail 'BLUETOOTH_ADMIN must remain capped at API 30.'

sed -n '/E: uses-feature /{n;s/.*Raw: "\([^"]*\)".*/\1/p;}' "$manifest" |
  LC_ALL=C sort -u > "$work/actual-features.txt"
printf '%s\n' android.hardware.bluetooth > "$work/expected-features.txt"
if ! diff -u "$work/expected-features.txt" "$work/actual-features.txt" > "$work/feature-diff.txt"; then
  fail "Feature contract mismatch:\n$(cat "$work/feature-diff.txt")"
fi
grep -A2 'E: uses-feature ' "$manifest" |
  grep -Eq '^[[:space:]]+A: android:required\(0x0101028e\)=\(type 0x12\)0xffffffff$' ||
  fail 'Bluetooth feature must explicitly declare android:required=true.'

grep -Fq 'A: android:name' "$manifest" || fail 'Manifest has no named application.'
grep -Fq 'Raw: "au.edu.uts.vibepocket.VibePocket"' "$manifest" ||
  fail 'Unexpected or missing Vibe Pocket application class.'

awk '
  /E: (activity|activity-alias|service|receiver|provider)( |$)/ {
    type = $2
    next
  }
  type != "" && /A: android:name/ {
    value = $0
    sub(/^.*Raw: "/, "", value)
    sub(/".*$/, "", value)
    if (index(value, "au.edu.uts.vibepocket") == 1) print type ":" value
    type = ""
    next
  }
  type != "" && /E:/ { type = "" }
' "$manifest" | LC_ALL=C sort -u > "$work/actual-components.txt"

if [[ "$build_type" == debug ]]; then
  cat > "$work/expected-components.txt" <<'COMPONENTS'
activity:au.edu.uts.vibepocket.MainActivity
activity:au.edu.uts.vibepocket.ui.control.BoardTestActivity
activity:au.edu.uts.vibepocket.ui.control.LandscapeBoardTestActivity
receiver:au.edu.uts.vibepocket.hardware.hid.Control
COMPONENTS
else
  printf '%s\n' 'activity:au.edu.uts.vibepocket.MainActivity' > "$work/expected-components.txt"
fi
LC_ALL=C sort -o "$work/expected-components.txt" "$work/expected-components.txt"
if ! diff -u "$work/expected-components.txt" "$work/actual-components.txt" > "$work/component-diff.txt"; then
  fail "Vibe Pocket component contract mismatch:\n$(cat "$work/component-diff.txt")"
fi

if grep -Fqx 'assets/THIRD_PARTY_NOTICES.md' "$archive"; then
  fail 'Research notice asset is present in the standard APK.'
fi

mkdir "$work/dex"
unzip -qq "$apk" 'classes*.dex' -d "$work/dex"
shopt -s nullglob
dex_files=("$work/dex"/classes*.dex)
if (( ${#dex_files[@]} == 0 )); then
  fail 'APK contains no DEX files.'
else
  for dex in "${dex_files[@]}"; do
    LC_ALL=C "$dexdump" -l plain "$dex" |
      LC_ALL=C \
      sed -n "s/^  Class descriptor  : '\([^']*\)'.*/\1/p" >> "$classes"
  done
  if grep -Eq '^Lau/edu/uts/vibepocket/hardware/micro/' "$classes" ||
      grep -Eq '^Lau/edu/uts/vibepocket/hardware/(Recovery|Restore|Return)(\$|;)' "$classes"; then
    fail "Standard APK contains research-only class:\n$(grep -E '^Lau/edu/uts/vibepocket/hardware/(micro/|Recovery|Restore|Return)' "$classes" | head -20)"
  fi
fi

if (( failures > 0 )); then
  printf 'Standard APK boundary verification failed with %d violation(s): %s\n' "$failures" "$apk" >&2
  exit 1
fi

printf 'Verified standard %s APK identity, manifest, components, assets, and classes: %s\n' \
  "$build_type" "$apk"
