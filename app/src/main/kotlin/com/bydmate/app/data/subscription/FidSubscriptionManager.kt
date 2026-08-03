package com.bydmate.app.data.subscription

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.remote.DiParsData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observe-only fid subscriptions: registers listeners on the hidden BYDAuto*Device
 * classes and only counts what arrives. Polling is untouched and stays the source of
 * truth — nothing here feeds triggers or changes the poll rate (design phase A).
 * Runs in -test builds only.
 */
@Singleton
class FidSubscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private class DeviceSpec(val label: String, val className: String, val fids: IntArray)

    private val specs = listOf(
        DeviceSpec("light", "android.hardware.bydauto.light.BYDAutoLightDevice", intArrayOf(FID_BLINK)),
        DeviceSpec("gearbox", "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", intArrayOf(FID_GEAR)),
        DeviceSpec("adas", "android.hardware.bydauto.adas.BYDAutoADASDevice", intArrayOf(FID_BSD_LEFT, FID_BSD_RIGHT)),
    )

    private val channels = mapOf(
        FID_BLINK to SubscriptionChannelState("blink/$FID_BLINK", 1..6),
        FID_GEAR to SubscriptionChannelState("gear/$FID_GEAR", 1..6),
        FID_BSD_LEFT to SubscriptionChannelState("bsdL/$FID_BSD_LEFT", 0..2),
        FID_BSD_RIGHT to SubscriptionChannelState("bsdR/$FID_BSD_RIGHT", 0..2),
    )

    // device instance + listener proxy + resolved unregister method, per successfully subscribed spec
    private class Registration(val device: Any, val listener: Any, val unregister: Method)

    // onError tally per device, so the dump shows a broken channel even after logcat is cleared
    private class ErrorTally(var count: Int = 0, var last: String? = null)

    private val registrations = mutableListOf<Registration>()
    private val registerResults = linkedMapOf<String, String>()  // label -> "ok" | error text
    private val listenerErrors = linkedMapOf<String, ErrorTally>()  // label -> onError tally
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    internal var testBuild: Boolean = BuildConfig.VERSION_NAME.endsWith("-test")  // test seam

    @Synchronized
    fun start() {
        if (started) return
        if (!testBuild) return  // observe-mode phase: -test builds only, prod untouched
        started = true
        for (spec in specs) {
            try {
                subscribe(spec)
                registerResults[spec.label] = "ok"
                Log.i(TAG, "registered ${spec.label} fids=${spec.fids.joinToString()}")
            } catch (e: Throwable) {
                // InvocationTargetException hides the real error in its cause — report the root
                val root = generateSequence(e) { it.cause }.last()
                registerResults[spec.label] = "${root.javaClass.simpleName}: ${root.message}"
                Log.w(TAG, "register ${spec.label} failed", e)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        for (reg in registrations) {
            runCatching { reg.unregister.invoke(reg.device, reg.listener) }
                .onFailure { Log.w(TAG, "unregister failed: $it") }
        }
        registrations.clear()
    }

    /** Called from TrackingService on every poll tick; poll stays the source of truth. */
    fun onPollSnapshot(data: DiParsData) {
        if (!started) return
        channels[FID_BLINK]?.onPollComparison(data.turnSignal)
        channels[FID_GEAR]?.onPollComparison(data.gear)
        // BSD fids are not in FidMap — no poll counterpart in this phase, events only.
    }

    fun diagnosticsSnapshot(): List<String> {
        if (!testBuild) return listOf("disabled (not a -test build)")
        if (!started) return listOf("not started")
        val now = System.currentTimeMillis()
        val errors = synchronized(listenerErrors) {
            listenerErrors.mapValues { (_, tally) -> "errors=${tally.count} last=${tally.last}" }
        }
        return registerResults.map { (label, res) ->
            "device $label: $res" + (errors[label]?.let { " $it" } ?: "")
        } + channels.values.map { it.diagnosticLine(now) }
    }

    /** Called from the binder thread when the vendor listener reports a channel error. */
    private fun onListenerError(label: String, message: String?) {
        Log.w(TAG, "listener $label onError: $message")
        synchronized(listenerErrors) {
            val tally = listenerErrors.getOrPut(label) { ErrorTally() }
            tally.count++
            tally.last = message ?: "?"
        }
    }

    private fun subscribe(spec: DeviceSpec) {
        val deviceType = Class.forName(spec.className)
        val device = deviceType.getMethod("getInstance", Context::class.java).invoke(null, context)
            ?: error("getInstance returned null")
        val listenerType = Class.forName("android.hardware.IBYDAutoListener")
        val eventType = Class.forName("android.hardware.IBYDAutoEvent")
        val getEventId = eventType.getMethod("getEventType")
        val getValue = eventType.getMethod("getValue")
        // The framework logs listeners by toString(), so the proxy must answer Object methods.
        val listener = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            // Binder thread: an escaping throwable lands inside vendor framework code and
            // can take the process down, so every branch stays inside this try.
            try {
                when {
                    method.declaringClass == Any::class.java -> when (method.name) {
                        "toString" -> "BydMateFidListener(${spec.label})"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                    method.name == "onDataChanged" -> {
                        val event = args?.getOrNull(0)
                        val fid = (getEventId.invoke(event) as Number).toInt()
                        val value = (getValue.invoke(event) as Number).toInt()
                        handler.post { onEvent(spec.label, fid, value) }
                        null
                    }
                    method.name == "onError" -> {
                        onListenerError(spec.label, args?.joinToString())
                        null
                    }
                    else -> null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "listener ${spec.label} ${method.name} failed: $e")
                null
            }
        }
        val register = findListenerMethod(device, listener, "registerListener", withFeatureIds = true)
        val unregister = findListenerMethod(device, listener, "unregisterListener", withFeatureIds = false)
        register.invoke(device, listener, spec.fids)
        registrations += Registration(device, listener, unregister)
    }

    private fun findListenerMethod(target: Any, listener: Any, name: String, withFeatureIds: Boolean): Method {
        for (method in target.javaClass.methods) {
            val p = method.parameterTypes
            if (method.name == name && p.size == (if (withFeatureIds) 2 else 1) &&
                p[0].isAssignableFrom(listener.javaClass) &&
                (!withFeatureIds || p[1] == IntArray::class.java)
            ) return method
        }
        throw NoSuchMethodException(name)
    }

    private fun onEvent(label: String, fid: Int, value: Int) {
        val ch = channels[fid]
        if (ch == null) {
            Log.w(TAG, "event for unknown fid=$fid from $label")
            return
        }
        val valid = ch.onEvent(value, System.currentTimeMillis())
        Log.i(TAG, "event $label fid=$fid value=$value valid=$valid")
    }

    companion object {
        const val FID_BLINK = 950009900
        const val FID_GEAR = 555745336
        const val FID_BSD_LEFT = 1098907664
        const val FID_BSD_RIGHT = 1098907666
        private const val TAG = "FidSubscription"
    }
}
