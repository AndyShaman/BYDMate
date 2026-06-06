# Native Stack Validation Suite (Phase 1a)

Cross-checks autoservice fid candidates against live D+ HTTP responses on Leopard 3.

## Prereqs
- Mac with `adb`, `python3`, `pip3 install pyyaml`
- DiLink reachable: `adb -s 192.168.2.68:5555 shell echo ok` returns `ok`
- BYDMate force-stopped: `adb -s 192.168.2.68:5555 shell am force-stop com.bydmate.app`
- D+ running (drives 8988 HTTP)

## Run
    ./validate.sh --state s2 --count 5

State codes: s2 (parked+IGN ON), s3 (driving), s4 (AC active), s5 (AC charging), s6 (sleep probe).

Output:
- raw snapshots → ../../.research/leopard3-pulled/native-stack-validation-YYYY-MM-DD-<state>.json
- public fid-map updates → ../../docs/native-data-stack/fid-map.md (manual review before commit)
