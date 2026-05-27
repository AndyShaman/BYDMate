#!/usr/bin/env bash
# Iterate fid-candidates.yaml, call `service call autoservice` on the device,
# emit JSON: {param: {raw_parcel: "...", raw_int: N, error: "..."}}
#
# Usage: autoservice-snapshot.sh <yaml-path> <adb-target>
# Example: autoservice-snapshot.sh fid-candidates.yaml 192.168.2.68:5555
set -euo pipefail

YAML="${1:?yaml path required}"
ADB_TARGET="${2:?adb target required}"

if ! command -v python3 >/dev/null; then echo "python3 required" >&2; exit 2; fi
if ! command -v adb >/dev/null; then echo "adb required" >&2; exit 2; fi

python3 <<EOF
import sys, yaml, subprocess, json, re, time

ADB = "adb -s ${ADB_TARGET}".split()
PARCEL_RE = re.compile(r"Parcel\(00000000\s+([0-9a-fA-F]{8})")
ALLOWED_TX = {5, 7}  # READ-ONLY guarantee. tx=6 (setInt) is FORBIDDEN in Phase 1.
RATE_LIMIT_SEC = 0.25  # ≤4 req/sec, under spec's 5 req/sec ceiling.

entries = yaml.safe_load(open("${YAML}"))
out = {}
for e in entries:
    p = e["param"]
    if not e.get("fid") or not e.get("device") or not e.get("transact"):
        out[p] = {"skipped": True, "reason": "no candidate fid"}
        continue
    tx, dev, fid = e["transact"], e["device"], e["fid"]
    if tx not in ALLOWED_TX:
        out[p] = {"error": f"forbidden transact code {tx}; only 5/7 allowed in Phase 1"}
        continue
    cmd = ADB + ["shell", "service", "call", "autoservice",
                 str(tx), "i32", str(dev), "i32", str(fid)]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=4)
        m = PARCEL_RE.search(r.stdout)
        if not m:
            out[p] = {"raw_parcel": r.stdout.strip(), "raw_int": None,
                      "error": "no parcel match"}
        else:
            ri = int(m.group(1), 16)
            if ri >= 0x80000000:
                ri -= 0x100000000
            out[p] = {"raw_parcel": m.group(1), "raw_int": ri, "transact": tx,
                      "device": dev, "fid": fid}
    except Exception as ex:
        out[p] = {"error": str(ex)}
    time.sleep(RATE_LIMIT_SEC)

print(json.dumps(out, indent=2, ensure_ascii=False))
EOF
