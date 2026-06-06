#!/usr/bin/env python3
"""Compare D+ vs autoservice snapshots, emit per-param outcome.

Outcomes: PASS, TOLERANCE, SENTINEL, MISSING, MISMATCH, SKIPPED.

Usage:
    diff.py <yaml> <diplus.json> <autoservice.json>

Sentinel ints (from app/src/main/kotlin/.../SentinelDecoder.kt): -10011, -10005,
0xFFFFD8E5 (= -10011 as signed int32). Also detect raw_int==0xFFFFD8E5 explicitly.
"""
import json, struct, sys, yaml

SENTINEL_INTS = {-10011, -10005}
SENTINEL_HEX = {0xFFFFD8E5}

TOLERANCE = {
    "int_raw": 0,
    "int_div10": 0,           # exact after divide
    "int_percent": 0,
    "int_enum": 0,
    "int_temp_c": 1,
    "int_kpa": 0,
    "float_volt": 0.01,
    "float_percent": 0.5,
    "float_kw": 0.5,
    "float_kwh": 0.05,
    "static_user_setting": 0,
}


def decode(decoder, raw_int):
    if raw_int is None:
        return None
    if raw_int in SENTINEL_INTS or (raw_int & 0xFFFFFFFF) in SENTINEL_HEX:
        return "SENTINEL"
    if decoder == "int_raw" or decoder == "int_percent" or decoder == "int_enum" or decoder == "int_kpa":
        return raw_int
    if decoder == "int_div10":
        return raw_int / 10.0
    if decoder == "int_temp_c":
        return raw_int
    if decoder in ("float_volt", "float_percent", "float_kw", "float_kwh"):
        # int bits → float
        return struct.unpack("<f", struct.pack("<i", raw_int))[0]
    return raw_int


def to_number(s):
    if s is None:
        return None
    try:
        if "." in s:
            return float(s)
        return int(s)
    except (TypeError, ValueError):
        return None


def compare(decoder, diplus_val, native_val):
    if native_val == "SENTINEL":
        return "SENTINEL", None
    dn = to_number(diplus_val) if isinstance(diplus_val, str) else diplus_val
    if dn is None and native_val is None:
        return "MISSING", None
    if dn is None:
        return "MISSING", f"diplus missing, native={native_val}"
    if native_val is None:
        return "MISSING", f"native missing, diplus={dn}"
    tol = TOLERANCE.get(decoder, 0)
    delta = abs(float(dn) - float(native_val))
    if delta <= tol:
        return "PASS", f"delta={delta}"
    return "MISMATCH", f"diplus={dn} native={native_val} delta={delta}"


def main():
    if len(sys.argv) != 4:
        sys.exit("Usage: diff.py <yaml> <diplus.json> <autoservice.json>")

    candidates = {e["param"]: e for e in yaml.safe_load(open(sys.argv[1]))}
    diplus = json.load(open(sys.argv[2]))
    native = json.load(open(sys.argv[3]))

    print(f"{'PARAM':<18} {'OUTCOME':<11} {'DECODER':<22} DETAIL")
    print("-" * 90)
    counts = {}
    for p, entry in candidates.items():
        decoder = entry["decoder"]
        raw_ns = native.get(p, {})
        if raw_ns.get("skipped"):
            outcome, detail = "SKIPPED", "no candidate fid"
        elif "error" in raw_ns:
            outcome, detail = "MISSING", raw_ns["error"]
        else:
            native_decoded = decode(decoder, raw_ns.get("raw_int"))
            outcome, detail = compare(decoder, diplus.get(p), native_decoded)
        counts[outcome] = counts.get(outcome, 0) + 1
        print(f"{p:<18} {outcome:<11} {decoder:<22} {detail or ''}")

    print()
    print("Totals:", " ".join(f"{k}={v}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
