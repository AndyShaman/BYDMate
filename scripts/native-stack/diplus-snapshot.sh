#!/usr/bin/env bash
# Fetch the full DiParsClient TEMPLATE response via curl on the device.
# Emits JSON: {param: stringValue}
#
# Usage: diplus-snapshot.sh <adb-target>
set -euo pipefail

ADB_TARGET="${1:?adb target required}"

TEMPLATE='SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}|ChargingStatus:{充电状态}|BatCapacity:{电池容量}|TotalElecCon:{总电耗}|Voltage12V:{蓄电池电压}|MaxCellV:{最高电池电压}|MinCellV:{最低电池电压}|ExtTemp:{车外温度}|Gear:{档位}|PowerState:{电源状态}|InsideTemp:{车内温度}|ACStatus:{空调状态}|ACTemp:{主驾驶空调温度}|FanLevel:{风量档位}|ACCirc:{空调循环方式}|DoorFL:{主驾车门}|DoorFR:{副驾车门}|DoorRL:{左后车门}|DoorRR:{右后车门}|WindowFL:{主驾车窗打开百分比}|WindowFR:{副驾车窗打开百分比}|WindowRL:{左后车窗打开百分比}|WindowRR:{右后车窗打开百分比}|Sunroof:{天窗打开百分比}|Trunk:{后备箱门}|Hood:{引擎盖}|SeatbeltFL:{主驾驶安全带状态}|LockFL:{主驾车门锁}|TirePressFL:{左前轮气压}|TirePressFR:{右前轮气压}|TirePressRL:{左后轮气压}|TirePressRR:{右后轮气压}|DriveMode:{整车运行模式}|WorkMode:{整车工作模式}|AutoPark:{自动驻车}|Rain:{雨量}|LightLow:{近光灯}|DRL:{日行灯}'

ENCODED=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$TEMPLATE")

# Allow adb/curl to fail without aborting the script — we still need to emit JSON.
set +e
RAW=$(adb -s "$ADB_TARGET" shell "curl -s 'http://127.0.0.1:8988/api/getDiPars?text=${ENCODED}'" 2>&1)
RC=$?
set -e

RAW="$RAW" RC="$RC" python3 <<'EOF'
import os, json, sys
raw = os.environ["RAW"]
rc = int(os.environ["RC"])
if rc != 0:
    print(json.dumps({"_error": f"adb/curl failed rc={rc}", "_raw": raw[:400]}))
    sys.exit(0)
try:
    j = json.loads(raw)
except Exception as e:
    print(json.dumps({"_error": f"json parse failed: {e}", "_raw": raw[:400]}))
    sys.exit(0)

if not j.get("success"):
    print(json.dumps({"_error": "diplus success=false", "_raw": raw[:400]}))
    sys.exit(0)

out = {}
for part in (j.get("val") or "").split("|"):
    if ":" in part:
        k, v = part.split(":", 1)
        out[k] = v
print(json.dumps(out, indent=2, ensure_ascii=False))
EOF
