#!/usr/bin/env python3
"""Extract competitor defaults.actions slice into app asset. Idempotent."""
import json, pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[4]
SRC = ROOT / ".research/competitor-v2/pushFidConfig.json"
DST = ROOT / "app/src/main/assets/competitor-actions.json"
data = json.loads(SRC.read_text())
actions = data["defaults"]["actions"]
DST.parent.mkdir(parents=True, exist_ok=True)
DST.write_text(json.dumps(actions, ensure_ascii=False, indent=2, sort_keys=True))
print(f"wrote {len(actions)} actions to {DST}", file=sys.stderr)
