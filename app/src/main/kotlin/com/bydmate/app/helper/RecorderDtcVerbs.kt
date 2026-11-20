package com.bydmate.app.helper

import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.helper.push.FidRecorder
import com.bydmate.app.helper.dtc.DtcDaemon
import com.bydmate.app.helper.dtc.DtcScanner
import com.bydmate.app.helper.dtc.TpmsPlanEntry
import com.bydmate.app.helper.dtc.TpmsWire
import com.bydmate.app.helper.dtc.writeDtcScanStatus
import com.bydmate.app.helper.dtc.writeFirmwareStatus
import com.bydmate.app.helper.dtc.writeTpmsStatus
import com.bydmate.app.helper.offreport.OffReport
import com.bydmate.app.helper.offreport.readOffReportArm
import com.bydmate.app.helper.offreport.writeOffReportStatus
import com.bydmate.app.helper.push.readRecStartRequest
import com.bydmate.app.helper.push.writeRecStatus
import android.os.IBinder
import android.os.Parcel

/**
 * The three diagnostic-recorder verbs, out of line so onTransact keeps its own branch count:
 * start over the requested devices (empty = all), stop, and read the live counters back.
 * Every reply starts with a status int, so a failure is never read as an empty recording.
 */
internal fun handleRecorderTransact(code: Int, data: Parcel, reply: Parcel?): Boolean {
    when (code) {
        HelperBinderProtocol.TX_REC_START -> runCatching {
            val devices = readRecStartRequest(data)
            val result = FidRecorder.start(devices)
            reply?.writeInt(0)
            reply?.writeInt(result.devices)
            reply?.writeInt(result.registered)
            reply?.writeString(result.error)
        }.onFailure { e ->
            android.util.Log.w("FidRec", "TX_REC_START failed", e)
            reply?.writeInt(-1); reply?.writeInt(0); reply?.writeInt(0); reply?.writeString("")
        }

        HelperBinderProtocol.TX_REC_STOP -> runCatching {
            val stopped = FidRecorder.stop()
            reply?.writeInt(0)
            reply?.writeInt(stopped)
        }.onFailure { e ->
            android.util.Log.w("FidRec", "TX_REC_STOP failed", e)
            reply?.writeInt(-1); reply?.writeInt(0)
        }

        else -> runCatching {
            val status = FidRecorder.status()
            reply?.writeInt(0)
            reply?.let { writeRecStatus(it, status) }
        }.onFailure { e ->
            android.util.Log.w("FidRec", "TX_REC_STATUS failed", e)
            reply?.writeInt(-1)
        }
    }
    return true
}

/**
 * The three power-off report verbs, out of line like the recorder's. Every reply starts with a
 * status int; a failure here costs this call only, never the daemon.
 */
internal fun handleOffReportTransact(code: Int, data: Parcel, reply: Parcel?): Boolean {
    runCatching {
        when (code) {
            HelperBinderProtocol.TX_OFFREPORT_ARM -> {
                val request = readOffReportArm(data)
                if (request != null) OffReport.arm(request)
                reply?.writeInt(if (request != null) 0 else -1)
            }
            HelperBinderProtocol.TX_OFFREPORT_DISARM -> {
                // Non-zero while a pending file survived the delete: the app keeps asking.
                reply?.writeInt(if (OffReport.disarm()) 0 else 1)
            }
            else -> {
                val status = OffReport.status(data.readString().orEmpty())
                reply?.writeInt(0)
                reply?.let { writeOffReportStatus(it, status) }
            }
        }
    }.onFailure { e ->
        // The class name only: a message could carry what the request held.
        android.util.Log.w("OffReport", "offreport: tx=$code failed: ${e.javaClass.simpleName}")
        reply?.setDataSize(0)
        reply?.writeInt(-1)
    }
    return true
}

/**
 * The manual fault-code scan and erase verbs, the tyre-sensor read and write, and the block
 * firmware check. START only
 * hands the job to its own thread and answers; the gear (getInt, tx 5) and, for the erase and the
 * tyre write, the speed (getFloat, tx 7) are read live at the addresses the app resolved, before
 * every request. A failure here costs this call only.
 */
internal fun handleDtcScanTransact(code: Int, data: Parcel, reply: Parcel?, svc: IBinder, autoIface: String): Boolean {
    runCatching {
        when (code) {
            HelperBinderProtocol.TX_DTC_SCAN_START -> {
                val gearDev = data.readInt()
                val gearFid = data.readInt()
                val status = DtcDaemon.scanner.start { readDtcGear(svc, autoIface, gearDev, gearFid) }
                android.util.Log.i("DtcScan", "start request gear=$gearDev/$gearFid reply=$status")
                reply?.writeInt(status)
            }
            HelperBinderProtocol.TX_DTC_ERASE_START -> {
                val gearDev = data.readInt()
                val gearFid = data.readInt()
                val speedDev = data.readInt()
                val speedFid = data.readInt()
                val addrs = data.createIntArray() ?: IntArray(0)
                DtcDaemon.speed = { readDtcSpeed(svc, autoIface, speedDev, speedFid) }
                val status = DtcDaemon.scanner.startErase(addrs) { readDtcGear(svc, autoIface, gearDev, gearFid) }
                android.util.Log.i(
                    "DtcScan",
                    "erase request gear=$gearDev/$gearFid speed=$speedDev/$speedFid " +
                        "addrs=${addrs.joinToString(",") { "0x%X".format(it) }} reply=$status",
                )
                reply?.writeInt(status)
            }
            HelperBinderProtocol.TX_TPMS_READ_START -> {
                val gearDev = data.readInt()
                val gearFid = data.readInt()
                val status = DtcDaemon.scanner.startTpmsRead { readDtcGear(svc, autoIface, gearDev, gearFid) }
                android.util.Log.i("DtcScan", "tpms read request gear=$gearDev/$gearFid reply=$status")
                reply?.writeInt(status)
            }
            HelperBinderProtocol.TX_TPMS_WRITE_START -> reply?.writeInt(handleTpmsWrite(data, svc, autoIface))
            HelperBinderProtocol.TX_FW_READ_START -> {
                val gearDev = data.readInt()
                val gearFid = data.readInt()
                val status = DtcDaemon.scanner.startFirmware { readDtcGear(svc, autoIface, gearDev, gearFid) }
                android.util.Log.i("DtcScan", "firmware request gear=$gearDev/$gearFid reply=$status")
                reply?.writeInt(status)
            }
            HelperBinderProtocol.TX_DIAG_JOB_STATUS -> reply?.let {
                writeTpmsStatus(it, DtcDaemon.scanner.tpmsStatus())
                writeFirmwareStatus(it, DtcDaemon.scanner.firmwareStatus())
            }
            else -> reply?.let { writeDtcScanStatus(it, DtcDaemon.scanner.status()) }
        }
    }.onFailure { e ->
        android.util.Log.w("DtcScan", "tx=$code failed: ${e.javaClass.simpleName}")
        reply?.setDataSize(0)
        reply?.writeInt(-1)
    }
    return true
}

/** TX_TPMS_WRITE_START: the plan as sent (n outside 0..4 is refused unread); the start's reply. */
private fun handleTpmsWrite(data: Parcel, svc: IBinder, autoIface: String): Int {
    val gearDev = data.readInt()
    val gearFid = data.readInt()
    val speedDev = data.readInt()
    val speedFid = data.readInt()
    val n = data.readInt()
    val plan = if (n in 0..TpmsWire.DIDS.size) List(n) { TpmsPlanEntry(data.readInt(), data.readInt(), data.readInt()) } else null
    DtcDaemon.speed = { readDtcSpeed(svc, autoIface, speedDev, speedFid) }
    val status = plan?.let { DtcDaemon.scanner.startTpmsWrite(it) { readDtcGear(svc, autoIface, gearDev, gearFid) } }
        ?: DtcScanner.START_REFUSED
    android.util.Log.i(
        "DtcScan",
        "tpms write request gear=$gearDev/$gearFid speed=$speedDev/$speedFid n=$n " +
            "plan=${plan?.joinToString(",") { "0x%X:%08X>%08X".format(it.did, it.expectedOld, it.newId) }} reply=$status",
    )
    return status
}

private fun readDtcGear(svc: IBinder, autoIface: String, dev: Int, fid: Int): Int? =
    autoserviceTransact(svc, autoIface, 5, dev, fid, 0, writeValue = false)
        .takeIf { (status, _) -> status == 0 }?.second

/** Speed in whole km/h rounded away from 0 (any movement reads >= 1); null when unreadable or a sentinel. */
private fun readDtcSpeed(svc: IBinder, autoIface: String, dev: Int, fid: Int): Int? =
    autoserviceTransact(svc, autoIface, 7, dev, fid, 0, writeValue = false)
        .takeIf { (status, _) -> status == 0 }
        ?.let { (_, bits) -> SentinelDecoder.parseFloatFromShellInt(bits) }
        ?.let { kotlin.math.ceil(kotlin.math.abs(it)).toInt() }
