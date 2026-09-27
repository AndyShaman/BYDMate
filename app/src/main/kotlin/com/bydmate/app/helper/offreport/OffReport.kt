package com.bydmate.app.helper.offreport

import android.os.SystemClock
import android.util.Log
import com.bydmate.app.data.telegram.TelegramReportBuilder
import com.bydmate.app.helper.push.FID_PUSH_DEVICE_CLASSES
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FID_PUSH_SENT
import com.bydmate.app.helper.push.FidPushSink
import com.bydmate.app.helper.push.confirmRegistration
import com.bydmate.app.helper.push.deviceInstance
import com.bydmate.app.helper.push.findListenerMethod
import com.bydmate.app.helper.push.pushListenerFor
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Daemon side of the power-off Telegram report (3.19, phase B). The daemon alone delivers it: the
 * app is killed within about a second of the car being switched off (quickboot force-stops it), the
 * daemon is not. The app keeps a ready report here ([arm]); when the daemon's own listener on the
 * power fids sees the power-off ([PowerOffAutomaton]), the report gets its time, is written to disk
 * ([PendingReports]) and sent within the 8 s before the networks go ([OffReportRetry]). Sent or
 * refused for good, the file goes; otherwise it waits, and every later [arm] (the app's next start
 * on the next drive, with a token again) delivers what waits, oldest first, with the
 * «(записано в HH:MM)» line, backing off after a failed pass. [disarm] drops the pending files too.
 *
 * Every send runs on ONE sender thread ([send]), so the same report is never in flight twice.
 * [arm] only stores the report and returns: the listener registration, its retries and the priming
 * reads run on the daemon's worker thread. A power fid whose registration failed is retried every
 * [REGISTER_RETRY_MS] for the daemon's life.
 *
 * Separate from the push registry and the recorder: their listeners come and go with the app and
 * the recorder switch, this one stays registered for the daemon's lifetime once the first report
 * is armed. Locking rule is theirs: [lock] guards our own fields only, no vendor call runs while it
 * is held (the firmware dispatches events under its listener-map monitor and calls [onPower]).
 *
 * Accepted: one report per arm, so a second power-off within seconds of a power-on, before the app
 * re-armed, finds nothing armed and is not reported; a Telegram answer lost with the network can
 * make one report arrive twice (see [OffReportRetry]).
 *
 * Never logged: the token, the chat id and the report text (users post their logs in issues).
 */
@Suppress("TooManyFunctions") // arm, status, the listener, the sender and the pending delivery, kept in one place
internal object OffReport {

    private const val TAG = "OffReport"

    /** `BODYWORK_POWER_LEVEL` and `SET_VEHICLE_STATE` (powerState): (device, fid). */
    val POWER_FIDS = listOf(1001 to 315621418, 1023 to 315621408)

    /** Outcomes kept per id, for the dump. */
    private const val MAX_OUTCOMES = 8

    const val REGISTER_RETRY_MS = 30_000L
    const val FID_PENDING = "pending"

    /** A pending delivery runs on a live network: one short attempt per report per pass. */
    const val PENDING_CONNECT_MS = 3_000
    const val PENDING_READ_MS = 5_000

    /** After a pass that met no network, arms do not start another one for this long (doubling). */
    const val PENDING_BACKOFF_FIRST_MS = 30_000L
    const val PENDING_BACKOFF_MAX_MS = 15 * 60_000L

    private const val PENDING_DIR = "/data/local/tmp/bydmate_offreport"
    private const val TELEGRAM_HOST = "api.telegram.org"

    private class Armed(val request: ArmRequest, val armedAtMs: Long)

    /** Internal rather than private so a test can assert it is free during a vendor call. */
    internal val lock = Any()
    private var automaton = PowerOffAutomaton()
    private var armed: Armed? = null
    private val outcomes = LinkedHashMap<String, OffReportOutcome>()
    private var last: OffReportOutcome? = null

    /** Per power fid: its registration state; empty until the first [arm]. Guarded by [lock]. */
    private val registrations = LinkedHashMap<Int, String>()
    private var retryScheduled = false

    /** Pending delivery gate, guarded by [lock]: one pass queued at a time, backoff on [monoClock]. */
    private var passQueued = false
    private var passNotBefore = 0L
    private var passBackoffMs = 0L

    /** Direct read of a power fid (autoservice getInt), installed by the daemon; null = unreadable. */
    @Volatile internal var reader: (dev: Int, fid: Int) -> Int? = { _, _ -> null }

    /** Test seams: the daemon uses the vendor listener, HTTPS, the real clocks, threads and disk. */
    internal var registrar: (dev: Int, fid: Int, sink: FidPushSink) -> String = ::registerPowerFid
    internal var sender: (token: String, chatId: Long, text: String, connectMs: Int, readMs: Int) -> AttemptResult =
        ::postSendMessage
    internal var wallClock: () -> Long = System::currentTimeMillis
    internal var monoClock: () -> Long = SystemClock::elapsedRealtime
    internal var sleep: (Long) -> Unit = Thread::sleep
    internal var pending = PendingReports(File(PENDING_DIR))

    /** THE sender: every send and every pending file change runs here, one after the other. */
    internal var send: (Runnable) -> Unit = { job -> sendThread.execute(job) }

    /** The worker: registration, retries and priming reads. [delayMs] 0 = now. */
    internal var schedule: (delayMs: Long, task: Runnable) -> Unit = { delayMs, task ->
        worker.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }

    private val sendThread by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "offreport-send").apply { isDaemon = true } }
    }

    private val worker: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "offreport-worker").apply { isDaemon = true } }
    }

    private val sink = FidPushSink { fid, intValue, _, ts -> onPower(fid, intValue, ts) }

    /**
     * Holds [request] as THE report to send at the next power-off, replacing the previous one, and
     * lets the reports still pending on disk go out with its bot.
     */
    fun arm(request: ArmRequest) {
        val first = synchronized(lock) {
            armed = Armed(request, wallClock())
            val fresh = registrations.isEmpty()
            if (fresh) POWER_FIDS.forEach { (_, fid) -> registrations[fid] = FID_PENDING }
            fresh
        }
        Log.i(TAG, "offreport: armed id=${request.id} len=${request.text.length}")
        schedule(0L, Runnable { guardedWork { if (first) registerPending() else prime() } })
        requestPendingPass()
    }

    /** The report was switched off or the bot disconnected: nothing armed, nothing pending. */
    fun disarm() {
        val had = synchronized(lock) {
            passNotBefore = 0L
            passBackoffMs = 0L
            armed?.request?.id.also { armed = null }
        }
        Log.i(TAG, "offreport: disarmed id=${had ?: "-"}")
        send(Runnable {
            guardedWork {
                pending.deleteAll().forEach { Log.i(TAG, "offreport: pending dropped id=$it reason=disarm") }
            }
        })
    }

    /** Where [id] stands, plus what is armed now, the last outcome and the pending count, for the dump. */
    fun status(id: String): OffReportStatus {
        val waiting = runCatching { pending.count() }.getOrDefault(0)
        return synchronized(lock) {
            val now = armed
            val queried = outcomes[id]
                ?: if (now != null && now.request.id == id) OffReportOutcome(id, OffReportState.ARMED)
                else OffReportOutcome(id, OffReportState.UNKNOWN)
            val fids = POWER_FIDS.mapNotNull { (dev, fid) -> registrations[fid]?.let { OffReportFid(dev, fid, it) } }
            val listening = if (registrations.isEmpty()) -1 else registrations.values.count(::isLive)
            OffReportStatus(queried, now?.request?.id.orEmpty(), now?.armedAtMs ?: 0L, listening, last, fids, waiting)
        }
    }

    /**
     * Vendor binder thread: a power fid moved. On the power-off the armed report is taken (one
     * report per arm: a quick off-on-off before the app re-arms must not send stale text twice) and
     * handed to the sender — this thread belongs to the firmware and must return at once.
     */
    fun onPower(fid: Int, value: Int, atElapsed: Long) {
        if (POWER_FIDS.none { it.second == fid }) return
        var wasPrimed = false
        var fired = false
        val taken = synchronized(lock) {
            wasPrimed = automaton.primed
            fired = automaton.onEvent(fid, value)
            if (!fired) return@synchronized null
            val report = armed ?: return@synchronized null
            armed = null
            val offAt = wallClock()
            remember(OffReportOutcome(report.request.id, OffReportState.SENDING, powerOffMs = offAt))
            report to offAt
        }
        when {
            fired -> Log.i(TAG, "offreport: power off fid=$fid value=$value armed=${taken?.first?.request?.id ?: "-"}")
            !wasPrimed && value > 0 -> Log.i(TAG, "offreport: power on fid=$fid value=$value")
        }
        if (taken == null) return
        val (report, offAt) = taken
        send(Runnable {
            runCatching { sendAtPowerOff(report.request, offAt, atElapsed) }.onFailure { t ->
                Log.w(TAG, "offreport: send crashed id=${report.request.id}: ${t.javaClass.simpleName}")
                synchronized(lock) {
                    remember(OffReportOutcome(report.request.id, OffReportState.FAILED, powerOffMs = offAt, rc = "crash"))
                }
            }
        })
    }

    /** Sender thread: to disk first, then the burst; the file goes once it was sent or refused. */
    private fun sendAtPowerOff(request: ArmRequest, offAt: Long, offElapsed: Long) {
        val text = TelegramReportBuilder.fillTime(request.text, offAt)
        if (pending.save(PendingReport(request.id, request.chatId, offAt, text))) {
            Log.i(TAG, "offreport: pending saved id=${request.id}")
        }
        val result = OffReportRetry.run(
            offAt = offElapsed,
            clock = monoClock,
            sleep = sleep,
            attempt = { connectMs, readMs -> sender(request.token, request.chatId, text, connectMs, readMs) },
            onAttempt = { n, rc, sinceOff -> Log.i(TAG, "offreport: attempt $n rc=$rc ms=$sinceOff") },
        )
        val outcome = OffReportOutcome(
            id = request.id,
            state = if (result.sent) OffReportState.SENT else OffReportState.FAILED,
            powerOffMs = offAt,
            sentAtMs = if (result.sent) wallClock() else 0L,
            attempts = result.attempts,
            rc = result.rc,
        )
        synchronized(lock) { remember(outcome) }
        when {
            result.sent -> pending.delete(request.id)
            result.refused -> {
                pending.delete(request.id)
                Log.i(TAG, "offreport: pending dropped id=${request.id} reason=${result.rc}")
            }
        }
        Log.i(TAG, "offreport: ${if (result.sent) "sent" else "failed"} id=${request.id} attempts=${result.attempts}")
    }

    /** Queues one pending pass on the sender, unless one is queued, nothing is armed or it backs off. */
    private fun requestPendingPass() {
        val go = synchronized(lock) {
            val ok = armed != null && !passQueued && monoClock() >= passNotBefore
            if (ok) passQueued = true
            ok
        }
        if (go) send(Runnable { guardedWork { deliverPending() } })
    }

    /**
     * Sender thread: the reports on disk, oldest first, with the bot and chat armed now. Stops at the
     * first failure that may heal (no network yet) and backs off; a refusal drops that report.
     */
    private fun deliverPending() {
        val target = synchronized(lock) {
            passQueued = false
            armed?.request
        } ?: return
        for (report in pending.list()) {
            // A disarm since the pass started: its own job drops the files, nothing more goes out.
            if (synchronized(lock) { armed == null }) return
            if (report.chatId != target.chatId) {
                pending.delete(report.id)
                Log.i(TAG, "offreport: pending dropped id=${report.id} reason=chat_changed")
                continue
            }
            val text = withLateMark(report, target.lateMark)
            val result = try {
                sender(target.token, target.chatId, text, PENDING_CONNECT_MS, PENDING_READ_MS)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                AttemptResult("err:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
            }
            val ageS = (wallClock() - report.powerOffMs) / 1000
            when (result.verdict) {
                AttemptResult.Verdict.SENT -> {
                    pending.delete(report.id)
                    rememberPending(report, OffReportState.SENT, result.rc)
                    Log.i(TAG, "offreport: pending sent id=${report.id} age_s=$ageS")
                }
                AttemptResult.Verdict.STOP -> {
                    pending.delete(report.id)
                    rememberPending(report, OffReportState.FAILED, result.rc)
                    Log.i(TAG, "offreport: pending dropped id=${report.id} reason=${result.rc}")
                }
                AttemptResult.Verdict.RETRY -> {
                    val backoff = synchronized(lock) {
                        passBackoffMs = if (passBackoffMs == 0L) PENDING_BACKOFF_FIRST_MS
                        else minOf(passBackoffMs * 2, PENDING_BACKOFF_MAX_MS)
                        passNotBefore = monoClock() + passBackoffMs
                        passBackoffMs
                    }
                    Log.i(TAG, "offreport: pending keep id=${report.id} rc=${result.rc} age_s=$ageS retry_in_s=${backoff / 1000}")
                    return
                }
            }
        }
        synchronized(lock) {
            passBackoffMs = 0L
            passNotBefore = 0L
        }
    }

    private fun rememberPending(report: PendingReport, state: Int, rc: String) {
        val sentAt = if (state == OffReportState.SENT) wallClock() else 0L
        synchronized(lock) { remember(OffReportOutcome(report.id, state, report.powerOffMs, sentAt, 1, rc)) }
    }

    /** «(записано в 18:42)» from the app's template, with the date when the power-off was another day. */
    private fun withLateMark(report: PendingReport, template: String): String {
        if (template.isBlank()) return report.text
        val now = wallClock()
        val stamp = if (sameDay(report.powerOffMs, now)) TelegramReportBuilder.formatTime(report.powerOffMs)
        else TelegramReportBuilder.formatDateTime(report.powerOffMs)
        return report.text + "\n" + template.replace(TelegramReportBuilder.TIME_PLACEHOLDER, stamp)
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** Caller holds [lock]. */
    private fun remember(outcome: OffReportOutcome) {
        outcomes.remove(outcome.id)
        outcomes[outcome.id] = outcome
        while (outcomes.size > MAX_OUTCOMES) outcomes.remove(outcomes.keys.first())
        last = outcome
    }

    /**
     * Worker: registers every power fid that is not live yet (vendor calls, [lock] free), primes the
     * automaton, and comes back in [REGISTER_RETRY_MS] while any fid is still missing.
     */
    private fun registerPending() {
        val missing = synchronized(lock) { registrations.filterValues { !isLive(it) }.keys.toList() }
        for ((dev, fid) in POWER_FIDS.filter { it.second in missing }) {
            val outcome = runCatching { registrar(dev, fid, sink) }.getOrElse { describe(it) }
            val before = synchronized(lock) { registrations.put(fid, outcome) }
            // One line per change: a fid this firmware lacks is retried for the daemon's life.
            if (before != outcome) Log.i(TAG, "offreport: listen dev=$dev fid=$fid -> $outcome")
        }
        prime()
        val again = synchronized(lock) {
            val need = registrations.values.any { !isLive(it) } && !retryScheduled
            if (need) retryScheduled = true
            need
        }
        if (again) {
            schedule(REGISTER_RETRY_MS, Runnable {
                synchronized(lock) { retryScheduled = false }
                guardedWork { registerPending() }
            })
        }
    }

    /**
     * Worker: the listener only reports changes, so a daemon started on a running car learns it is
     * on from a direct read. A read of 0 never unprimes (see [PowerOffAutomaton.seed]), and reads
     * taken before a pushed event are stale and dropped ([PowerOffAutomaton.generation]): a non-zero
     * read from before a handled power-off would otherwise re-prime it for a second report.
     */
    private fun prime() {
        val generation = synchronized(lock) { automaton.generation }
        val seeds = POWER_FIDS.map { (dev, fid) -> fid to runCatching { reader(dev, fid) }.getOrNull() }
        val (before, primed, applied) = synchronized(lock) {
            val was = automaton.primed
            val fresh = automaton.generation == generation
            if (fresh) seeds.forEach { (fid, value) -> if (value != null) automaton.seed(fid, value) }
            Triple(was, automaton.primed, fresh)
        }
        // Every arm primes (the text changes each tick while driving): one line per change only.
        if (before != primed || lastPrimeLogged == null || !applied) {
            lastPrimeLogged = primed
            val seedLine = seeds.joinToString(",") { "${it.first}:${it.second ?: "?"}" }
            Log.i(TAG, "offreport: primed=$primed seed=$seedLine${if (applied) "" else " stale=dropped"}")
        }
    }

    /** Worker thread only. */
    private var lastPrimeLogged: Boolean? = null

    private fun guardedWork(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "offreport: worker failed: ${it.javaClass.simpleName}") }
    }

    private fun isLive(outcome: String): Boolean = outcome == FID_PUSH_OK || outcome == FID_PUSH_SENT

    /** Test seam: a fresh daemon. */
    internal fun resetForTest() {
        synchronized(lock) {
            automaton = PowerOffAutomaton()
            armed = null
            outcomes.clear()
            last = null
            registrations.clear()
            retryScheduled = false
            passQueued = false
            passNotBefore = 0L
            passBackoffMs = 0L
        }
        devices.clear()
        lastPrimeLogged = null
    }

    /** Device, listener and register method per device, kept once built; worker thread only. */
    private class DeviceHandle(val device: Any, val listener: Any, val register: Method)
    private val devices = HashMap<Int, DeviceHandle>()

    /** One fid on the device's own listener, confirmed through the listener map like the registry does. */
    private fun registerPowerFid(dev: Int, fid: Int, sink: FidPushSink): String {
        val handle = devices[dev] ?: run {
            val className = FID_PUSH_DEVICE_CLASSES[dev] ?: error("no device class")
            val listener = pushListenerFor(dev, sink) ?: error("no listener class")
            val device = deviceInstance(className)
            DeviceHandle(device, listener, findListenerMethod(device, listener, "registerListener", withFeatureIds = true))
                .also { devices[dev] = it }
        }
        handle.register.invoke(handle.device, handle.listener, intArrayOf(fid))
        return confirmRegistration(handle.device, fid)
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message ?: "(no message)"}"
    }

    /**
     * One Bot API `sendMessage` over plain HttpsURLConnection (no OkHttp in the daemon), Telegram
     * HTML like the app's backup sink. The host is resolved first under its own bound
     * ([OffReportRetry.callWithin]); connect and read have their own timeouts. rc is the HTTP code
     * or `io:<exception>`; never the URL, which carries the token.
     */
    private fun postSendMessage(token: String, chatId: Long, text: String, connectMs: Int, readMs: Int): AttemptResult {
        val resolved = try {
            OffReportRetry.callWithin(connectMs.toLong()) { InetAddress.getAllByName(TELEGRAM_HOST) }
        } catch (e: IOException) {
            return AttemptResult("io:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            return AttemptResult("err:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        }
        if (resolved == null) return AttemptResult("io:dns_timeout", AttemptResult.Verdict.RETRY)
        val body = ("chat_id=$chatId&parse_mode=HTML&text=" + URLEncoder.encode(text, "UTF-8"))
            .toByteArray(Charsets.UTF_8)
        var conn: HttpsURLConnection? = null
        return try {
            conn = URL("https://$TELEGRAM_HOST/bot$token/sendMessage").openConnection() as HttpsURLConnection
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            runCatching { (if (code < HTTP_ERROR) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
            AttemptResult(code.toString(), OffReportRetry.verdictFor(code))
        } catch (e: IOException) {
            AttemptResult("io:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            AttemptResult("err:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        } finally {
            conn?.disconnect()
        }
    }

    private const val HTTP_ERROR = 400
}
