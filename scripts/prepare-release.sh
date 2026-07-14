#!/usr/bin/env bash
# prepare-release.sh X.Y.Z
#
# Promotes the [Unreleased] section of CHANGELOG.md into a dated version
# section, fixes up the compare links, and prints plain-text release notes
# (Russian headings, no markdown) ready for `gh release create --notes`.
#
# Workflow:
#   1. before a release, write the changes under "## [Unreleased]" in CHANGELOG.md
#   2. ./scripts/prepare-release.sh 3.6.0
#   3. copy the printed notes into `gh release create v3.6.0 ... --notes "..."`
set -euo pipefail

VER="${1:?usage: prepare-release.sh X.Y.Z}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CL="$ROOT/CHANGELOG.md"
DATE="$(date +%Y-%m-%d)"
URL="https://github.com/AndyShaman/BYDMate"

# --- guard: [Unreleased] must actually have content ---
UNREL="$(awk '/^## \[Unreleased\]/{f=1;next} /^## \[/{f=0} f' "$CL")"
if [ -z "$(printf '%s' "$UNREL" | tr -d '[:space:]')" ]; then
  echo "error: [Unreleased] is empty in CHANGELOG.md, nothing to release" >&2
  exit 1
fi

# --- previous top version (first version heading after Unreleased) ---
# matches both 2-part (3.5) and 3-part (3.2.2) versions the project uses
PREV="$(grep -m1 -oE '^## \[[0-9]+\.[0-9]+(\.[0-9]+)?\]' "$CL" | tr -d '#[] ')"
if [ -z "$PREV" ]; then
  echo "error: could not find previous version heading in CHANGELOG.md" >&2
  exit 1
fi

# --- promote: insert the dated version heading right under [Unreleased] ---
awk -v ver="$VER" -v date="$DATE" '
  /^## \[Unreleased\]/ { print; print ""; print "## [" ver "] - " date; next }
  { print }
' "$CL" > "$CL.tmp"

# --- links: retarget [Unreleased] to HEAD and add the [X.Y.Z] compare line ---
awk -v ver="$VER" -v prev="$PREV" -v url="$URL" '
  /^\[Unreleased\]:/ {
    print "[Unreleased]: " url "/compare/v" ver "...HEAD"
    print "[" ver "]: " url "/compare/v" prev "...v" ver
    next
  }
  { print }
' "$CL.tmp" > "$CL"
rm -f "$CL.tmp"

echo "CHANGELOG.md: [Unreleased] -> [$VER] - $DATE (prev v$PREV)" >&2
echo "" >&2
echo "===== release notes for v$VER (plain text, paste into gh release) =====" >&2

# --- print plain-text notes: Russian headings, drop markdown ### ---
awk -v ver="$VER" '
  $0 ~ "^## \\[" ver "\\]" { f=1; next }
  /^## \[/ { f=0 }
  f {
    if ($0 ~ /^### Added/)   { print "Новое";       next }
    if ($0 ~ /^### Fixed/)   { print "Исправления"; next }
    if ($0 ~ /^### Changed/) { print "Изменено";    next }
    if ($0 ~ /^### /)        { sub(/^### /, ""); print; next }
    print
  }
' "$CL"

# --- APK SHA-256 for the release notes (AC-12) ---
APK="$ROOT/app/build/outputs/apk/release/BYDMate-v$VER.apk"
if [ -f "$APK" ]; then
  echo ""
  echo "SHA-256: $(shasum -a 256 "$APK" | cut -d' ' -f1)"
else
  echo "" >&2
  echo "note: $APK not found - build the APK, then append its SHA-256 to the notes manually" >&2
fi
