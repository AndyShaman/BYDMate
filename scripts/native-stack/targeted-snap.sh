#!/bin/bash
# Quick targeted autoservice snap. Reads selected params from yaml + pulls.
# Usage: ./targeted-snap.sh <label> <param1> <param2> ...
#
# Example: ./targeted-snap.sh handles-retracted DoorFL DoorFR DoorRL DoorRR LockFL LockFR LockRL LockRR LockStateGlobal
#
# Output: .research/leopard3-pulled/s10-targeted/<label>/native-*.raw + native-1.json

set -u
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SERIAL=${SERIAL:-192.168.2.68:5555}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
YAML="$REPO/scripts/native-stack/fid-candidates.yaml"

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <label> <param1> [param2 ...]" >&2
  exit 2
fi

LABEL="$1"
shift
PARAMS=("$@")

OUT_REL="$REPO/.research/leopard3-pulled/s10-targeted/$LABEL"
mkdir -p "$OUT_REL"

# Build on-device sh from yaml entries matching given params.
TMP_SH=$(mktemp -t s10-snap.XXXXXX.sh)
python3 - "$YAML" "$LABEL" "${PARAMS[@]}" <<'PYEOF' > "$TMP_SH"
import sys, yaml
yaml_path, label, *params = sys.argv[1:]
entries = yaml.safe_load(open(yaml_path))
by_name = {e["param"]: e for e in entries if isinstance(e, dict) and e.get("fid") is not None}
print("#!/system/bin/sh")
print(f"BASE=/sdcard/Download/native-stack/s10-targeted/{label}")
print('mkdir -p "$BASE"')
print(f'date -u +%s > "$BASE/ts.txt"')
print(f'echo "[runner] label={label}" > "$BASE/runner.log"')
for p in params:
    if p not in by_name:
        print(f'echo "MISSING-PARAM {p}" >&2', file=sys.stderr)
        continue
    e = by_name[p]
    print(f'service call autoservice {e["transact"]} i32 {e["device"]} i32 {e["fid"]} > "$BASE/native-{p}.raw" 2>&1')
    print('sleep 0.1')
print(f'echo "[runner] done" >> "$BASE/runner.log"')
PYEOF

REMOTE_SH="/sdcard/Download/native-stack/s10-targeted/$LABEL/snap.sh"
$ADB -s "$SERIAL" shell "mkdir -p /sdcard/Download/native-stack/s10-targeted/$LABEL"
$ADB -s "$SERIAL" push "$TMP_SH" "$REMOTE_SH" >/dev/null
$ADB -s "$SERIAL" shell "sh $REMOTE_SH"
$ADB -s "$SERIAL" pull "/sdcard/Download/native-stack/s10-targeted/$LABEL" "$REPO/.research/leopard3-pulled/s10-targeted/" 2>&1 | tail -1

# Parse on Mac.
python3 - "$OUT_REL" <<'PYEOF'
import sys, os, re, json, glob
PARCEL_RE = re.compile(r"Parcel\(00000000\s+([0-9a-fA-F]{8})")
d = sys.argv[1]
out = {}
ts_p = os.path.join(d, "ts.txt")
if os.path.exists(ts_p):
    out["_ts"] = int(open(ts_p).read().strip())
for raw in sorted(glob.glob(os.path.join(d, "native-*.raw"))):
    param = os.path.basename(raw).removeprefix("native-").removesuffix(".raw")
    text = open(raw).read()
    m = PARCEL_RE.search(text)
    if m:
        ri = int(m.group(1), 16)
        if ri >= 0x80000000:
            ri -= 0x100000000
        out[param] = {"raw_int": ri, "raw_parcel": m.group(1)}
    else:
        out[param] = {"raw_int": None, "raw_parcel": None}
with open(os.path.join(d, "snap.json"), "w") as f:
    json.dump(out, f, indent=2, ensure_ascii=False)
print(f"\n=== {os.path.basename(d)} ===")
for k, v in out.items():
    if k.startswith("_"):
        continue
    ri = v["raw_int"]
    print(f"  {k:25s} = {ri}")
PYEOF

rm -f "$TMP_SH"
