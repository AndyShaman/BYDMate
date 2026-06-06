#!/usr/bin/env python3
# Parse on-device runner output into per-snap JSON in autoservice-snapshot.sh format.
# Reads `.research/leopard3-pulled/work-<state>/snap-<i>/native-<param>.raw` files
# and emits `native-<i>.json` files in the same dir.
#
# Usage: parse-on-device.py <work-dir>
# Example: parse-on-device.py .research/leopard3-pulled/work-s3

import sys, os, re, json, glob

PARCEL_RE = re.compile(r"Parcel\(00000000\s+([0-9a-fA-F]{8})")

def parse_raw(path):
    with open(path) as f:
        text = f.read()
    m = PARCEL_RE.search(text)
    if not m:
        return {"raw_parcel": text.strip()[:200], "raw_int": None, "error": "no parcel match"}
    ri = int(m.group(1), 16)
    if ri >= 0x80000000:
        ri -= 0x100000000
    return {"raw_parcel": m.group(1), "raw_int": ri}

def main():
    if len(sys.argv) != 2:
        print("usage: parse-on-device.py <work-dir>", file=sys.stderr)
        sys.exit(2)
    work = sys.argv[1]
    if not os.path.isdir(work):
        print(f"not a dir: {work}", file=sys.stderr)
        sys.exit(2)
    snaps = sorted(glob.glob(os.path.join(work, "snap-*")))
    if not snaps:
        print(f"no snap-* dirs in {work}", file=sys.stderr)
        sys.exit(2)
    for sdir in snaps:
        idx = sdir.rsplit("snap-", 1)[1]
        out = {}
        ts_path = os.path.join(sdir, "ts.txt")
        if os.path.exists(ts_path):
            out["_ts"] = int(open(ts_path).read().strip())
        for raw in sorted(glob.glob(os.path.join(sdir, "native-*.raw"))):
            param = os.path.basename(raw).removeprefix("native-").removesuffix(".raw")
            out[param] = parse_raw(raw)
        out_path = os.path.join(work, f"native-{idx}.json")
        with open(out_path, "w") as f:
            json.dump(out, f, indent=2, ensure_ascii=False)
        valid = sum(1 for k, v in out.items() if not k.startswith("_") and v.get("raw_int") is not None)
        total = sum(1 for k in out if not k.startswith("_"))
        print(f"snap {idx}: {valid}/{total} valid → {out_path}", file=sys.stderr)

if __name__ == "__main__":
    main()
