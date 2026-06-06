#!/bin/bash
# Phase 2c Round 2 — interior/ambient light + DRL write validation.
#
# Writes each candidate light fid via probe.dex (app_process shell uid, tx=6 setInt
# = identical transact to the shipping helper daemon). For each fid: write ON,
# wait for operator to observe the car, write OFF, print both RESULT lines.
#
# Values are RAW (already account for BYD setter conversions, e.g. ambient +1 shift).
# Source: docs/native-data-stack/oncar-light-validation-2026-05-28.md
#
# PASS = status >= 0 AND light physically changes. FAIL = status < 0 / SecurityException.
#
# Run while car in park, IGN ON, заряд ≥30%. Operator watches the cabin/exterior.

set -u
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-192.168.2.68:5555}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROBE_DIR="$(dirname "$SCRIPT_DIR")"
DEX_LOCAL="$PROBE_DIR/probe.dex"
DEX_REMOTE="/data/local/tmp/probe.dex"

if [ ! -f "$DEX_LOCAL" ]; then
  echo "ERR probe.dex not found at $DEX_LOCAL. Run $PROBE_DIR/build.sh first." >&2
  exit 2
fi

echo "[round2] push probe.dex -> $DEX_REMOTE"
"$ADB" -s "$SERIAL" push "$DEX_LOCAL" "$DEX_REMOTE" >/dev/null

write_probe () {
  local dev="$1" fid="$2" val="$3"
  "$ADB" -s "$SERIAL" shell \
    "CLASSPATH=$DEX_REMOTE app_process /system/bin --nice-name=bydmate_probe \
     com.bydmate.probe.WriteProbe 6 $dev $fid $val" </dev/null 2>&1
}

# test: label dev fid on_val off_val watch_text
run_test () {
  local label="$1" dev="$2" fid="$3" on="$4" off="$5" watch="$6"
  echo
  echo "════════════════════════════════════════════════════════════"
  echo "  $label   (dev=$dev fid=$fid)"
  echo "  Наблюдать: $watch"
  echo "════════════════════════════════════════════════════════════"
  echo "[ON  val=$on]"
  write_probe "$dev" "$fid" "$on"
  read -r -p "  >> Свет изменился? Enter — выключаю..." _
  echo "[OFF val=$off]"
  write_probe "$dev" "$fid" "$off"
  read -r -p "  >> Записал результат? Enter — следующий тест..." _
}

echo
echo "=== PRIORITY (паритет): свет салона + ambient ==="
run_test "#1 Свет салона (плафон)"  1023 1330643002 2 1 "плафон/салонные лампы вкл→выкл"
run_test "#2 Ambient — яркость"     1023 1069547536 5 0 "подсветка салона ярче→гаснет"

echo
read -r -p "Тестировать дополнительные ambient (#3-#6)? [y/N] " more
if [ "${more:-N}" = "y" ] || [ "${more:-N}" = "Y" ]; then
  echo
  echo "=== #3 Ambient зона: пишу 1, 2, 3 — наблюдай какая зона ==="
  for z in 1 2 3; do
    echo "[zone=$z]"; write_probe 1023 1069547540 "$z"
    read -r -p "  >> Какая зона? Enter — дальше..." _
  done
  echo
  echo "=== #4 Ambient цвет: 5(冷蓝) 26(红) 14(黄) ==="
  for c in 5 26 14; do
    echo "[color=$c]"; write_probe 1023 1069547528 "$c"
    read -r -p "  >> Какой цвет? Enter — дальше..." _
  done
  run_test "#5 Длит. салонного света" 1023 1043333154 4 1 "таймаут гашения (status>=0 достаточно)"
  run_test "#6 Welcome light"         1023 1330643013 1 2 "приветственная подсветка (может не сработать в P)"
fi

echo
echo "════════════════════════════════════════════════════════════"
echo "  #7 ДХО / DRL — dev=1004 BANNED namespace"
echo "  Это снап ПЕРЕД разбаном. Подтверди, что готов писать в dev=1004."
echo "════════════════════════════════════════════════════════════"
read -r -p "Тестировать ДХО (dev=1004)? [y/N] " drl
if [ "${drl:-N}" = "y" ] || [ "${drl:-N}" = "Y" ]; then
  run_test "#7 ДХО (DRL)" 1004 1125122118 1 2 "дневные ходовые огни вкл→выкл"
fi

echo
echo "[round2] DONE. Занеси PASS/FAIL + status в"
echo "         docs/native-data-stack/oncar-light-validation-2026-05-28.md"
