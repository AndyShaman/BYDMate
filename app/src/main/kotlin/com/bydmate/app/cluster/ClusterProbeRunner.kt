package com.bydmate.app.cluster

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HelperClientImpl
import com.bydmate.app.split.Split37Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val TAG = "ClusterProbe"

/**
 * Test-build probe of the two UI7 levers that might hide the cluster's RIGHT card zone: the cluster
 * card menu ([ClusterMenuClient]) and the DiCar instrument SPI ([DiCarInstrumentClient]). Driven
 * from a Mac with `am broadcast -a com.bydmate.app.CLUSTER_PROBE --es cmd <cmd> …`, answers land in
 * logcat (tag `ClusterProbe`, in full) and in [ClusterJournal] (one short `probe …` line per step,
 * so the answers survive into the diagnostic dump long after the logcat ring rotated).
 *
 * Not a feature and not wired into any existing path: nothing here runs unless a broadcast asks for
 * it, and on pre-OTA firmware ([Split37Engine.isPlatformizedFirmware]) every command stops at one
 * journal line before touching the car.
 *
 * Safety: only the explicit write commands write, every write is paired with an automatic restore in
 * a `finally` under [NonCancellable], the hold window is capped at [HOLD_MAX_SEC], and the saved
 * right-menu JSON stays in prefs across a process death so `menu_restore` can put it back by hand.
 * One command at a time ([mutex]); a second one reports `probe: busy` and does nothing.
 */
class ClusterProbeRunner(
    private val helper: HelperClient,
    private val journal: (String) -> Unit,
    private val platformized: () -> Boolean,
    private val menu: ClusterMenuClient,
    private val dicar: DiCarInstrumentClient,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {

    /** One parsed broadcast. Limits are applied here, so [run] can trust its arguments. */
    internal data class ProbeCommand(
        val cmd: String,
        val holdSec: Int,
        val watchSec: Int,
        val items: List<String>,
        val what: String?,
        val value: Int,
    )

    private val mutex = Mutex()

    /** Receiver entry point: parse, hand to the probe scope, return (commands run for minutes). */
    fun handle(intent: Intent) {
        val command = parse(intent)
        scope.launch { run(command) }
    }

    internal fun parse(intent: Intent): ProbeCommand = ProbeCommand(
        cmd = intent.getStringExtra(EXTRA_CMD).orEmpty().trim(),
        holdSec = intent.getIntExtra(EXTRA_HOLD, HOLD_DEFAULT_SEC).coerceIn(1, HOLD_MAX_SEC),
        watchSec = intent.getIntExtra(EXTRA_SECS, WATCH_DEFAULT_SEC).coerceIn(1, WATCH_MAX_SEC),
        items = intent.getStringExtra(EXTRA_ITEMS).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() },
        what = intent.getStringExtra(EXTRA_WHAT)?.trim(),
        value = intent.getIntExtra(EXTRA_VALUE, 0),
    )

    internal suspend fun run(command: ProbeCommand) {
        if (!platformized()) {
            journal("probe: not platformized")
            return
        }
        if (!mutex.tryLock()) {
            Log.w(TAG, "probe ${command.cmd} refused: another command is running")
            journal("probe: busy")
            return
        }
        try {
            when (command.cmd) {
                CMD_READ -> read()
                CMD_DICAR_READ -> dicarRead()
                CMD_WATCH -> watch(command.watchSec)
                CMD_MENU_CLEAR ->
                    menuApply(CMD_MENU_CLEAR, listOf(MENU_ZERO_SELECTED), command.holdSec)
                CMD_MENU_SET ->
                    if (command.items.isEmpty()) journal("probe menu_set: no items")
                    else menuApply(CMD_MENU_SET, command.items, command.holdSec)
                CMD_MENU_RESTORE -> restoreRightMenu()
                CMD_DICAR_SET -> dicarSet(command.what, command.value, command.holdSec)
                else -> journal("probe: unknown cmd ${command.cmd}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "probe ${command.cmd} threw", e)
            journal("probe ${command.cmd}: exception ${e.javaClass.simpleName}")
        } finally {
            mutex.unlock()
        }
    }

    /** Read-only: the fid family around the cluster zones plus all three menu answers. */
    private suspend fun read() {
        // map (inline) and not joinToString (not inline): the reads inside are suspending.
        val fids = READ_FIDS.map { (name, fid) -> "$name=${fidText(readFid(fid))}" }.joinToString(" ")
        Log.i(TAG, "probe read: $fids")
        journal("probe read: $fids")
        for (side in 0..2) {
            val json = menu.getClusterMenu(side)
            Log.i(TAG, "probe menu[$side]: $json")
            journal("probe menu[$side]: ${short(json)}")
        }
    }

    /** Read-only: the six DiCar getters, expected to answer 20004 from app uid. */
    private suspend fun dicarRead() {
        val line = DICAR_GETTERS.map { (name, call) ->
            val (tx, arg) = call
            "$name=${dicar.result(tx, arg)?.toString() ?: NO_ANSWER}"
        }.joinToString(" ")
        Log.i(TAG, "probe dicar: $line")
        journal("probe dicar: $line")
    }

    /**
     * Read-only: polls the cluster status fids while the driver works the wheel knob, and journals
     * transitions only — the ring holds [ClusterJournal.MAX_ENTRIES] lines and a per-tick dump would
     * flush it in seconds.
     */
    private suspend fun watch(seconds: Int) {
        journal("probe watch: start ${seconds}s")
        var previous = emptyMap<String, Long?>()
        var changes = 0
        repeat(seconds * 1000 / WATCH_INTERVAL_MS.toInt()) { tick ->
            val current = WATCH_FIDS.associate { (name, fid) -> name to readFid(fid) }
            if (tick > 0) {
                val moved = current.filter { (name, value) -> previous[name] != value }
                if (moved.isNotEmpty()) {
                    changes++
                    val line = moved.entries.joinToString(" ") { (name, value) ->
                        "$name ${fidText(previous[name])}->${fidText(value)}"
                    }
                    Log.i(TAG, "probe watch: $line")
                    journal("probe watch: $line")
                }
            }
            previous = current
            delay(WATCH_INTERVAL_MS)
        }
        journal("probe watch: done, $changes changes")
    }

    /**
     * Saves the current right menu, puts [right] there for [holdSec] seconds, then restores.
     * The RIGHT cover-panel fid is read before the write, a second after it and at the end of the
     * hold: if the card zone really goes away, that status is where it shows.
     *
     * Nothing is written without a selection to put back: a menu the probe cannot read (or cannot
     * parse) is a menu it cannot restore, and the driver would be left with our card list. The read
     * lands in logcat in full either way, which is what a shape we do not parse needs.
     */
    @Suppress("ApplySharedPref")
    private suspend fun menuApply(tag: String, right: List<String>, holdSec: Int) {
        val saved = menu.getClusterMenu(SIDE_RIGHT)
        Log.i(TAG, "probe $tag: saved right menu = $saved")
        // commit(), not apply(): this JSON is the only way back to the driver's own cards, and a
        // head unit that dies during the hold must find it on disk at the next start.
        if (parseSelectedRightMenu(saved) != null) {
            prefs.edit().putString(KEY_SAVED_RIGHT_MENU, saved).commit()
        }
        if (parseSelectedRightMenu(prefs.getString(KEY_SAVED_RIGHT_MENU, null)) == null) {
            journal("probe $tag: right menu unreadable (${saved?.length ?: -1} chars), skipped")
            return
        }
        journal("probe $tag: saved ${saved?.length ?: -1} chars, items=${right.joinToString("+")}")
        val before = readFid(FID_RIGHT_COVER)
        try {
            val rc = menu.setClusterMenu(arrayOf(MENU_UNCHANGED), right.toTypedArray())
            delay(SETTLE_MS)
            val settled = readFid(FID_RIGHT_COVER)
            Log.i(TAG, "probe $tag: rc=$rc right ${fidText(before)}->${fidText(settled)}")
            journal(
                "probe $tag: rc=${rc ?: NO_ANSWER} right ${fidText(before)}->${fidText(settled)} " +
                    "hold=${holdSec}s"
            )
            delay(holdSec * 1000L)
            journal("probe $tag: after hold right=${fidText(readFid(FID_RIGHT_COVER))}")
        } finally {
            withContext(NonCancellable) { restoreRightMenu() }
        }
    }

    /**
     * Puts the saved selection back. An empty saved selection is a real state ("nothing selected"),
     * an unreadable one is not: without knowing what was there, leaving the menu alone beats
     * guessing a card list for the driver's cluster.
     */
    private suspend fun restoreRightMenu() {
        val saved = prefs.getString(KEY_SAVED_RIGHT_MENU, null)
        val parsed = parseSelectedRightMenu(saved)
        val target = parsed?.ifEmpty { listOf(MENU_ZERO_SELECTED) } ?: listOf(MENU_UNCHANGED)
        val rc = runCatching {
            menu.setClusterMenu(arrayOf(MENU_UNCHANGED), target.toTypedArray())
        }.onFailure { Log.w(TAG, "probe restore threw", it) }.getOrNull()
        Log.i(TAG, "probe restore: target=$target rc=$rc")
        if (parsed == null) {
            journal("probe restore: unparsed, left as is")
        } else {
            journal("probe restore: items=${target.joinToString("+")} rc=${rc ?: NO_ANSWER}")
        }
    }

    /**
     * One DiCar setter, bracketed by its paired getter: the current value is what the restore writes
     * back, so a getter that does not answer (the expected 20004) cancels the whole command — a
     * write we cannot undo has no place on a driver's cluster.
     */
    private suspend fun dicarSet(what: String?, value: Int, holdSec: Int) {
        val target = DICAR_SETTERS[what]
        if (target == null) {
            journal("probe dicar_set: unknown what=$what")
            return
        }
        val (getterTx, setterTx) = target
        val current = dicar.result(getterTx)
        val saved = when (val read = current?.takeIf { it.ok }?.value) {
            is Int -> read
            is Boolean -> if (read) 1 else 0
            is Double -> read.toInt()
            else -> null
        }
        if (saved == null) {
            journal("probe dicar_set: $what getter=${current?.toString() ?: NO_ANSWER}, skipped")
            return
        }
        val beforeRight = readFid(FID_RIGHT_COVER)
        val beforeLeft = readFid(FID_LEFT_COVER)
        try {
            val rc = dicar.status(setterTx, value)
            delay(SETTLE_MS)
            Log.i(TAG, "probe dicar_set: $what $saved->$value rc=$rc")
            journal(
                "probe dicar_set: $what $saved->$value rc=${rc?.toString() ?: NO_ANSWER} " +
                    "right ${fidText(beforeRight)}->${fidText(readFid(FID_RIGHT_COVER))} " +
                    "left ${fidText(beforeLeft)}->${fidText(readFid(FID_LEFT_COVER))} hold=${holdSec}s"
            )
            delay(holdSec * 1000L)
        } finally {
            withContext(NonCancellable) {
                val back = runCatching { dicar.status(setterTx, saved) }
                    .onFailure { Log.w(TAG, "probe dicar_set restore threw", it) }
                    .getOrNull()
                journal("probe dicar_set: $what restored=$saved rc=${back?.toString() ?: NO_ANSWER}")
            }
        }
    }

    private suspend fun readFid(fid: Int): Long? =
        runCatching { helper.read(ClusterFrameUi7.DEV_INSTRUMENT, fid) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

    private fun fidText(value: Long?): String = value?.toString() ?: "-"

    private fun short(json: String?): String =
        when {
            json == null -> NO_ANSWER
            json.length <= JOURNAL_JSON_CHARS -> json
            else -> json.take(JOURNAL_JSON_CHARS) + "..."
        }

    companion object {
        const val ACTION_PROBE = "com.bydmate.app.CLUSTER_PROBE"

        internal const val EXTRA_CMD = "cmd"
        internal const val EXTRA_HOLD = "hold"
        internal const val EXTRA_SECS = "secs"
        internal const val EXTRA_ITEMS = "items"
        internal const val EXTRA_WHAT = "what"
        internal const val EXTRA_VALUE = "v"

        internal const val CMD_READ = "read"
        internal const val CMD_DICAR_READ = "dicar_read"
        internal const val CMD_WATCH = "watch"
        internal const val CMD_MENU_CLEAR = "menu_clear"
        internal const val CMD_MENU_SET = "menu_set"
        internal const val CMD_MENU_RESTORE = "menu_restore"
        internal const val CMD_DICAR_SET = "dicar_set"

        internal const val HOLD_DEFAULT_SEC = 20
        internal const val HOLD_MAX_SEC = 120
        internal const val WATCH_DEFAULT_SEC = 30
        internal const val WATCH_MAX_SEC = 180
        internal const val WATCH_INTERVAL_MS = 500L

        /** Time given to the cluster to act on a write before the status fid is read back. */
        private const val SETTLE_MS = 1000L

        private const val JOURNAL_JSON_CHARS = 200
        private const val NO_ANSWER = "noanswer"

        /** Right menu of `getClusterMenu`/`setClusterMenu`. */
        internal const val SIDE_RIGHT = 2

        // Cluster status fids (dev 1007, tx 5); the CENTER/LEFT pair is ClusterFrameUi7's.
        internal const val FID_LEFT_COVER = 1086369832
        internal const val FID_RIGHT_COVER = 1086369834
        internal const val FID_THEME_STATUS = 683679777
        internal const val FID_MENU_STATUS = 683679769
        internal const val FID_THEME = 1086369808

        private val READ_FIDS = listOf(
            "leftCover" to FID_LEFT_COVER,
            "rightCover" to FID_RIGHT_COVER,
            "themeStatus" to FID_THEME_STATUS,
            "menuStatus" to FID_MENU_STATUS,
            "theme" to FID_THEME,
            "center" to ClusterFrameUi7.FID_CENTER,
            "left" to ClusterFrameUi7.FID_LEFT,
        )

        private val WATCH_FIDS = listOf(
            "leftCover" to FID_LEFT_COVER,
            "rightCover" to FID_RIGHT_COVER,
            "themeStatus" to FID_THEME_STATUS,
            "menuStatus" to FID_MENU_STATUS,
            "center" to ClusterFrameUi7.FID_CENTER,
        )

        private val DICAR_GETTERS: List<Pair<String, Pair<Int, Int?>>> = listOf(
            "menuVersion" to (DiCarProviderClient.TX_GET_MENU_VERSION to null),
            "config4" to (DiCarProviderClient.TX_GET_INSTRUMENT_CONFIG to 4),
            "menuType" to (DiCarProviderClient.TX_GET_MENU_TYPE to null),
            "menuVisible" to (DiCarProviderClient.TX_IS_MENU_VISIBLE to null),
            "navType" to (DiCarProviderClient.TX_GET_NAVIGATION_TYPE to null),
            "theme" to (DiCarProviderClient.TX_GET_INSTRUMENT_THEME to null),
        )

        /** `what` → (paired getter tx, setter tx). */
        private val DICAR_SETTERS = mapOf(
            "menu_visible" to
                (DiCarProviderClient.TX_IS_MENU_VISIBLE to DiCarProviderClient.TX_SET_MENU_VISIBLE),
            "nav_type" to
                (DiCarProviderClient.TX_GET_NAVIGATION_TYPE to DiCarProviderClient.TX_SET_NAVIGATION_TYPE),
            "theme" to
                (DiCarProviderClient.TX_GET_INSTRUMENT_THEME to DiCarProviderClient.TX_SET_INSTRUMENT_THEME),
        )

        internal const val PREFS_NAME = "cluster_probe"
        internal const val KEY_SAVED_RIGHT_MENU = "probe_saved_right_menu_json"

        @Volatile
        private var instance: ClusterProbeRunner? = null

        fun shared(context: Context): ClusterProbeRunner =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    ClusterProbeRunner(
                        helper = HelperClientImpl(),
                        journal = { ClusterJournal.shared(app).append(it) },
                        platformized = { Split37Engine.isPlatformizedFirmware() },
                        menu = MenuServiceClient(app),
                        dicar = DiCarProviderClient(app),
                        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    ).also { instance = it }
                }
            }
    }
}
