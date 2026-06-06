#!/usr/bin/env bash
# Orchestrates one validation state: N paired snapshots, then diff per snapshot
# and aggregate. Writes raw outputs to .research/leopard3-pulled/.
#
# Usage: validate.sh --state <s2|s3|s4|s5|s6> --count N [--target IP:PORT]
set -euo pipefail

STATE=""
COUNT=5
TARGET="192.168.2.68:5555"
INTERVAL=2

while [[ $# -gt 0 ]]; do
  case "$1" in
    --state) STATE="$2"; shift 2 ;;
    --count) COUNT="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    *) echo "Unknown arg $1" >&2; exit 2 ;;
  esac
done

[[ -n "$STATE" ]] || { echo "--state required (s2..s6)" >&2; exit 2; }

DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/.research/leopard3-pulled"
mkdir -p "$OUT_DIR"
DATE=$(date -u +%Y-%m-%d)
LOG="$OUT_DIR/native-stack-validation-${DATE}-${STATE}.json"

echo "[validate] state=$STATE count=$COUNT target=$TARGET interval=$INTERVAL" >&2
echo "[validate] preflight..." >&2
adb -s "$TARGET" shell "service check autoservice" >/dev/null \
  || { echo "autoservice not found on device" >&2; exit 3; }

# Multi-process contention guard: BYDMate must NOT be polling autoservice
# while we snapshot — different transact code timing skews results.
echo "[validate] force-stopping BYDMate to prevent autoservice contention..." >&2
adb -s "$TARGET" shell "am force-stop com.bydmate.app" >/dev/null
sleep 1
RUNNING=$(adb -s "$TARGET" shell "pidof com.bydmate.app || true" | tr -d '\r\n')
if [[ -n "$RUNNING" ]]; then
  echo "BYDMate still running (pid=$RUNNING) after force-stop. Abort." >&2
  exit 4
fi

mkdir -p "$OUT_DIR/work-${STATE}"

snapshots=()
for i in $(seq 1 "$COUNT"); do
  ts=$(date -u +%s)
  echo "[validate] snap $i/$COUNT at ts=$ts" >&2
  diplus_f="$OUT_DIR/work-${STATE}/diplus-${i}.json"
  native_f="$OUT_DIR/work-${STATE}/native-${i}.json"
  "$DIR/diplus-snapshot.sh" "$TARGET" > "$diplus_f"
  "$DIR/autoservice-snapshot.sh" "$DIR/fid-candidates.yaml" "$TARGET" > "$native_f"
  snapshots+=("$ts,$diplus_f,$native_f")
  [[ $i -lt $COUNT ]] && sleep "$INTERVAL"
done

# Per-snapshot diff dump
echo "[validate] writing $LOG" >&2
SNAPSHOTS_TSV=$(printf '%s\n' "${snapshots[@]}")
STATE="$STATE" TARGET="$TARGET" COUNT="$COUNT" LOG="$LOG" SNAPSHOTS_TSV="$SNAPSHOTS_TSV" python3 <<'EOF'
import os, json

state = os.environ["STATE"]
target = os.environ["TARGET"]
count = int(os.environ["COUNT"])
log = os.environ["LOG"]
snapshots_tsv = os.environ["SNAPSHOTS_TSV"]

out = {"state": state, "target": target, "count": count, "snapshots": []}
for line in snapshots_tsv.splitlines():
    line = line.strip()
    if not line:
        continue
    ts, df, nf = line.split(",", 2)
    with open(df) as fh:
        diplus_data = json.load(fh)
    with open(nf) as fh:
        native_data = json.load(fh)
    out["snapshots"].append({
        "ts": int(ts),
        "diplus": diplus_data,
        "autoservice": native_data,
    })
with open(log, "w") as fh:
    json.dump(out, fh, indent=2, ensure_ascii=False)
print("[validate] saved", log, "with", len(out["snapshots"]), "snapshots")
EOF

# Diff report for first snapshot (operator reads aloud)
echo
echo "=== Diff report (snapshot 1) ==="
"$DIR/diff.py" "$DIR/fid-candidates.yaml" \
  "$OUT_DIR/work-${STATE}/diplus-1.json" \
  "$OUT_DIR/work-${STATE}/native-1.json"
