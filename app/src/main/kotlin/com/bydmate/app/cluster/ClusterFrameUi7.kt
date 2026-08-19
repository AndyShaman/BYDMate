package com.bydmate.app.cluster

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.WriteOutcome
import com.bydmate.app.split.Split37Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ClusterFrameUi7"

/**
 * Opens the cluster's "Map" frame on platformized firmware (BYD OTA V1.6), where the instrument
 * panel ignores whatever we project unless its own CAN registers say a map belongs there.
 *
 * Verified on the car 2026-08-18 (all writes reversible):
 *   - CENTER [FID_CENTER] = [CENTER_MAP] makes the cluster draw the Map frame, i.e. our projected
 *     content becomes visible instead of the native center widget;
 *   - LEFT [FID_LEFT] = [LEFT_CARD] removes the native tire-pressure car widget from the left zone,
 *     so the map shows through there too;
 *   - (verified 2026-08-19) MENU [FID_MENU_SET] = [MENU_HIDDEN] hides both native side cards (left
 *     tire/car card, right compass/clock/music card) for the projection; the status register
 *     [FID_MENU_STATUS] is what we read and put back. The firmware still shows the cards for ~15 s
 *     on a wheel-button press, by design.
 * The stock values on that car were CENTER=7 / LEFT=0, but other cars and cluster modes answer 1 or
 * 0, so the pair is READ at runtime and written back verbatim on [restore] — never assumed.
 *
 * The firmware rewrites CENTER whenever the driver cycles the cluster mode with the wheel knob,
 * which is why [apply] leaves a re-assert job behind.
 *
 * Pre-OTA firmware is untouched: every entry point returns before the first read, write or journal
 * line when [Split37Engine.isPlatformizedFirmware] is false.
 *
 * Concurrency: own [mutex], deliberately NOT [ClusterProjectionManager]'s — the re-assert job runs
 * on the manager's scope and would deadlock against a transition holding that lock.
 *
 * Two features want the same frame ([Owner]): the navigation projection and the blind-spot camera.
 * The first one opens it, a later one joins it, and it goes back to the car only when the last one
 * lets go: neither may hand the cluster back while the other is still drawing on it.
 *
 * Fail-soft: nothing here throws but a cancellation, so a projection is never failed by the frame.
 */
class ClusterFrameUi7(
    private val prefs: SharedPreferences,
    private val journal: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Firmware gate; injectable for tests, read once per process by default. */
    private val platformized: () -> Boolean = { platformizedFirmware },
) {

    /** Features that put content on the cluster and therefore need the Map frame open. */
    enum class Owner(internal val tag: String) {
        PROJECTION("projection"),
        CAMERA("camera"),
    }

    /**
     * Stock values of the registers, as read from the car before we touched them. [menu] is null
     * when the menu status could not be read, and on a pair persisted before this register was
     * known: either way the side cards are not ours and stay untouched, apply and restore alike.
     */
    private data class Frame(val center: Int, val left: Int, val menu: Int?)

    private val mutex = Mutex()

    /** Who currently holds the frame; guarded by [mutex]. Empty at process start, which is what
     *  makes [recoverStale] a prior session's business and not a live owner's. */
    private val owners = mutableSetOf<Owner>()
    private var saved: Frame? = null
    private var reassertJob: Job? = null

    /** Consecutive re-assert passes that could not read or could not write; see [reassertOnce]. */
    @Volatile
    private var failedPasses = 0

    /** True between a successful [apply] and its [restore]; read lock-free by [hasStalePair]. */
    @Volatile
    private var applied = false

    /**
     * Opens the Map frame for a projection that is already up. Saves the stock pair first (write
     * ahead: committed to prefs BEFORE the car is touched, so a power cut mid-projection still
     * leaves the pair for [recoverStale]), then writes CENTER then LEFT.
     *
     * Returns true when CENTER really moved ([WriteOutcome.REAL]) and LEFT was at least accepted.
     * A CENTER no-op means the fid is inert on this trim: nothing moved, so nothing is saved,
     * re-asserted or restored. A re-apply (reproject / resize) keeps the pair saved by the first
     * one — re-reading would save OUR values as the stock pair.
     *
     * The native side cards are hidden in the same pass, but never at the frame's expense: a menu
     * register that will not read or will not take the write costs us the cards, not the frame.
     *
     * A second [owner] arriving on an already open frame only joins it: the registers are ours
     * already, and re-reading them would save OUR values as the stock pair.
     */
    suspend fun apply(helper: HelperClient, owner: Owner): Boolean {
        if (!platformized()) return false
        return mutex.withLock {
            if (applied && owner !in owners) {
                owners += owner
                // A joiner must never get an unguarded frame: the re-assert job stops itself after
                // MAX_REASSERT_FAILURES, and without it the next knob turn takes the center zone
                // back for the rest of the session.
                if (reassertJob?.isActive != true) startReassert(helper)
                journal("ui7 frame: joined by ${owner.tag}")
                return@withLock true
            }
            val stock = saved ?: loadPersisted() ?: run {
                val center = readRegister(helper, FID_CENTER)
                val left = readRegister(helper, FID_LEFT)
                if (center == null || left == null) {
                    Log.w(TAG, "cluster frame registers unreadable; leaving the cluster alone")
                    journal("ui7 frame: read failed, skipped")
                    return@withLock false
                }
                // Cosmetic register: unlike the pair, an unreadable one is not a reason to leave the
                // cluster alone. null keeps it out of the write below and out of the restore.
                val menu = readRegister(helper, FID_MENU_STATUS)
                if (menu == null) journal("ui7 frame: menu status unreadable, side cards left alone")
                val read = Frame(center, left, menu)
                if (!persist(read)) {
                    Log.w(TAG, "cluster frame pair not persisted; skipping the write")
                    journal("ui7 frame: save failed, skipped")
                    return@withLock false
                }
                read
            }
            // NonCancellable from the first write on: the camera cancels this coroutine on a
            // fast side flicker, and a cancellation between the CENTER write and [applied] would
            // leave the car in a frame this object does not know it opened, with nobody to put it
            // back. The reads above stay cancellable, they touch nothing.
            withContext(NonCancellable) {
                saved = stock
                val centerOutcome = writeRegister(helper, FID_CENTER, CENTER_MAP)
                // NOOP = the daemon accepted the write and the car did nothing with it: this trim
                // has no Map frame to open. Nothing moved, so there is nothing to put back — drop
                // the pair instead of leaving a stale one for the next start. Only on the FIRST
                // apply: once the frame is ours, a no-op is just a write that did not land and the
                // pair must survive.
                if (centerOutcome == WriteOutcome.NOOP && !applied) {
                    saved = null
                    clearPersisted()
                    Log.w(TAG, "cluster frame center write is a no-op; frame unsupported on this car")
                    journal("ui7 frame: center write no-op, frame unsupported here")
                    return@withContext false
                }
                val leftOutcome = writeRegister(helper, FID_LEFT, LEFT_CARD)
                val menuOk = stock.menu == null ||
                    writeRegister(helper, FID_MENU_SET, MENU_HIDDEN).accepted()
                // Applied even on a failed write: a half-written pair still has to be restored, and
                // the re-assert job is what retries the write.
                applied = true
                owners += owner
                val frameOk = centerOutcome == WriteOutcome.REAL && leftOutcome.accepted()
                // The journal's ok covers the cards too, the return value deliberately does not:
                // cards that stayed on screen are not a reason to fail a projection that is up.
                journal("ui7 frame: center ${stock.center}->$CENTER_MAP left ${stock.left}->$LEFT_CARD " +
                    (if (stock.menu != null) "menu ${stock.menu}->$MENU_HIDDEN " else "") +
                    "ok=${frameOk && menuOk}")
                startReassert(helper)
                frameOk
            }
        }
    }

    /**
     * Writes the stock pair back — LEFT first, then CENTER (that order was the one verified on the
     * car; the reverse leaves the left zone empty until the next mode change). CENTER goes back only
     * while it still holds OUR value: a driver who cycled the cluster mode past the map already
     * chose a frame, and dragging them back to the stock one would be a second surprise. The saved
     * pair is dropped only when every attempted write is accepted, so a failed restore is retried by
     * the next teardown or by [recoverStale] at the next service start.
     *
     * Releases [owner]'s hold only: while another owner still needs the cluster nothing is written,
     * and an owner that never opened the frame releases nothing.
     */
    suspend fun restore(helper: HelperClient, owner: Owner) {
        if (!platformized()) return
        mutex.withLock {
            if (owner !in owners) return@withLock
            owners -= owner
            if (owners.isNotEmpty()) {
                journal("ui7 frame: kept, still held by ${owners.joinToString("+") { it.tag }}")
                return@withLock
            }
            // cancel() without join: the job takes [mutex] for each pass and we hold it here — a
            // join would deadlock. Inside the lock so no pass can re-open the frame behind us.
            reassertJob?.cancel()
            reassertJob = null
            restoreLocked(helper)
        }
    }

    /**
     * Service-start recovery, sibling of [ClusterProjectionManager.recoverStaleCompositor]: a
     * persisted pair with nothing applied in this process means a prior process (or the whole head
     * unit) died mid-projection and the cluster is still showing OUR frame with nobody drawing.
     */
    suspend fun recoverStale(helper: HelperClient) {
        if (!platformized() || applied) return
        mutex.withLock {
            if (applied) return@withLock
            if (saved == null && loadPersisted() == null) return@withLock
            journal("ui7 frame: recovering frame left by a prior session")
            restoreLocked(helper)
        }
    }

    /**
     * True when a prior session left a frame on the cluster that this process should restore.
     * Cheap and lock-free so the recovery entry point can skip starting the daemon on cars that
     * have nothing to recover (every pre-OTA car included).
     */
    fun hasStalePair(): Boolean = platformized() && !applied && loadPersisted() != null

    /**
     * Caller holds [mutex]. NonCancellable as a whole: the caller ([restore]) has already dropped
     * the last owner and killed the re-assert job, so a cancellation anywhere in here would leave
     * our frame on the cluster with [applied] still true: no owner to release it, no job to
     * re-assert it, and [hasStalePair] blind to it for the rest of the process.
     */
    private suspend fun restoreLocked(helper: HelperClient) = withContext(NonCancellable) {
        val stock = saved ?: loadPersisted() ?: run { applied = false; return@withContext }
        val center = readRegister(helper, FID_CENTER)
        // An unreadable CENTER is restored anyway: without a verdict, putting the stock value back
        // is the safer of the two guesses (the alternative leaves the cluster in our frame).
        val restoreCenter = center == null || center == CENTER_MAP
        val leftOk = writeRegister(helper, FID_LEFT, stock.left).accepted()
        val centerOk = !restoreCenter || writeRegister(helper, FID_CENTER, stock.center).accepted()
        // Verbatim like the pair, no verdict of our own: a stock 2 is a driver who already hid the
        // cards with the native toggle, and the write is then a no-op.
        val menuOk = stock.menu == null || writeRegister(helper, FID_MENU_SET, stock.menu).accepted()
        applied = false
        journal("ui7 frame: restored left=${stock.left} " +
            (if (restoreCenter) "center=${stock.center}" else "center kept=$center") +
            (if (stock.menu != null) " menu=${stock.menu}" else "") +
            " ok=${leftOk && centerOk && menuOk}")
        if (leftOk && centerOk && menuOk) {
            saved = null
            clearPersisted()
        } else {
            Log.w(TAG, "cluster frame restore not confirmed; keeping the saved pair for a retry")
            saved = stock
        }
    }

    private fun startReassert(helper: HelperClient) {
        reassertJob?.cancel()
        failedPasses = 0
        reassertJob = scope.launch {
            while (isActive) {
                delay(REASSERT_INTERVAL_MS)
                reassertOnce(helper)
            }
        }
    }

    /**
     * One re-assert pass: the firmware rewrites CENTER when the driver cycles the cluster mode with
     * the wheel knob, which would drop our frame for the rest of the session. Silent while both
     * registers still hold our values — a healthy session writes nothing and journals nothing.
     *
     * The menu register is left out on purpose: the firmware itself brings the side cards back for
     * ~15 s on a wheel-button press, and a pass would fight that.
     *
     * Deliberate trade-off: while a projection is on, the driver cannot leave the map frame with the
     * wheel knob — every other frame is taken back within [REASSERT_INTERVAL_MS] (product decision,
     * no setting). Turning the projection off is what gives the cluster back.
     *
     * A pass is bounded by [REASSERT_PASS_TIMEOUT_MS] so a wedged daemon cannot hold [mutex] for the
     * two full read/write timeouts, and [MAX_REASSERT_FAILURES] consecutive failed passes stop the
     * job: a car that keeps refusing the registers is not going to start accepting them at 5 s
     * intervals for the rest of the drive.
     */
    internal suspend fun reassertOnce(helper: HelperClient) {
        val ok = withTimeoutOrNull(REASSERT_PASS_TIMEOUT_MS) {
            mutex.withLock { reassertPass(helper) }
        } ?: false
        if (ok) {
            failedPasses = 0
            return
        }
        failedPasses++
        if (failedPasses >= MAX_REASSERT_FAILURES) {
            Log.w(TAG, "cluster frame re-assert failed $MAX_REASSERT_FAILURES times; stopping")
            journal("ui7 frame: reassert stopped after $MAX_REASSERT_FAILURES failures")
            reassertJob?.cancel()
            reassertJob = null
        }
    }

    /** Caller holds [mutex]. Returns false when the pass could not read or could not write. */
    private suspend fun reassertPass(helper: HelperClient): Boolean {
        if (!applied) return true
        val center = readRegister(helper, FID_CENTER)
        val left = readRegister(helper, FID_LEFT)
        if (center == null || left == null) return false
        // Between the reads and the writes: a restore() that cancelled us while the reads were in
        // flight must not be followed by our writes putting the frame straight back.
        currentCoroutineContext().ensureActive()
        val centerDrifted = center != CENTER_MAP
        val leftDrifted = left != LEFT_CARD
        if (!centerDrifted && !leftDrifted) return true
        val centerOk = !centerDrifted || writeRegister(helper, FID_CENTER, CENTER_MAP).accepted()
        val leftOk = !leftDrifted || writeRegister(helper, FID_LEFT, LEFT_CARD).accepted()
        journal("ui7 frame: reasserted" +
            (if (centerDrifted) " center=$center->$CENTER_MAP ok=$centerOk" else "") +
            (if (leftDrifted) " left=$left->$LEFT_CARD ok=$leftOk" else ""))
        return centerOk && leftOk
    }

    private suspend fun readRegister(helper: HelperClient, fid: Int): Int? =
        runCatching { helper.read(DEV_INSTRUMENT, fid) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.toInt()
            // A sentinel is not a value: persisting or writing one back would put garbage on the
            // cluster's own registers.
            ?.let { SentinelDecoder.decodeInt(it) }

    private suspend fun writeRegister(helper: HelperClient, fid: Int, value: Int): WriteOutcome =
        runCatching { WriteOutcome.fromStatus(helper.writeStatus(DEV_INSTRUMENT, fid, value)) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(WriteOutcome.TRANSIENT)

    /** The daemon answered and the car did not reject the write (real action or inert fid). */
    private fun WriteOutcome.accepted(): Boolean =
        this == WriteOutcome.REAL || this == WriteOutcome.NOOP

    /** Write-ahead, like KEY_COMPOSITOR_POWERED: commit on IO, never apply(). */
    @Suppress("ApplySharedPref")
    private suspend fun persist(frame: Frame): Boolean = runCatching {
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
                .putInt(KEY_SAVED_CENTER, frame.center)
                .putInt(KEY_SAVED_LEFT, frame.left)
            if (frame.menu != null) editor.putInt(KEY_SAVED_MENU, frame.menu)
            editor.commit()
        }
    }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)

    @Suppress("ApplySharedPref")
    private suspend fun clearPersisted() {
        runCatching {
            withContext(Dispatchers.IO) {
                prefs.edit()
                    .remove(KEY_SAVED_CENTER)
                    .remove(KEY_SAVED_LEFT)
                    .remove(KEY_SAVED_MENU)
                    .commit()
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun loadPersisted(): Frame? =
        if (prefs.contains(KEY_SAVED_CENTER) && prefs.contains(KEY_SAVED_LEFT)) {
            Frame(
                center = prefs.getInt(KEY_SAVED_CENTER, 0),
                left = prefs.getInt(KEY_SAVED_LEFT, 0),
                // Absent on a pair saved before this register was known: nothing to put back there.
                menu = if (prefs.contains(KEY_SAVED_MENU)) prefs.getInt(KEY_SAVED_MENU, 0) else null,
            )
        } else {
            null
        }

    /** Test seam: the job itself stays private, its liveness is what the tests assert. */
    internal fun isReassertRunning(): Boolean = reassertJob?.isActive == true

    companion object {
        /** Instrument-panel device of the autoservice catalog. */
        internal const val DEV_INSTRUMENT = 1007

        /** Center zone of the cluster: which frame it draws. */
        internal const val FID_CENTER = 1086373904

        /** Left zone of the cluster: the native car/tire-pressure widget. */
        internal const val FID_LEFT = 1086373907

        /** Cluster menu status: 1 = the native side cards are on screen, 2 = hidden. Read only. */
        internal const val FID_MENU_STATUS = 683679769

        /** Cluster menu setting, the register behind the native "hide the instrument menu" toggle.
         *  Not the same fid as [FID_MENU_STATUS] — this one only takes writes. */
        internal const val FID_MENU_SET = 1324691472

        /** Center zone value that draws the Map frame our projection lands in. */
        internal const val CENTER_MAP = 2

        /** Left zone value that clears the native widget. */
        internal const val LEFT_CARD = 1

        /** Menu value that takes both native side cards off the cluster. */
        internal const val MENU_HIDDEN = 2

        internal const val REASSERT_INTERVAL_MS = 5000L

        /** Bound on one pass, shorter than the interval: a wedged daemon must not hold [mutex]. */
        internal const val REASSERT_PASS_TIMEOUT_MS = 4000L

        internal const val MAX_REASSERT_FAILURES = 3

        // Stock pair of a live projection, in [ClusterProjectionManager.PREFS_NAME]. Absent = we
        // have nothing on the cluster to put back.
        internal const val KEY_SAVED_CENTER = "ui7_frame_saved_center"
        internal const val KEY_SAVED_LEFT = "ui7_frame_saved_left"

        // Written only when the menu status was readable; absent = the side cards are not ours.
        internal const val KEY_SAVED_MENU = "ui7_frame_saved_menu"

        /** Reflective system-property read; the answer is fixed for the life of the boot. */
        private val platformizedFirmware: Boolean by lazy { Split37Engine.isPlatformizedFirmware() }
    }
}
