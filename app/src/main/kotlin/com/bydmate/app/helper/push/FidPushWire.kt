package com.bydmate.app.helper.push

import android.os.Parcel

/**
 * Wire types and encoders of the fid push channel, shared by the daemon (which registers the
 * vendor listeners) and the app (which receives the events). Kept out of both so the marshalling
 * can be round-tripped in a test without either side.
 *
 * Every fid travels with the device it belongs to: the app resolves both through
 * [com.bydmate.app.data.nativestack.FidAddresses], and the daemon has no resolved catalog of its
 * own — deriving the device from the fid on the daemon side would break on any car whose catalog
 * moved the address.
 */

/** One requested subscription: the resolved fid plus the autoservice device it lives on. */
data class FidPushSub(val fid: Int, val device: Int)

/** Registration outcome of one fid, as the daemon reports it back. */
data class FidPushResult(val fid: Int, val device: Int, val outcome: String)

/** One fid's live counters, read back over TX_PUSH_STATUS. */
data class FidPushStatusRow(
    val fid: Int,
    val device: Int,
    val outcome: String,
    val events: Int,
    val lastIntValue: Int,
    val lastDoubleValue: Double,
    val lastTsElapsed: Long,
)

/**
 * Full TX_PUSH_STATUS reply: the per-fid rows, the callback's own health and the delivery
 * totals. [events] counts what the vendor listeners produced, [packets] the batched transacts
 * that carried them and [coalesced] the events a later value of the same fid replaced inside a
 * flush window — together they say how much traffic the coalescing actually absorbed.
 */
data class FidPushStatus(
    val rows: List<FidPushStatusRow>,
    val callbackAlive: Boolean,
    val deliverErrors: Int,
    val packets: Int = 0,
    val events: Int = 0,
    val coalesced: Int = 0,
)

/** One delivered event. [receivedAtMs] is filled in by the app, never by the wire. */
data class FidPushEvent(
    val fid: Int,
    val intValue: Int,
    val doubleValue: Double,
    val tsElapsed: Long,
    val receivedAtMs: Long = 0L,
)

/** Fids grouped for registration; [unsupported] holds those on a device with no listener class. */
data class FidPushGrouping(
    val byDevice: Map<Int, List<Int>>,
    val unsupported: List<Int>,
)

/** Registration succeeded for this fid: the device's own listener map confirms it. */
const val FID_PUSH_OK = "OK"

/**
 * registerListener() returned without throwing, but the readback of the device's listener map was
 * unavailable — the first event for the fid is then the confirmation.
 */
const val FID_PUSH_SENT = "sent"

/** registerListener() returned silently because checkDeviceFeatures refused the fid. */
const val FID_PUSH_NOT_IN_FEATURE_MAP = "not in feature map"

/** The fid's device has no BYDAuto listener class we can subclass. */
const val FID_PUSH_UNSUPPORTED = "unsupported"

/**
 * Hard cap on fids per subscribe call — the wave subscribes to every FidMap field (111), the
 * cap only guards the parcel. It bounds the event list of one flush too: a flush carries at
 * most one event per subscribed fid.
 */
const val MAX_PUSH_FIDS = 160

/**
 * Every autoservice device with a numeric mDeviceType in the firmware, mapped to the class the
 * daemon instantiates by reflection. Generated from the decompiled firmware by
 * scripts/gen-bydauto-listener-stubs.py, which prints this map.
 *
 * A device here still needs a listener class to be registrable: the push channel resolves it with
 * [pushListenerFor] and the diagnostic recorder with `recorderListenerFor`; both cover every
 * device that has a listener class. A fid whose device is missing here is reported as
 * [FID_PUSH_UNSUPPORTED] rather than silently dropped. Multimedia has no device type at all and is therefore absent.
 */
val FID_PUSH_DEVICE_CLASSES: Map<Int, String> = mapOf(
    1000 to "android.hardware.bydauto.ac.BYDAutoAcDevice",
    1001 to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
    1002 to "android.hardware.bydauto.audio.BYDAutoAudioDevice",
    1004 to "android.hardware.bydauto.light.BYDAutoLightDevice",
    1005 to "android.hardware.bydauto.power.BYDAutoPowerDevice",
    1006 to "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
    1007 to "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
    1008 to "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device",
    1009 to "android.hardware.bydauto.charging.BYDAutoChargingDevice",
    1010 to "android.hardware.bydauto.security.BYDAutoSecurityDevice",
    1011 to "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
    1012 to "android.hardware.bydauto.engine.BYDAutoEngineDevice",
    1013 to "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
    1014 to "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
    1015 to "android.hardware.bydauto.collision.BYDAutoCollisionDevice",
    1016 to "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
    1017 to "android.hardware.bydauto.location.BYDAutoLocationDevice",
    1019 to "android.hardware.bydauto.auxiliary.BYDAutoAuxDevice",
    1020 to "android.hardware.bydauto.motor.BYDAutoMotorDevice",
    1021 to "android.hardware.bydauto.radio.BYDAutoRadioDevice",
    1022 to "android.hardware.bydauto.test.BYDAutoTestDevice",
    1023 to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
    1024 to "android.hardware.bydauto.time.BYDAutoTimeDevice",
    1025 to "android.hardware.bydauto.radar.BYDAutoRadarDevice",
    1026 to "android.hardware.bydauto.reminder.BYDAutoReminderDevice",
    1027 to "android.hardware.bydauto.version.BYDAutoVersionDevice",
    1028 to "android.hardware.bydauto.funcnotice.BYDAutoFuncNoticeDevice",
    1029 to "android.hardware.bydauto.phone.BYDAutoPhoneDevice",
    1030 to "android.hardware.bydauto.cputemprature.BYDAutoCpuTempratureDevice",
    1031 to "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
    1032 to "android.hardware.bydauto.ota.BYDAutoOtaDevice",
    1033 to "android.hardware.bydauto.mqtt.BYDAutoMqttDevice",
    1034 to "android.hardware.bydauto.yun.BYDAutoYunDevice",
    1036 to "android.hardware.bydauto.qcfs.BYDAutoQcfsDevice",
    1037 to "android.hardware.bydauto.signal.BYDAutoSignalDevice",
    1038 to "android.hardware.bydauto.adas.BYDAutoADASDevice",
    1039 to "android.hardware.bydauto.gb.BYDAutoGBDevice",
    1040 to "android.hardware.bydauto.rescue.BYDAutoRescueDevice",
    1041 to "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
    1042 to "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice",
    1043 to "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
    1045 to "android.hardware.bydauto.dtc.BYDAutoDtcDevice",
    1046 to "android.hardware.bydauto.wiper.BYDAutoWiperDevice",
    1047 to "android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice",
    1048 to "android.hardware.bydauto.vehicledata.BYDAutoVehicleDataDevice",
    1049 to "android.hardware.bydauto.special.BYDAutoSpecialDevice",
    1061 to "android.hardware.bydauto.bigdata.BYDAutoBigDataDevice",
    1062 to "android.hardware.bydauto.rse.BYDAutoRSEDevice",
)

/**
 * Groups the requested fids by device, preserving request order and dropping duplicates.
 * A fid on a device outside [FID_PUSH_DEVICE_CLASSES] lands in [FidPushGrouping.unsupported].
 */
fun groupFidsByDevice(subs: List<FidPushSub>): FidPushGrouping {
    val byDevice = linkedMapOf<Int, MutableList<Int>>()
    val unsupported = mutableListOf<Int>()
    val seen = mutableSetOf<Int>()
    for (sub in subs) {
        if (!seen.add(sub.fid)) continue
        if (sub.device in FID_PUSH_DEVICE_CLASSES) {
            byDevice.getOrPut(sub.device) { mutableListOf() } += sub.fid
        } else {
            unsupported += sub.fid
        }
    }
    return FidPushGrouping(byDevice, unsupported)
}

fun writeSubscribeRequest(p: Parcel, subs: List<FidPushSub>) {
    p.writeInt(subs.size)
    subs.forEach { p.writeInt(it.fid); p.writeInt(it.device) }
}

/** Empty list when the count is missing, negative or above [MAX_PUSH_FIDS]. */
fun readSubscribeRequest(p: Parcel): List<FidPushSub> {
    if (p.dataAvail() < INT_BYTES) return emptyList()
    val n = p.readInt()
    if (n !in 0..MAX_PUSH_FIDS || p.dataAvail() < n * 2 * INT_BYTES) return emptyList()
    return List(n) { FidPushSub(p.readInt(), p.readInt()) }
}

fun writeResultTable(p: Parcel, results: List<FidPushResult>) {
    p.writeInt(results.size)
    results.forEach { p.writeInt(it.fid); p.writeInt(it.device); p.writeString(it.outcome) }
}

fun readResultTable(p: Parcel): List<FidPushResult> {
    if (p.dataAvail() < INT_BYTES) return emptyList()
    val n = p.readInt()
    if (n !in 0..MAX_PUSH_FIDS) return emptyList()
    return List(n) { FidPushResult(p.readInt(), p.readInt(), p.readString().orEmpty()) }
}

fun writeStatusTable(p: Parcel, status: FidPushStatus) {
    p.writeInt(status.rows.size)
    status.rows.forEach { row ->
        p.writeInt(row.fid)
        p.writeInt(row.device)
        p.writeString(row.outcome)
        p.writeInt(row.events)
        p.writeInt(row.lastIntValue)
        p.writeDouble(row.lastDoubleValue)
        p.writeLong(row.lastTsElapsed)
    }
    p.writeInt(if (status.callbackAlive) 1 else 0)
    p.writeInt(status.deliverErrors)
    p.writeInt(status.packets)
    p.writeInt(status.events)
    p.writeInt(status.coalesced)
}

fun readStatusTable(p: Parcel): FidPushStatus {
    if (p.dataAvail() < INT_BYTES) return FidPushStatus(emptyList(), false, 0)
    val n = p.readInt()
    if (n !in 0..MAX_PUSH_FIDS) return FidPushStatus(emptyList(), false, 0)
    val rows = List(n) {
        FidPushStatusRow(
            fid = p.readInt(),
            device = p.readInt(),
            outcome = p.readString().orEmpty(),
            events = p.readInt(),
            lastIntValue = p.readInt(),
            lastDoubleValue = p.readDouble(),
            lastTsElapsed = p.readLong(),
        )
    }
    val alive = p.dataAvail() >= INT_BYTES && p.readInt() != 0
    val errors = if (p.dataAvail() >= INT_BYTES) p.readInt() else 0
    val packets = if (p.dataAvail() >= INT_BYTES) p.readInt() else 0
    val events = if (p.dataAvail() >= INT_BYTES) p.readInt() else 0
    val coalesced = if (p.dataAvail() >= INT_BYTES) p.readInt() else 0
    return FidPushStatus(rows, alive, errors, packets, events, coalesced)
}

/**
 * One flush of the daemon's coalescing buffer: a count followed by that many events. Batched
 * rather than one transact per event because the firmware pushes the busiest fids (hvCurrent,
 * motor rpm, speed) faster than twice a second each.
 */
fun writePushEvents(p: Parcel, events: List<FidPushEvent>) {
    p.writeInt(events.size)
    for (e in events) {
        p.writeInt(e.fid)
        p.writeInt(e.intValue)
        p.writeDouble(e.doubleValue)
        p.writeLong(e.tsElapsed)
    }
}

/**
 * Empty when the count is missing, negative, above [MAX_PUSH_FIDS] or not backed by that many
 * events. [receivedAtMs] is the app's own clock.
 */
fun readPushEvents(p: Parcel, receivedAtMs: Long): List<FidPushEvent> {
    if (p.dataAvail() < INT_BYTES) return emptyList()
    val n = p.readInt()
    if (n !in 0..MAX_PUSH_FIDS || p.dataAvail() < n * EVENT_BYTES) return emptyList()
    return List(n) {
        FidPushEvent(
            fid = p.readInt(),
            intValue = p.readInt(),
            doubleValue = p.readDouble(),
            tsElapsed = p.readLong(),
            receivedAtMs = receivedAtMs,
        )
    }
}

private const val INT_BYTES = 4

/** int + int + double + long on the wire. */
private const val EVENT_BYTES = 24
