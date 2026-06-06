#!/usr/bin/env python3
"""Compare reference fid catalog vs our fid-candidates.yaml.

Outputs four sections:

  IMPORTED      — (fid, dev) pair present in both. Already covered.
  DEV_MISMATCH  — same fid in both but different device. Re-check our dev.
  DUAL_FID      — different fids for same logical parameter (by name hint).
                  Means both sides have an alternate source — investigate.
  NOT_IN_YAML   — reference has it, we don't. Candidate to import.
  ONLY_IN_YAML  — we have it, reference does not. Our own finding.

Usage:
    reference-diff.py [path/to/pushFidConfig.json] [path/to/fid-candidates.yaml]

Defaults to local artefacts.
"""
import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip3 install pyyaml\n")
    sys.exit(1)

REPO = Path(__file__).resolve().parent.parent.parent
DEFAULT_REF = REPO / ".research" / "competitor-v2" / "pushFidConfig.json"
DEFAULT_YAML = REPO / "scripts" / "native-stack" / "fid-candidates.yaml"

# Hint mapping: reference key → our param name (for DUAL_FID detection).
NAME_HINTS = {
    "soc_bms": "SOC",
    "soh": "SOH",
    "total_mileage": "Mileage",
    "cell_max_voltage": "MaxCellV",
    "cell_min_voltage": "MinCellV",
    "battery_max_temp": "MaxBatTemp",
    "battery_min_temp": "MinBatTemp",
    "battery_voltage": "BatteryVoltage",
    "battery_current": "BatteryCurrent",
    "aux_voltage": "Voltage12V",
    "engine_power": "Power",
    "vehicle_speed": "Speed",
    "gear_position": "Gear",
    "vehicle_state": "PowerState",
    "drive_mode": "DriveMode",
    "energy_mode": "WorkMode",
    "charging_gun_state": "ChargeGun",
    "charging_state": "ChargingStatus",
    "ac_power": "ACStatus",
    "ac_temp_main": "ACTemp",
    "ac_temp_inside": "InsideTemp",
    "ac_temp_out": "ExtTemp",
    "ac_wind_level": "FanLevel",
    "ac_cycle_mode": "ACCirc",
    "driver_seat_heat": "SeatHeatD",
    "driver_seat_vent": "SeatVentD",
    "passenger_seat_heat": "SeatHeatP",
    "passenger_seat_vent": "SeatVentP",
    "door_lf": "DoorFL",
    "door_rf": "DoorFR",
    "door_lr": "DoorRL",
    "door_rr": "DoorRR",
    "door_hood": "Hood",
    "lock_lf": "LockFL",
    "lock_rf": "LockFR",
    "lock_lr": "LockRL",
    "lock_rr": "LockRR",
    "window_lf_pos": "WindowFL",
    "window_rf_pos": "WindowFR",
    "window_lr_pos": "WindowRL",
    "window_rr_pos": "WindowRR",
    "tyre_pressure_lf": "TirePressFL",
    "tyre_pressure_rf": "TirePressFR",
    "tyre_pressure_lr": "TirePressRL",
    "tyre_pressure_rr": "TirePressRR",
    "tyre_temp_lf": "TireTempFL",
    "tyre_temp_rf": "TireTempFR",
    "tyre_temp_lr": "TireTempRL",
    "tyre_temp_rr": "TireTempRR",
    "light_low_beam": "LightLow",
    "light_side": "LightSide",
    "light_high_beam": "LightHigh",
    "light_drl": "DRL",
    "light_intensity": "LightIntensity",
    "wiper_rain_sensitivity": "Rain",
    "sunroof_pos": "Sunroof",
    "sunshade_pos": "SunshadePos",
    "trunk_p4": "Trunk",
    "belt_driver": "BeltDriver",
    "belt_passenger": "BeltPassenger",
    "belt_rear_left": "BeltRearLeft",
    "belt_rear_right": "BeltRearRight",
    "epb_state": "EpbState",
    "brake_pedal_state": "BrakeState",
    "accel_pedal_depth": "AccelPedal",
    "brake_pedal_depth": "BrakePedal",
    "front_motor_temp": "FrontMotorTemp",
    "rear_motor_temp": "RearMotorTemp",
    "front_motor_current": "FrontMotorCurrent",
    "rear_motor_current": "RearMotorCurrent",
    "total_elec_consumption": "TotalElecCon",
    "steering_wheel_heat": "SteeringHeat",
    "ac_defrost_front": "ACDefrostFront",
    "ac_defrost_rear": "ACDefrostRear",
    "ac_dual": "ACDual",
    "ac_wind_mode": "ACWindMode",
    "ac_ctrl_mode": "ACCtrlMode",
}


def load_reference(path):
    """Return list of (name, fid, dev)."""
    data = json.loads(Path(path).read_text())
    fids = data["defaults"]["fids"]
    devices = data["defaults"]["devices"]
    out = []
    for name, fid in fids.items():
        dev = devices.get(name)
        out.append((name, fid, dev))
    return out


def load_yaml_entries(path):
    """Return list of dicts with param, fid, device."""
    data = yaml.safe_load(Path(path).read_text())
    return [e for e in data if isinstance(e, dict) and e.get("fid") is not None]


def main():
    ref_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REF
    yaml_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_YAML

    ref = load_reference(ref_path)
    ours = load_yaml_entries(yaml_path)

    ours_by_fid_dev = {(e["fid"], e["device"]): e for e in ours}
    ours_by_fid = {}
    for e in ours:
        ours_by_fid.setdefault(e["fid"], []).append(e)
    ours_by_param = {e["param"]: e for e in ours}

    imported, dev_mismatch, dual_fid, not_in_yaml = [], [], [], []

    for name, fid, dev in ref:
        if (fid, dev) in ours_by_fid_dev:
            imported.append((name, fid, dev))
            continue
        if fid in ours_by_fid:
            their_devs = [e["device"] for e in ours_by_fid[fid]]
            dev_mismatch.append((name, fid, dev, their_devs))
            continue
        # Check name hint for dual-source.
        hint = NAME_HINTS.get(name)
        if hint and hint in ours_by_param:
            our = ours_by_param[hint]
            dual_fid.append((name, fid, dev, hint, our["fid"], our["device"]))
            continue
        not_in_yaml.append((name, fid, dev))

    # ONLY_IN_YAML — fids we have, no reference fid matches.
    ref_fids = {(fid, dev) for _, fid, dev in ref}
    only_in_yaml = [
        (e["param"], e["fid"], e["device"]) for e in ours
        if (e["fid"], e["device"]) not in ref_fids
        and e["fid"] not in {f for _, f, _ in ref}
    ]

    print(f"Reference: {ref_path}")
    print(f"Our yaml: {yaml_path}")
    print(f"Reference fids: {len(ref)} | Our yaml fids: {len(ours)}")
    print()

    def section(title, rows, fmt):
        print(f"=== {title} ({len(rows)}) ===")
        for r in rows:
            print(fmt.format(*r))
        print()

    section("IMPORTED (fid+dev match)", imported,
            "  {0:35s} fid={1:>12d} dev={2}")

    section("DEV_MISMATCH (same fid, different dev)", dev_mismatch,
            "  {0:35s} fid={1:>12d} ref_dev={2} our_dev(s)={3}")

    section("DUAL_FID (same parameter, different fid)", dual_fid,
            "  ref:{0:30s} fid={1:>12d} dev={2:<5} our:{3:15s} fid={4:>12d} dev={5}")

    section("NOT_IN_YAML (reference has it, we don't)", not_in_yaml,
            "  {0:35s} fid={1:>12d} dev={2}")

    section("ONLY_IN_YAML (our own findings)", only_in_yaml,
            "  {0:35s} fid={1:>12d} dev={2}")


if __name__ == "__main__":
    main()
