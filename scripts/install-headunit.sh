#!/usr/bin/env bash
# Install BYDMate on DiLink with upload progress (adb push shows %).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-}"

if [[ -z "$APK" ]]; then
  APK="$(ls -t "$ROOT"/app/build/outputs/apk/debug/BYDMate-v*.apk 2>/dev/null | head -1)"
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found. Build first: ./gradlew assembleDebug"
  exit 1
fi

SERIAL="${ADB_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  for addr in 192.168.195.2:5555 172.16.181.153:5555; do
    if adb connect "$addr" 2>/dev/null | grep -q connected; then
      SERIAL="$addr"
      break
    fi
  done
fi

if [[ -z "$SERIAL" ]]; then
  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if ((${#devices[@]} == 1)); then
    SERIAL="${devices[0]}"
  elif ((${#devices[@]} > 1)); then
    echo "Several devices — set ADB_SERIAL=host:5555"
    adb devices -l
    exit 1
  else
    echo "No device. adb connect <car-ip>:5555"
    exit 1
  fi
fi

ADB=(adb -s "$SERIAL")
REMOTE="/data/local/tmp/bydmate-install.apk"
SIZE="$(du -h "$APK" | awk '{print $1}')"

echo "Device:  $SERIAL"
echo "APK:     $APK ($SIZE)"
echo ""
echo ">>> Upload (progress below)…"
"${ADB[@]}" push "$APK" "$REMOTE"
echo ""
echo ">>> Installing…"
if "${ADB[@]}" shell pm install -r -d "$REMOTE"; then
  echo ""
  echo "OK: BYDMate installed."
  "${ADB[@]}" shell rm -f "$REMOTE" 2>/dev/null || true
else
  echo ""
  echo "pm install failed — trying streamed install…"
  "${ADB[@]}" install -r -d "$APK"
fi
