package com.bydmate.app.helper.dtc

import android.hardware.bydauto.BYDAutoEventValue
import android.hardware.bydauto.mqtt.AbsBYDAutoMqttListener
import android.util.Log
import com.bydmate.app.helper.push.FID_PUSH_NOT_IN_FEATURE_MAP
import com.bydmate.app.helper.push.confirmRegistration
import com.bydmate.app.helper.push.deviceInstance
import com.bydmate.app.helper.push.findListenerMethod
import com.bydmate.app.split.readSystemProperty
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The daemon's [DtcScanner], built on first use: the vendor 1033 device, the monotonic clock and
 * the diagnostic manager's guard property.
 */
internal object DtcDaemon {
    private const val GUARD_PROPERTY = "sys.diagmgrsrv.task_bk"

    val scanner: DtcScanner by lazy {
        DtcScanner(DtcVendorDevice(), SystemDtcClock, { readSystemProperty(GUARD_PROPERTY) })
    }
}

internal object SystemDtcClock : DtcClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
    override fun sleep(ms: Long) = Thread.sleep(ms)
}

/**
 * BYDAutoMqttDevice (dev 1033), as the validated probes use it: requests go out through
 * `set(int[]{TX}, value.bufferDataValue)`, answers come back to a listener on the RX feature id.
 * The listener is a real AbsBYDAutoMqttListener subclass (the firmware refuses a Proxy), lives
 * for one scan and only queues the frames; the scan thread reads them.
 */
internal class DtcVendorDevice : DtcDevice {
    private val frames = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    @Volatile private var device: Any? = null
    @Volatile private var listener: Listener? = null

    private class Listener(private val onFrame: (ByteArray) -> Unit) : AbsBYDAutoMqttListener() {
        // Vendor binder thread, under the firmware's listener-map monitor: queue and return.
        override fun onDataEventChanged(eventType: Int, eventValue: BYDAutoEventValue?) {
            if (eventType != RX) return
            runCatching { eventValue?.bufferDataValue?.let(onFrame) }
        }

        override fun onError(errCode: Int, errMessage: String?) {
            Log.w(TAG, "listener error code=$errCode")
        }
    }

    override fun register(): String? {
        val dev = runCatching { deviceInstance(DEVICE_CLASS) }.getOrElse { e ->
            Log.w(TAG, "register failed ${describe(e)}")
            return reasonFor(e)
        }
        return runCatching {
            val l = Listener(::offer)
            device = dev
            listener = l
            findListenerMethod(dev, l, "registerListener", withFeatureIds = true).invoke(dev, l, intArrayOf(RX))
            val confirmed = confirmRegistration(dev, RX)
            Log.i(TAG, "register rx=0x%08X map=%s".format(RX, confirmed))
            if (confirmed == FID_PUSH_NOT_IN_FEATURE_MAP) "register_failed not_in_feature_map" else null
        }.getOrElse { e ->
            Log.w(TAG, "register failed ${describe(e)}")
            reasonFor(e)
        }
    }

    override fun unregister() {
        val dev = device
        val l = listener
        device = null
        listener = null
        frames.clear()
        if (dev == null || l == null) return
        findListenerMethod(dev, l, "unregisterListener", withFeatureIds = false).invoke(dev, l)
    }

    override fun send(payload: ByteArray): Int {
        val dev = device ?: return NOT_REGISTERED
        // The no-arg constructor of the firmware class sets the int/double sentinels, so set()
        // takes the buffer path; intValue is never touched.
        val value = BYDAutoEventValue()
        value.bufferDataValue = payload
        val set = dev.javaClass.getMethod("set", IntArray::class.java, BYDAutoEventValue::class.java)
        return set.invoke(dev, intArrayOf(TX), value) as? Int ?: NOT_REGISTERED
    }

    override fun poll(timeoutMs: Long): ByteArray? = frames.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun drain() = frames.clear()

    private fun offer(frame: ByteArray) {
        if (frame.size > MAX_FRAME_BYTES || !frames.offer(frame.copyOf())) {
            Log.w(TAG, "frame dropped size=${frame.size} queued=${frames.size}")
        }
    }

    private fun reasonFor(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return if (root is ClassNotFoundException || root is NoClassDefFoundError) {
            "class_missing"
        } else {
            "register_failed ${root.javaClass.simpleName}"
        }
    }

    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message ?: "(no message)"}"
    }

    private companion object {
        const val TAG = "DtcScan"
        const val DEVICE_CLASS = "android.hardware.bydauto.mqtt.BYDAutoMqttDevice"
        const val RX = 0x9900010A.toInt()
        const val TX = 0xAA00010A.toInt()
        const val QUEUE_CAPACITY = 128
        const val MAX_FRAME_BYTES = 1024
        const val NOT_REGISTERED = -1
    }
}
