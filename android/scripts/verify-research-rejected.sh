#!/usr/bin/env bash

set -euo pipefail

usage='Usage: verify-research-rejected.sh RESEARCH_APK ROOT_NOTICE VERSION_CODE VERSION_NAME'
apk=${1:?$usage}
root_notice=${2:?$usage}
version_code=${3:?$usage}
version_name=${4:?$usage}

if [[ ! -f "$apk" || ! -f "$root_notice" ]]; then
  printf 'Research APK and root notice must both exist.\n%s\n' "$usage" >&2
  exit 2
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/vibe-pocket-research.XXXXXX")
trap 'rm -rf "$work"' EXIT
output="$work/rejection.txt"

if bash scripts/verify-standard-apk.sh "$apk" "$version_code" "$version_name" debug > "$output" 2>&1; then
  printf 'Research APK was incorrectly accepted as a standard artifact: %s\n' "$apk" >&2
  exit 1
fi

diagnostics=(
  'Unexpected applicationId'
  'Unexpected versionName'
  'Permission contract mismatch'
  'Feature contract mismatch'
  'Vibe Pocket component contract mismatch'
  'Research notice asset is present'
  'research-only class'
)
for diagnostic in "${diagnostics[@]}"; do
  if ! grep -Fq "$diagnostic" "$output"; then
    printf 'Research rejection did not exercise %s. Full output:\n' "$diagnostic" >&2
    cat "$output" >&2
    exit 1
  fi
done

notice_count=$(unzip -Z1 "$apk" | grep -Fxc 'assets/THIRD_PARTY_NOTICES.md' || true)
if [[ "$notice_count" != 1 ]]; then
  printf 'Research APK must contain exactly one root notice asset; found %s.\n' "$notice_count" >&2
  exit 1
fi
unzip -p "$apk" assets/THIRD_PARTY_NOTICES.md > "$work/packaged-notice.md"
if ! cmp -s "$root_notice" "$work/packaged-notice.md"; then
  printf 'Research APK notice does not match the root THIRD_PARTY_NOTICES.md.\n' >&2
  diff -u "$root_notice" "$work/packaged-notice.md" >&2 || true
  exit 1
fi

printf 'Verified research APK is rejected on every standard boundary and packages the root notice: %s\n' "$apk"
