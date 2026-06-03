#!/bin/bash
# Phase 2b hypothesis test: read AC temp current -> write same value -> read again.
# Verifies whether autoservice tx=6 setInt is reachable via app_process shell uid.
#
# Output: PASS / FAIL + raw responses.

set -u

ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SERIAL=${SERIAL:-192.168.2.68:5555}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEX_LOCAL="$SCRIPT_DIR/probe.dex"
DEX_REMOTE="/data/local/tmp/probe.dex"

# AC target temp (driver) read+write fids on dev=1000.
# READ:  fid=1077936168 tx=5  (from fid-candidates.yaml)
# WRITE: fid=501219368  tx=6  (from competitor catalog set_driver_temp)
DEV=1000
READ_FID=1077936168
WRITE_FID=501219368

if [ ! -f "$DEX_LOCAL" ]; then
  echo "ERR probe.dex not found at $DEX_LOCAL. Run build.sh first." >&2
  exit 2
fi

run_probe () {
  local tx="$1"; local fid="$2"; local val="${3:-}"
  local cmd="CLASSPATH=$DEX_REMOTE app_process /system/bin --nice-name=bydmate_probe com.bydmate.probe.WriteProbe $tx $DEV $fid"
  if [ -n "$val" ]; then cmd="$cmd $val"; fi
  $ADB -s "$SERIAL" shell "$cmd" 2>&1
}

echo "[2b] push $DEX_LOCAL -> $DEX_REMOTE"
$ADB -s "$SERIAL" push "$DEX_LOCAL" "$DEX_REMOTE" >/dev/null

echo
echo "[2b] STEP 1: read current AC temp (fid=$READ_FID tx=5)"
R1=$(run_probe 5 "$READ_FID")
echo "$R1"
CURRENT=$(echo "$R1" | grep -oE 'ret_int=-?[0-9]+' | head -1 | cut -d= -f2 || echo "")
if [ -z "$CURRENT" ]; then
  echo "[2b] FAIL: could not read current AC temp"
  exit 3
fi
echo "[2b] current AC temp raw_int = $CURRENT"

echo
echo "[2b] STEP 2: write SAME value back (fid=$WRITE_FID tx=6 val=$CURRENT)"
R2=$(run_probe 6 "$WRITE_FID" "$CURRENT")
echo "$R2"
if echo "$R2" | grep -q "SecurityException\|EXC "; then
  echo "[2b] FAIL: write rejected (helper hypothesis disproven OR fid wrong)"
  echo "[2b] Phase 2 helper path NOT viable via simple app_process shell uid."
  exit 4
fi

echo
echo "[2b] STEP 3: read AC temp again (fid=$READ_FID tx=5)"
R3=$(run_probe 5 "$READ_FID")
echo "$R3"
AFTER=$(echo "$R3" | grep -oE 'ret_int=-?[0-9]+' | head -1 | cut -d= -f2 || echo "")

echo
if [ "$AFTER" = "$CURRENT" ]; then
  echo "[2b] PASS: no-op write succeeded. before=$CURRENT after=$AFTER"
  echo "[2b] autoservice tx=6 reachable from app_process shell uid."
else
  echo "[2b] CONCERN: write returned without exception but value changed."
  echo "      before=$CURRENT after=$AFTER"
  echo "      Possible: wrong write fid OR readback delay OR namespace mismatch."
fi
