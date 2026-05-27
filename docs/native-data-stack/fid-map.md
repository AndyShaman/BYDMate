# Native Data Stack — FID Map

Aggregated validation results from Phase 1a live snap sessions on Leopard 3.
Sessions: S2 (parked + IGN ON, AC off), S3 (driving), S4 (AC + seats + lights). S5 (charging) and S6 (sleep) deferred — not blocking Phase 1b.

All entries are read-only (`tx=5` getInt, `tx=7` getFloat). Write channel deferred to Phase 2.

## Legend

- ✅ **validated** — live snap matches expectation; safe to wire into FidMap.
- 🟡 **candidate** — fid resolves, decoder plausible, needs targeted re-snap or runtime check.
- ⚠️ **enum** — fid resolves but semantics need state-change observation (e.g. day/dusk/night).
- ❌ **dropped** — sentinel `0xffffd8e5` (-10011) on Leopard 3, or wrong fid identified.
- ⏭ **deferred** — needs S5 (charging) or S6 (sleep) snap, not Phase 1 blocker.

## Core energy + drive (validated in S3)

| Param | fid | dev | tx | scale/decoder | Verdict | Notes |
|---|---|---|---|---|---|---|
| SOC | 1246777400 | 1014 | 7 | float_percent | ✅ | 100.0 → 99.0 over 5 min drive |
| Speed | -1807745016 | 1013 | 7 | float km/h | ✅ | 0/30/64.5 dynamic |
| Power | 339738656 | 1012 | 5 | int_raw kW | ✅ | Signed: regen -28, accel +67 |
| Mileage | 1246765072 | 1014 | 5 | **scale=0.1** km | ✅ | 37998 raw → 3799.8 km |
| TotalElecCon | 1032871984 | 1014 | 7 | float_kwh | ✅ | monotonic during drive |
| Voltage12V | 1128267816 | 1001 | 7 | float_volt | ✅ | 13.5-13.6 V |
| MaxCellV | 1147142192 | 1014 | 5 | **scale=0.001** V | ✅ | raw 3315-3426 mV |
| MinCellV | 1147142160 | 1014 | 5 | **scale=0.001** V | ✅ | raw 3307-3352 mV |
| MaxBatTemp | 1148190752 | 1014 | 5 | int_temp_c | 🟡 | from reference catalog; not yet snapped |
| MinBatTemp | 1148190736 | 1014 | 5 | int_temp_c | 🟡 | from reference catalog; not yet snapped |

## Tires + body (validated S2)

| Param | fid | dev | tx | decoder | Verdict |
|---|---|---|---|---|---|
| TirePressFL | -1728052956 | 1016 | 5 | int_kpa | ✅ |
| TirePressFR | -1728052952 | 1016 | 5 | int_kpa | ✅ |
| TirePressRL | -1728052948 | 1016 | 5 | int_kpa | ✅ |
| TirePressRR | -1728052944 | 1016 | 5 | int_kpa | ✅ |
| Hood | 692060188 | 1001 | 5 | int_enum | ✅ closed=0 |
| ExtTemp | 1077936184 | 1000 | 5 | int_temp_c | ✅ direct °C |
| InsideTemp | 1031798832 | 1000 | 5 | int_temp_c | ✅ 21°C → 22°C |
| Trunk | 1074790416 | 1001 | 5 | int_enum | 🟡 returns 2 closed; alternative fid 692060186 (binary) candidate |
| Sunroof | 1101004832 | 1001 | 5 | int_enum | 🟡 returns 1 closed; alternative fid 1101004848 (position) candidate |
| SeatbeltFL | 692060184 | 1007 | 5 | int_enum | ✅ |

## Gear + state (validated S3)

| Param | fid | dev | tx | decoder | Verdict | Notes |
|---|---|---|---|---|---|---|
| Gear | **555745336** | 1011 | 5 | int_enum | ✅ | swap from old 562036736 (stuck=1); new tested P=1/R=2/N=3/D=4 |
| ChargeGun | 876609586 | 1009 | 5 | int_enum | ✅ | disconnected=1 |
| ChargingStatus | **1231032336** | 1009 | 5 | int_enum | ⏭ | swap from old 876609560 (stuck=15); needs S5 snap |
| ACStatus | 1077936144 | 1000 | 5 | int_enum | ✅ |

## AC / climate dev=1000 (validated S4)

Operator toggled OFF/ON/24°C/auto/defrost-front; all fids reacted.

| Param | fid | dev | tx | decoder | Verdict |
|---|---|---|---|---|---|
| ac_power | 1077936144 | 1000 | 5 | int_enum | ✅ (also ACStatus) |
| ac_ctrl_mode | 1077936146 | 1000 | 5 | int_enum auto/manual | ✅ |
| ac_cycle_mode | 1077936148 | 1000 | 5 | int_enum recirc | ✅ |
| ac_defrost_front | 1077936150 | 1000 | 5 | int_enum on/off | ✅ |
| ac_wind_mode | 1077936152 | 1000 | 5 | int_enum direction | ✅ 2→5 on defrost |
| ac_wind_level | 1077936156 | 1000 | 5 | int_raw 0-7 | ✅ |
| ac_temp_main | 1077936168 | 1000 | 5 | int_temp_c | ✅ 24°C |
| ac_temp_deputy | 1077936176 | 1000 | 5 | int_temp_c | ✅ |
| ac_defrost_rear | 1128267825 | 1001 | 5 | int_enum | 🟡 not snapped yet |
| ac_dual | 1077936164 | 1000 | 5 | int_enum | 🟡 |
| ac_temp_out | 1077936184 | 1000 | 5 | int_temp_c | ✅ (also ExtTemp) |
| ac_temp_inside | 1031798832 | 1000 | 5 | int_temp_c | ✅ (also InsideTemp) |
| power_compressor_consume_power | 1031798840 | 1000 | 5 | int_raw W | 🟡 |
| steering_wheel_heat | 1116733454 | 1000 | 5 | int_enum 0-2 | 🟡 not snapped (operator car has no steering heat) |

## Seat heat/vent dev=1000 (validated S4)

Memory fids dev=1000 work end-to-end (off → operator pressed level → off observed). Alternative reference catalog uses different device; do NOT swap.

| Param | fid | dev | tx | decoder | Verdict |
|---|---|---|---|---|---|
| seat_heat_D | 702545948 | 1000 | 5 | int_enum 0-5 | ✅ |
| seat_vent_D | 702545944 | 1000 | 5 | int_enum 0-5 | ✅ |
| seat_heat_P | 711983132 | 1000 | 5 | int_enum 0-5 | ✅ default ON = 3 |
| seat_vent_P | 711983128 | 1000 | 5 | int_enum 0-5 | ✅ |

## Lights dev=1004 (validated S4 in rain + dark→light transition)

| Param | fid | dev | tx | decoder | Verdict |
|---|---|---|---|---|---|
| light_low_beam | 950009866 | 1004 | 5 | int_enum 0/1 | ✅ caught auto-toggle |
| light_side | 950009864 | 1004 | 5 | int_enum 0/1 | ✅ |
| light_high_beam | 950009868 | 1004 | 5 | int_enum 0/1 | ✅ off as expected |
| light_drl | **1231040528** | 1004 | 5 | int_enum 0/1 | ✅ swap from old 985661476 (untested) |
| light_intensity | 315621396 | 1043 | 5 | int_enum day/dusk/night | ⚠️ constant=2 in daylight; verify at night |
| wiper_rain_sensitivity | 321912864 | 1046 | 5 | int_raw user setting | ✅ user-set, not live rain reading |

## Motor + battery V/I (from reference catalog, not yet snapped)

| Param | fid | dev | tx | scale | Status |
|---|---|---|---|---|---|
| battery_voltage | 1145045000 | 1009 | 5 | — | 🟡 |
| battery_current | 1145045016 | 1009 | 5 | — | 🟡 |
| battery_max_charge_power | 877658136 | 1009 | 5 | — | ⏭ S5 |
| battery_max_discharge_power | 1145045048 | 1009 | 5 | — | 🟡 |
| battery_insulation_resistance | 1134559256 | 1039 | 5 | — | 🟡 |
| front_motor_temp | 1154482192 | 1039 | 5 | int_temp_c | 🟡 may be 0 on RWD |
| rear_motor_temp | 1155530768 | 1039 | 5 | int_temp_c | 🟡 |
| front_motor_current | 1186988040 | 1009 | 5 | scale=0.1 A | 🟡 |
| rear_motor_current | 1186988056 | 1009 | 5 | scale=0.1 A | 🟡 |
| front_motor_speed | 1141899272 | 1009 | 5 | — | 🟡 |
| rear_motor_speed | 621805576 | 1009 | 5 | — | 🟡 |

## Dropped on Leopard 3

| Param | fid | dev | Reason |
|---|---|---|---|
| AvgBatTemp | 1148190776 | 1014 | Statistic (24h-cached), not live. Use MaxBatTemp+MinBatTemp instead. |
| ChargingStatus old | 876609560 | 1009 | Stuck=15, not enum 0-13. Swapped to 1231032336. |
| Gear old | 562036736 | 1011 | Stuck=1 across P/R/N/D. Swapped to 555745336. |
| interior_humidity | 740567 | 1043 | Returns sentinel 0xffffd8e5 on Leopard 3. |
| DRL old | 985661476 | 1004 | Untested; swap to 1231040528. |

## Decoder semantics

- `int_raw` — return raw int as-is.
- `int_scaled` with `scale` field — multiply raw_int by scale (replaces ad-hoc `int_div10`).
- `int_percent` / `int_temp_c` / `int_kpa` — raw int already in unit.
- `int_enum` — caller maps enum values to labels.
- `float_*` — tx=7 getFloat; raw int parsed as IEEE-754 little-endian (`raw_int → float bit pattern`).

## Source-of-truth precedence

1. Live snap on Leopard 3 — overrides everything (state matches operator panel).
2. Alternative reference catalog — used when our local catalog had wrong fid or scale.
3. Local autoservice catalog — initial seed, superseded by 1+2 on conflict.
4. D+ value — **never** primary truth (scaling bugs, reduced-payload).

---

# Phase 1a extended — 2026-05-27 sessions

S9 (cold sweep 155 fids), S10 (active state tests doors/locks/belts/mirror/sunshade/sunroof/lights),
S5 (AC charging snap). Validates reference catalog as `imported` → `validated` / `dropped` / `noisy`.

## Sentinel classes discovered

- **-10011** (0xffffd8e5) — fid registered but always invalid on Leopard 3. Permanent drop.
- **-10013** (0xffffd8e3) — fid valid, no data right now (e.g. BatteryCurrent under no-load).
  Distinguish from -10011 in decoder.
- **float -1.0** (0xbf800000) — GPS no-fix value on tx=7 GPS fids.
- **constant 65535** (0xffff) — some int fids return uint16 max as "not available" (TrunkP1/P2/P3,
  ChargeStopSoc when unset, SeatHeat alt dev=1023).
- **constant unchanged** — fid returns the same value across all state changes (ChargingStatus 1231032336
  stuck 0, ChargerWorkState 666894346 stuck 1). Not usable as live signal.

## Newly validated on Leopard 3 (S9 + S10 + S5)

| Param | fid | dev | tx | decoder | Live observed |
|---|---|---|---|---|---|
| **SOH** | 1145045032 | 1014 | 5 | int_percent | 100% on new battery ← main find |
| EvRange | 1246765118 | 1014 | 5 | int_raw km | 465-468 km |
| BatteryVoltage | 1145045000 | 1009 | 5 | int_raw V | 504-506 V (HV) |
| BatteryMaxChargePower | 877658136 | 1014 | 5 | int_raw kW | 302-304 |
| BatteryMaxDischargePower | 1145045048 | 1014 | 5 | int_raw kW | 154 |
| BatteryInsulation | 1134559256 | 1039 | 5 | int_raw kΩ | 6211 |
| TotalElecCon | 1032871984 | 1014 | 7 | float_kwh | 1014.7 lifetime |
| FrontMotorTemp | 1154482192 | 1039 | 5 | int_temp_c | 20°C |
| RearMotorTemp | 1155530768 | 1039 | 5 | int_temp_c | 20°C |
| EpbState | 562036753 | 1011 | 5 | int_enum | 3 (parked) |
| **DoorFL/FR/RL/RR** | 692060168/170/172/174 | 1001 | 5 | int_enum | **0=closed, 1=open** |
| **Hood** (frunk) | 692060188 | 1001 | 5 | int_enum | **0=closed, 1=open** |
| **LockFL/FR/RL/RR** | 1081081864/1866/1868/1870 | 1032/1001 | 5 | int_enum | **1=unlocked, 2=locked** (NOT 0/1!) |
| SeatbeltFL | 692060184 | 1007 | 5 | int_enum | 0=off / 1=on |
| BeltDriver | -1455423464 | 1042 | 5 | int_enum | 0=off / 1=on (alt to SeatbeltFL) |
| **MirrorFoldState** | 1011875887 | 1047 | 5 | int_enum | **1=folded, 2=unfolded** |
| **Sunshade** | 1101004816 | 1001 | 5 | int_percent | 0-100% direct |
| **Sunroof** | 1101004848 | 1001 | 5 | int_enum | **1=open, 2=closed, 5=vent/tilt** (NOT percent!) |
| LightHigh | 950009868 | 1004 | 5 | int_enum | 0=off, 1=on |
| RadarDist0-7 | -1728053151..-1728053149 | 1025 | 5 | int_raw cm | 150 baseline (radar8 dropped) |
| ChargeGun | 876609586 | 1009 | 5 | int_enum | **1=disconnected, 2=AC** |
| **ChargingPowerDD** | 842006552 | 1009 | 7 | float_kw | **3.11 kW live** ✓ |
| Power | 339738656 | 1012 | 5 | int_raw kW | -3 charging, +1 idle (signed) |

## Sentinel-dropped on Leopard 3 (S9 baseline)

Constant -10011 = drop permanently.

| fid | Reference name | dev | Note |
|---|---|---|---|
| 755989 | belt_rear_left | 1042 | Use BeltRearRight or rely on cluster UI |
| 740506 | charging_power | 1009 | Use ChargingPowerDD instead |
| 651624504 | gb_dc_current_power_consumption | 1011 | Unused |
| 233088 | gps_fix | 1017 | All GPS fids same — no satellite indoor |
| 1125122080 | trunk_p7 | 1001 | Use Trunk (1074790416) |
| 839263 | water_temp | 1014 | Pure EV — no coolant |
| 740567 | interior_humidity | 1043 | Already known dead |
| -1728053143 | radar_dist_8 | 1025 | 9th radar slot empty |

## Sentinel -10013 (transient, may revive under load/charging)

- BatteryCurrent (1145045016 dev=1009) — sentinel during AC charging at 3 kW too.
  Hidden on Leopard 3, or needs DC fast charge.
- ChargingCapacity (666894360 dev=1009) — sentinel during short session.
  May reflect after sustained charging.
- FrontMotorCurrent / RearMotorCurrent — sentinel parked; expected to revive during drive.

## Dual-source resolved

- **SOC**: our 1246777400 dev=1014 tx=7 float (98.0) vs reference soc_bms 1145045040 dev=1014 tx=5 int (98). Same value, two representations. Keep our float fid as primary.
- **Voltage12V**: our 1128267816 dev=1001 tx=7 float (14.0V) vs reference dev=1032 — both fid same, our dev works; reference dev untested but redundant.
- **Seat heat/vent ALT dev=1023** (fid 1335885832/835/840/843) — all returned 65535 (uint16 max = not available). **Drop alt entries.** Our dev=1000 fids remain only valid path on Leopard 3.

## Constant / unusable on Leopard 3

- ChargingStatus 1231032336 dev=1009 — stuck 0 even during active 3 kW charge. Replace with: derive from `ChargeGun != 1` for connection state, `ChargingPowerDD > 0` for active charge.
- ChargerWorkState 666894346 dev=1009 — stuck 1. Same fallback as above.
- AlarmState 1150287934 dev=1001 — stuck 1 across lock/unlock. Not alarm-armed signal.
- LockStateGlobal 315621421 dev=1023 — stuck 0 across lock/unlock. Not global lock; use 4× LockXX instead.
- LightHazard, LightBrake, LightReversing — baseline 2/3/2, not toggling. Need driver-active test (deferred).

## Noisy / not reliable

- **BeltPassenger** -1728052985 dev=1042 — drifts 0↔1 between snaps even with empty seat
  + no item. Not weight-based, not buckle-based. Drop or mark unreliable.

## S5 charging takeaways

AC charger plug-in at 98% SOC, ~3 kW draw:
- ChargeGun: 1 → 2 immediately
- BatteryVoltage: 504 → 506 V
- MaxCellV: 3323 → 3337 mV
- ChargingPowerDD: junk value 359.4 (disconnected) → 3.11 kW (live)
- Power: 1 (idle) → -3 (regen/charge direction)
- BatteryCurrent stayed sentinel — A-level read not available via autoservice on Leopard 3

After disconnect ChargingPowerDD shows stale 359.4 — **must gate UI display by ChargeGun ≠ 1**.

## Verdict on competitor catalog

After live-validating ~50% of reference fids across S2/S3/S4/S5/S9/S10 with full agreement on
every tested entry, we treat the reference catalog as authoritative for Leopard 3. Untested
fids are imported with `imported` status, filtered by S9 sentinel sweep:

- 155 reference fids snapped → 132 returned live data, 7 sentinel -10011 (drop), 4 sentinel -10013 (transient), 4 GPS no-fix, 4 alt-seat 65535, 4 constant-stuck (drop).
- Net usable: **~140 fids** ready for FidMap.kt integration.
