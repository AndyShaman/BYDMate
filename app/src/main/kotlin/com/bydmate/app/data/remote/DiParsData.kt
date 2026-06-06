package com.bydmate.app.data.remote

data class DiParsData(
    val soc: Int?,
    val speed: Int?,
    val mileage: Double?,
    val power: Double?,
    val chargeGunState: Int?,
    val maxBatTemp: Int?,
    val avgBatTemp: Int?,
    val minBatTemp: Int?,
    val chargingStatus: Int?,
    val batteryCapacityKwh: Double?,
    val totalElecConsumption: Double?,
    val voltage12v: Double?,
    val maxCellVoltage: Double?,
    val minCellVoltage: Double?,
    val exteriorTemp: Int?,
    // Automation params (v2.2.0)
    val gear: Int?,               // 1=P, 2=R, 3=N, 4=D
    val powerState: Int?,         // 0=OFF, 1=ON, 2=DRIVE
    val insideTemp: Int?,
    val acStatus: Int?,           // 0=OFF, 1=ON
    val acTemp: Int?,
    val fanLevel: Int?,
    val acCirc: Int?,             // 0=external, 1=internal
    val doorFL: Int?,             // 0=closed, 1=open
    val doorFR: Int?,
    val doorRL: Int?,
    val doorRR: Int?,
    val windowFL: Int?,           // 0-100%
    val windowFR: Int?,
    val windowRL: Int?,
    val windowRR: Int?,
    val sunroof: Int?,            // 0-100%
    val trunk: Int?,              // 0=closed, 1=open
    val hood: Int?,               // 0=closed, 1=open
    val seatbeltFL: Int?,         // 0=unbuckled, 1=buckled, 2=invalid
    val lockFL: Int?,             // 1=unlocked, 2=locked
    val tirePressFL: Int?,        // kPa
    val tirePressFR: Int?,
    val tirePressRL: Int?,
    val tirePressRR: Int?,
    val driveMode: Int?,          // 1=ECO, 2=SPORT
    val workMode: Int?,           // 0=stop, 1=EV, 2=forced EV, 3=HEV
    val autoPark: Int?,           // 0=disabled, 1=standby, 2=active
    val rain: Int?,
    val lightLow: Int?,           // 0=OFF, 1=ON
    val drl: Int?                 // 0=invalid, 1=ON, 2=OFF
)
