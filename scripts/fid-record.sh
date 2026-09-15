#!/usr/bin/env bash
# Diagnostic fid recorder on the head unit: subscribes the helper daemon to every fid of whole
# BYD devices and writes each changed value to /sdcard/Download/bydmate_fidrec_<stamp>.txt.
# logcat gets the summary only. -test builds only (the receiver ignores a public APK).
# The same recorder is switchable in the app: Настройки -> «Приложение и данные» -> «Регистратор датчиков».
#
#   scripts/fid-record.sh start [devs]   # devs = "1001,1004"; omitted = every known device
#   scripts/fid-record.sh stop
#   scripts/fid-record.sh status
#   scripts/fid-record.sh watch          # live logcat of the recorder's summary lines
#   scripts/fid-record.sh pull           # copy the newest recording off the head unit
#
# ADB_TARGET overrides the head unit address (default: the DiLink over Wi-Fi).
set -euo pipefail

ADB_TARGET="${ADB_TARGET:-192.168.2.69:5555}"
PKG="com.bydmate.app"
ACTION="com.bydmate.app.FID_RECORD"

cmd="${1:-status}"
devs="${2:-}"

case "$cmd" in
  start|stop|status)
    args=(-a "$ACTION" -p "$PKG" --es cmd "$cmd")
    if [ -n "$devs" ]; then args+=(--es devs "$devs"); fi
    adb -s "$ADB_TARGET" shell am broadcast "${args[@]}"
    echo "--- FidRec (Ctrl-C to stop) ---"
    adb -s "$ADB_TARGET" logcat -s FidRec
    ;;
  pull)
    # Newest by name: the file name carries the start stamp (bydmate_fidrec_yyyyMMdd_HHmmss.txt),
    # so the last line of a plain sort is the last run — the biggest file is usually an older one.
    adb -s "$ADB_TARGET" shell ls /sdcard/Download/bydmate_fidrec_'*'.txt | sort | tail -1 | tr -d '\r' | \
      xargs -I{} adb -s "$ADB_TARGET" pull {} .
    ;;
  watch)
    adb -s "$ADB_TARGET" logcat -s FidRec
    ;;
  *)
    echo "usage: $0 start|stop|status|watch|pull [devs]" >&2
    exit 2
    ;;
esac
