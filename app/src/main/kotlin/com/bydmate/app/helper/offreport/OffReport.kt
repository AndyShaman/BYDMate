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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Daemon side of the power-off Telegram report (3.19, phase B). The daemon alone delivers it: the
 * app is killed within about a second of the car being switched off (quickboot force-stops it), the
 * daemon is not. The app keeps a ready report here ([arm]); when the daemon's own listener on the
 * power fids sees the power-off ([PowerOffAutomaton]), the report gets its time, is written to disk
 * ([PendingReports]) right there on the listener thread (disk only, never behind the network), put
 * into the pending set ([events]; there even when the disk write failed) and sent within the 8 s
 * before the networks go ([OffReportRetry]). Sent or refused for good, that event goes; otherwise
 * it waits, and every later [arm] (the app's next start on the next drive, with a token again)
 * delivers what waits, oldest first, with the «(записано в HH:MM)» line, backing off after a failed
 * pass. [disarm] deletes the pending files before it answers.
 *
 * An event is its key ([PendingReport.key], power-off time and report id), never the report id
 * alone: the app re-arms an unchanged report under the same id, so two power-offs can share it.
 *
 * The arm epoch ([epoch]) moves on every [disarm] and every [arm] to another chat: a power-off
 * report taken under an older epoch is not saved, and not attempted once more, it is dropped. Right
 * before every send, under [lock], the event must still be pending and its chat still the armed
 * one, so nothing goes out with a recipient the app has since replaced. Accepted: an arm or disarm
 * landing between that check and the request, or during it, does not stop that one request (to a
 * chat the user armed moments before).
 *
 * ONE loop on ONE sender thread ([send], [drain]) sends everything: the burst of a fresh power-off
 * first, then a pending pass when an arm asked for one. So an event is never sent twice by two
 * actors, and a fresh power-off never waits for an older report's backoff. [arm] only stores the
 * report and returns: the listener registration, its retries and the priming reads run on the
 * daemon's worker thread. A power fid whose registration failed is retried every
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

    /** Drop reasons in the log when the epoch moved. */
    private const val DROP_DISARM = "disarm"
    private const val DROP_CHAT = "chat_changed"

    /** Why [gate] let a pending pass stop or skip: delivered already, nothing armed, a power-off first. */
    private const val DROP_GONE = "gone"
    private const val DROP_UNARMED = "unarmed"
    private const val DROP_YIELD = "yield"

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

    /** Arm epoch, guarded by [lock]: see the class comment. [epochReason] names its last move. */
    private var epoch = 0L
    private var epochReason = DROP_DISARM

    /** The chat of the last [arm], kept after the power-off takes the report; null once disarmed. */
    private var recipient: Long? = null

    /** Pending delivery backoff on [monoClock], guarded by [lock]. */
    private var passNotBefore = 0L
    private var passBackoffMs = 0L

    /**
     * The pending set, guarded by [lock]: every event not delivered yet that this daemon knows of,
     * by key (power-offs of this run, files found on disk), at most [PendingReports.MAX], newest kept.
     */
    private val events = LinkedHashMap<String, PendingReport>()

    /** Power-offs whose burst has not run yet, oldest first; guarded by [lock]. */
    private val fresh = ArrayDeque<Burst>()

    /** [drain] is queued or running; [passWanted]: an arm asked for a pending pass. Guarded by [lock]. */
    private var drainQueued = false
    private var passWanted = false

    /** Direct read of a power fid (autoservice getInt), installed by the daemon; null = unreadable. */
    @Volatile internal var reader: (dev: Int, fid: Int) -> Int? = { _, _ -> null }

    /** Test seams: the daemon uses the vendor listener, HTTPS, the real clocks, threads and disk. */
    internal var registrar: (dev: Int, fid: Int, sink: FidPushSink) -> String = ::registerPowerFid
    internal var sender: (token: String, chatId: Long, text: String, connectMs: Int, readMs: Int) -> AttemptResult =
        ::postSendMessage
    internal var endpoint: (token: String) -> URL = { token -> URL("https://$TELEGRAM_HOST/bot$token/sendMessage") }
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

    /** Cuts a send attempt's connection at its deadline ([postSendMessage]). */
    private val watchdog: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "offreport-watchdog").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }

    private val sink = FidPushSink { fid, intValue, _, ts -> onPower(fid, intValue, ts) }

    /**
     * Holds [request] as THE report to send at the next power-off, replacing the previous one, and
     * lets the reports still pending on disk go out with its bot.
     */
    fun arm(request: ArmRequest) {
        val first = synchronized(lock) {
            if (recipient != null && recipient != request.chatId) bumpEpoch(DROP_CHAT)
            recipient = request.chatId
            armed = Armed(request, wallClock())
            if (monoClock() >= passNotBefore) passWanted = true
            val unregistered = registrations.isEmpty()
            if (unregistered) POWER_FIDS.forEach { (_, fid) -> registrations[fid] = FID_PENDING }
            unregistered
        }
        Log.i(TAG, "offreport: armed id=${request.id} len=${request.text.length}")
        schedule(0L, Runnable { guardedWork { if (first) registerPending() else prime() } })
        requestDrain()
    }

    /**
     * The report was switched off or the bot disconnected: nothing armed, nothing pending. The files
     * go here, before the binder call answers; false when any of them could not be deleted, so the
     * app asks again.
     */
    fun disarm(): Boolean {
        val had = synchronized(lock) {
            bumpEpoch(DROP_DISARM)
            recipient = null
            passNotBefore = 0L
            passBackoffMs = 0L
            events.clear()
            armed?.request?.id.also { armed = null }
        }
        Log.i(TAG, "offreport: disarmed id=${had ?: "-"}")
        val cleared = try {
            pending.deleteAll()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Log.w(TAG, "offreport: pending delete failed: ${e.javaClass.simpleName}")
            return false
        }
        cleared.ids.forEach { Log.i(TAG, "offreport: pending dropped id=$it reason=$DROP_DISARM") }
        if (!cleared.complete) Log.w(TAG, "offreport: pending delete incomplete, disarm not confirmed")
        return cleared.complete
    }

    /** Caller holds [lock]. */
    private fun bumpEpoch(reason: String) {
        epoch++
        epochReason = reason
    }

    /** Why a report taken under [taken] must go, or null while its epoch still holds. */
    private fun dropReason(taken: Long): String? = synchronized(lock) { if (epoch == taken) null else epochReason }

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
     * report per arm: a quick off-on-off before the app re-arms must not send stale text twice),
     * written to disk here (a few KB and an fsync, never waiting on the network), put into the
     * pending set and handed to the sender loop. This thread belongs to the firmware: nothing on it waits for more than local disk.
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
            Taken(report.request, offAt, epoch)
        }
        when {
            fired -> Log.i(TAG, "offreport: power off fid=$fid value=$value armed=${taken?.request?.id ?: "-"}")
            !wasPrimed && value > 0 -> Log.i(TAG, "offreport: power on fid=$fid value=$value")
        }
        if (taken == null) return
        val request = taken.request
        val report = PendingReport(request.id, request.chatId, taken.offAt, TelegramReportBuilder.fillTime(request.text, taken.offAt))
        try {
            // A disarm or another chat since the take: not written, and not held below.
            val saved = pending.save(report) { dropReason(taken.epoch) == null }
            if (saved) Log.i(TAG, "offreport: pending saved id=${request.id}")
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Log.w(TAG, "offreport: pending save failed id=${request.id}: ${e.javaClass.simpleName}")
        }
        // Held whether or not the disk took it: a failed write must not lose it while the daemon lives.
        val dropped = synchronized(lock) {
            if (epoch != taken.epoch) {
                remember(OffReportOutcome(request.id, OffReportState.FAILED, powerOffMs = taken.offAt, rc = epochReason))
                epochReason
            } else {
                hold(report)
                fresh.addLast(Burst(report, request.token, atElapsed, taken.epoch))
                null
            }
        }
        if (dropped != null) {
            Log.i(TAG, "offreport: pending dropped id=${request.id} reason=$dropped")
            return
        }
        requestDrain()
    }

    /** A report the power-off took: what it was armed with, when, and under which epoch. */
    private class Taken(val request: ArmRequest, val offAt: Long, val epoch: Long)

    /** A power-off's own burst: its event, the bot armed at the take, the power-off on [monoClock]. */
    private class Burst(val report: PendingReport, val token: String, val offElapsed: Long, val epoch: Long)

    /** Caller holds [lock]: into the pending set unless there already, the oldest past the cap out. */
    private fun hold(report: PendingReport) {
        events.putIfAbsent(report.key, report)
        val over = events.size - PendingReports.MAX
        if (over > 0) events.values.sortedBy { it.powerOffMs }.take(over).forEach { events.remove(it.key) }
    }

    /** Delivered or refused for good: exactly this event goes, from the set and from disk. */
    private fun forget(report: PendingReport) {
        synchronized(lock) { events.remove(report.key) }
        pending.delete(report.key)
    }

    /** Queues [drain] on the sender unless it is queued or running already, or has nothing to do. */
    private fun requestDrain() {
        val go = synchronized(lock) {
            val ok = !drainQueued && (fresh.isNotEmpty() || passWanted)
            if (ok) drainQueued = true
            ok
        }
        if (go) send(Runnable { drain() })
    }

    /**
     * Sender thread, THE delivery loop: every fresh power-off's burst first, then the pending pass an
     * arm asked for. It only ends under [lock] with nothing left, so work added meanwhile is never
     * stranded.
     */
    private fun drain() {
        while (true) {
            val burst = synchronized(lock) {
                val next = fresh.removeFirstOrNull()
                when {
                    next != null -> next
                    passWanted -> null.also { passWanted = false }
                    else -> {
                        drainQueued = false
                        return
                    }
                }
            }
            if (burst != null) runBurst(burst) else guardedWork { deliverPending() }
        }
    }

    private fun runBurst(burst: Burst) {
        val report = burst.report
        runCatching { sendAtPowerOff(burst) }.onFailure { t ->
            Log.w(TAG, "offreport: send crashed id=${report.id}: ${t.javaClass.simpleName}")
            synchronized(lock) {
                remember(OffReportOutcome(report.id, OffReportState.FAILED, powerOffMs = report.powerOffMs, rc = "crash"))
            }
        }
    }

    /** What [gate] decided right before a send. */
    private sealed class Gate {
        class Go(val token: String, val lateMark: String) : Gate()

        /** [drop]: the event goes for good; otherwise it stays for a later pass. */
        class Skip(val reason: String, val drop: Boolean) : Gate()
    }

    /**
     * Under [lock], immediately before the sender is called: the epoch [taken] still holds, the
     * event is still pending (not delivered by the other path meanwhile) and its chat is the armed
     * one. [burstToken] is the power-off's own bot; null = a pending pass, which sends with the bot
     * armed now and gives way to a fresh power-off.
     */
    private fun gate(report: PendingReport, taken: Long, burstToken: String?): Gate = synchronized(lock) {
        val burst = burstToken != null
        when {
            !burst && fresh.isNotEmpty() -> Gate.Skip(DROP_YIELD, drop = false).also { passWanted = true }
            epoch != taken -> Gate.Skip(epochReason, drop = burst)
            !events.containsKey(report.key) -> Gate.Skip(DROP_GONE, drop = false)
            report.chatId != recipient -> Gate.Skip(DROP_CHAT, drop = true)
            burstToken != null -> Gate.Go(burstToken, "")
            else -> armed?.request?.let { Gate.Go(it.token, it.lateMark) } ?: Gate.Skip(DROP_UNARMED, drop = false)
        }
    }

    /**
     * Sender thread: the burst, each attempt only past [gate]; the event goes once it was sent,
     * refused, or its epoch moved on, and is left alone when the pending pass delivered it first.
     */
    private fun sendAtPowerOff(burst: Burst) {
        val report = burst.report
        var gone = false
        val result = OffReportRetry.run(
            offAt = burst.offElapsed,
            clock = monoClock,
            sleep = sleep,
            attempt = { connectMs, readMs ->
                when (val go = gate(report, burst.epoch, burst.token)) {
                    is Gate.Go -> sender(go.token, report.chatId, report.text, connectMs, readMs)
                    is Gate.Skip -> {
                        gone = go.reason == DROP_GONE
                        AttemptResult(go.reason, AttemptResult.Verdict.STOP)
                    }
                }
            },
            onAttempt = { n, rc, sinceOff -> Log.i(TAG, "offreport: attempt $n rc=$rc ms=$sinceOff") },
        )
        if (gone) {
            Log.i(TAG, "offreport: burst skipped id=${report.id} reason=$DROP_GONE")
            return
        }
        val outcome = OffReportOutcome(
            id = report.id,
            state = if (result.sent) OffReportState.SENT else OffReportState.FAILED,
            powerOffMs = report.powerOffMs,
            sentAtMs = if (result.sent) wallClock() else 0L,
            attempts = result.attempts,
            rc = result.rc,
        )
        synchronized(lock) { remember(outcome) }
        when {
            result.sent -> forget(report)
            result.refused -> {
                forget(report)
                Log.i(TAG, "offreport: pending dropped id=${report.id} reason=${result.rc}")
            }
        }
        Log.i(TAG, "offreport: ${if (result.sent) "sent" else "failed"} id=${report.id} attempts=${result.attempts}")
    }

    /**
     * Sender thread: the pending set (this run's events plus the files on disk), oldest first, each
     * with the bot armed at that moment. Stops at the first failure that may heal (no network yet)
     * and backs off; a refusal drops that event. The backoff is checked here: it may have been set
     * after this pass was asked for.
     */
    private fun deliverPending() {
        val taken = synchronized(lock) {
            if (armed == null || monoClock() < passNotBefore) return
            epoch
        }
        val onDisk = pending.list()
        val queue = synchronized(lock) {
            if (epoch != taken) return
            onDisk.forEach(::hold)
            events.values.sortedBy { it.powerOffMs }
        }
        for (report in queue) if (!deliverOne(report, taken)) return
        synchronized(lock) {
            passBackoffMs = 0L
            passNotBefore = 0L
        }
    }

    /** One event of a pending pass taken under epoch [taken]; false ends the pass. */
    private fun deliverOne(report: PendingReport, taken: Long): Boolean {
        val go = when (val gate = gate(report, taken, burstToken = null)) {
            is Gate.Go -> gate
            is Gate.Skip -> {
                if (gate.drop) {
                    forget(report)
                    Log.i(TAG, "offreport: pending dropped id=${report.id} reason=${gate.reason}")
                }
                // Dropped or delivered already: the next one. Disarmed, another chat, nothing armed,
                // or a fresh power-off first: a later pass.
                return gate.drop || gate.reason == DROP_GONE
            }
        }
        val text = withLateMark(report, go.lateMark)
        val result = try {
            sender(go.token, report.chatId, text, PENDING_CONNECT_MS, PENDING_READ_MS)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            AttemptResult("err:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        }
        val ageS = (wallClock() - report.powerOffMs) / 1000
        when (result.verdict) {
            AttemptResult.Verdict.SENT -> {
                forget(report)
                rememberPending(report, OffReportState.SENT, result.rc)
                Log.i(TAG, "offreport: pending sent id=${report.id} age_s=$ageS")
            }
            AttemptResult.Verdict.STOP -> {
                forget(report)
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
                return false
            }
        }
        return true
    }

    private fun rememberPending(report: PendingReport, state: Int, rc: String) {
        val sentAt = if (state == OffReportState.SENT) wallClock() else 0L
        synchronized(lock) { remember(OffReportOutcome(report.id, state, report.powerOffMs, sentAt, 1, rc)) }
    }

    /**
     * «(записано в 18:42)» from the app's template (Telegram HTML, italic), with the date when the
     * power-off was another day; its own block above the map link.
     */
    private fun withLateMark(report: PendingReport, template: String): String {
        if (template.isBlank()) return report.text
        val now = wallClock()
        val stamp = if (sameDay(report.powerOffMs, now)) TelegramReportBuilder.formatTime(report.powerOffMs)
        else TelegramReportBuilder.formatDateTime(report.powerOffMs)
        val mark = template.replace(TelegramReportBuilder.TIME_PLACEHOLDER, TelegramReportBuilder.escape(stamp))
        return TelegramReportBuilder.withLateMark(report.text, mark)
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
            epoch = 0L
            epochReason = DROP_DISARM
            recipient = null
            outcomes.clear()
            last = null
            registrations.clear()
            retryScheduled = false
            events.clear()
            fresh.clear()
            drainQueued = false
            passWanted = false
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
     * One Bot API `sendMessage` over plain HttpURLConnection (no OkHttp in the daemon), Telegram
     * HTML like the app's backup sink. rc is the HTTP code or `io:<exception>`; never the URL, which
     * carries the token.
     *
     * Bounded as a whole to [connectMs] + [readMs] from its start: the host is resolved first under
     * its own bound ([OffReportRetry.callWithin]), then a watchdog disconnects the connection at the
     * deadline, which aborts a hung connect, write or read (a server trickling bytes defeats the read
     * timeout alone). The verdict comes from the status line; the body is never read (a trickling
     * body would hold the read, and on some stacks the disconnect waits for it). Accepted residual:
     * the connection resolves the host again; right after the pre-check that lookup hits Android's
     * InetAddress positive cache, and the watchdog bounds everything after it. Connecting to the
     * pre-resolved address instead would break TLS hostname verification and SNI.
     */
    internal fun postSendMessage(token: String, chatId: Long, text: String, connectMs: Int, readMs: Int): AttemptResult {
        val startNs = System.nanoTime()
        val url = endpoint(token)
        val resolved = try {
            OffReportRetry.callWithin(connectMs.toLong()) { InetAddress.getAllByName(url.host) }
        } catch (e: IOException) {
            return AttemptResult("io:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            return AttemptResult("err:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        }
        if (resolved == null) return AttemptResult("io:dns_timeout", AttemptResult.Verdict.RETRY)
        val body = sendMessageBody(chatId, text).toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        var cut: ScheduledFuture<*>? = null
        return try {
            conn = url.openConnection() as HttpURLConnection
            val left = connectMs + readMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            val watched = conn
            cut = watchdog.schedule({ watched.disconnect() }, left.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
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
            // -1: no valid status line (cut by the watchdog or the network), not a refusal.
            if (code < HTTP_FIRST_CODE) AttemptResult("io:no_status", AttemptResult.Verdict.RETRY)
            else AttemptResult(code.toString(), OffReportRetry.verdictFor(code))
        } catch (e: IOException) {
            AttemptResult("io:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            AttemptResult("err:${e.javaClass.simpleName}", AttemptResult.Verdict.RETRY)
        } finally {
            cut?.cancel(false)
            conn?.disconnect()
        }
    }

    /** The form body: Telegram HTML, no preview card for the map link (Bot API 7.0+). */
    internal fun sendMessageBody(chatId: Long, text: String): String =
        "chat_id=$chatId&parse_mode=HTML" +
            "&link_preview_options=" + URLEncoder.encode(NO_LINK_PREVIEW, "UTF-8") +
            "&text=" + URLEncoder.encode(text, "UTF-8")

    private const val NO_LINK_PREVIEW = """{"is_disabled":true}"""
    private const val HTTP_FIRST_CODE = 100
}
