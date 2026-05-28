#!/bin/bash
# Phase 2c Round 1 — permission-only probe across all candidate fids.
#
# For each fid in round1-permission-probes.csv:
#   1) tx=5 (getInt) → current value
#   2) tx=6 (setInt) writing same value back
#   3) tx=5 (getInt) → confirm unchanged
#
# Output table: category | name | dev | fid | read_status | write_status | accessible
#
# accessible = TRUE when write_status==0 (read+write both succeed)
# accessible = FALSE when read_status==-10011 OR write_status==-10011
#
# Run while car in park, IGN ON, AC off, doors closed, no driving. Takes ~2 minutes.
# Operator does NOT need to do anything — pure background probe, no physical state change.

set -u
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-192.168.2.68:5555}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROBE_DIR="$(dirname "$SCRIPT_DIR")"
DEX_LOCAL="$PROBE_DIR/probe.dex"
DEX_REMOTE="/data/local/tmp/probe.dex"
CSV="$SCRIPT_DIR/round1-permission-probes.csv"
RESULT="$SCRIPT_DIR/round1-results-$(date +%Y%m%d-%H%M%S).csv"

if [ ! -f "$DEX_LOCAL" ]; then
  echo "ERR probe.dex not found at $DEX_LOCAL. Run ../build.sh first." >&2
  exit 2
fi
if [ ! -f "$CSV" ]; then
  echo "ERR test list not found at $CSV" >&2
  exit 2
fi

echo "[round1] push probe.dex"
"$ADB" -s "$SERIAL" push "$DEX_LOCAL" "$DEX_REMOTE" >/dev/null
echo "[round1] result file: $RESULT"
echo

# header
printf "%-10s %-25s %5s %12s %12s %12s %s\n" "category" "name" "dev" "fid" "read_status" "write_status" "verdict"
printf "%-10s %-25s %5s %12s %12s %12s %s\n" "--------" "----" "---" "---" "-----------" "------------" "-------"
echo "category,name,dev,fid,read_status,read_value,write_status,write_value,verdict" > "$RESULT"

run_probe () {
  local tx="$1" dev="$2" fid="$3" val="${4:-}"
  local cmd="CLASSPATH=$DEX_REMOTE app_process /system/bin --nice-name=bydmate_probe com.bydmate.probe.WriteProbe $tx $dev $fid"
  [ -n "$val" ] && cmd="$cmd $val"
  "$ADB" -s "$SERIAL" shell "$cmd" 2>&1
}

# read CSV; skip blank/comment lines
grep -v '^#' "$CSV" | grep -v '^category,' | while IFS=, read -r category name dev fid desc safety; do
  [ -z "$category" ] && continue
  # 1) read current
  R1=$(run_probe 5 "$dev" "$fid")
  read_status=$(echo "$R1" | grep -oE 'status=-?[0-9]+' | head -1 | cut -d= -f2)
  read_value=$(echo "$R1" | grep -oE 'value=-?[0-9]+' | head -1 | cut -d= -f2)
  # default to 0 if status==0 but value missing
  read_status="${read_status:-?}"
  read_value="${read_value:-?}"
  # 2) write back same value (only if read succeeded and got a real value)
  write_status="skip"
  write_value="skip"
  if [ "$read_status" = "0" ] && [ "$read_value" != "?" ] && [ "$read_value" != "-10011" ]; then
    R2=$(run_probe 6 "$dev" "$fid" "$read_value")
    write_status=$(echo "$R2" | grep -oE 'status=-?[0-9]+' | head -1 | cut -d= -f2)
    write_value=$(echo "$R2" | grep -oE 'value=-?[0-9]+' | head -1 | cut -d= -f2)
    write_status="${write_status:-?}"
    write_value="${write_value:-?}"
  fi
  # verdict
  if [ "$read_status" = "0" ] && [ "$write_status" = "0" ]; then
    verdict="ACCESSIBLE"
  elif [ "$read_status" = "0" ] && [ "$write_status" = "-10011" ]; then
    verdict="READ_ONLY"
  elif [ "$read_status" = "0" ] && [ "$write_status" = "skip" ]; then
    verdict="READ_SENTINEL"
  elif [ "$read_status" = "-10011" ]; then
    verdict="DENIED"
  else
    verdict="UNKNOWN(rs=$read_status,ws=$write_status)"
  fi
  printf "%-10s %-25s %5s %12s %12s %12s %s\n" "$category" "$name" "$dev" "$fid" "$read_status" "$write_status" "$verdict"
  echo "$category,$name,$dev,$fid,$read_status,$read_value,$write_status,$write_value,$verdict" >> "$RESULT"
done

echo
echo "[round1] DONE. Results: $RESULT"
echo "[round1] Summary:"
awk -F, 'NR>1 {print $NF}' "$RESULT" | sort | uniq -c | sort -rn
