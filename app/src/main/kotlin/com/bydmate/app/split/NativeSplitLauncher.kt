package com.bydmate.app.split

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val TAG = "NativeSplitLauncher"

/**
 * Launches the HEAD UNIT's own split-screen for a [SplitPair], as an alternative to our freeform
 * layout (#139).
 *
 * On firmwares where `enable_freeform_support` is gated (DiLink 5.1 / Android 13) our split cannot
 * run at all, while the firmware's own one still works — so the pair is handed to the firmware and
 * nothing of ours (backdrop, watchdog, pill, session state) is involved. Plain app uid: only
 * startActivity and sendBroadcast, no helper daemon.
 *
 * Two mechanisms, chosen at runtime by [detectThirds]:
 *
 *  - **THIRDS** (DiLink 5.1 / DiLink 6): the proprietary `byd.intent.category.START_IVI_*`
 *    categories. `START_IVI_SECOND` puts the app in the 2/3 pane, `START_IVI_PRIMARY` in the 1/3
 *    pane; the wide app goes first, the narrow one after [THIRDS_GAP_MS] (the sequence BYD Picker
 *    Manager uses on Han L).
 *  - **TOGGLE_50_50** (DiLink 5.0, validated on the car 2026-07-27): launch the wide app, then
 *    broadcast `byd.intent.action.toggleSplitScreen`. The firmware halves the screen, the launched
 *    app takes the primary pane and the system's own app picker appears in the other one — so
 *    [SplitPair.narrowPkg] is deliberately NOT launched here, the user picks it in that picker.
 *
 * [SplitPair.narrowSide] is ignored in both paths: the firmware owns the layout and pane positions
 * are fixed. Ending the split is the user's job through the firmware's own divider buttons.
 *
 * Every failure is swallowed and reported as `false` plus a journal line — a field dump of the
 * split journal is the only diagnostic channel we have on someone else's car.
 */
class NativeSplitLauncher(
    private val context: Context,
    private val journal: SplitJournal = NoSplitJournal,
    /** Reads a system property; null when it is unset or unreadable. Injectable for tests. */
    private val systemProperty: (String) -> String? = ::readSystemProperty,
) {

    /**
     * Starts the firmware's split for [pair]. Returns true when the launch was dispatched — the
     * firmware owns the result, so there is nothing further to verify from app uid.
     */
    suspend fun launch(pair: SplitPair): Boolean {
        return try {
            val wideIntent = context.packageManager.getLaunchIntentForPackage(pair.widePkg)
                ?: return failed("no launch intent for ${pair.widePkg}")
            val thirds = detectThirds()
            if (thirds.supported) launchThirds(pair, wideIntent, thirds.evidence)
            else launchToggle(pair, wideIntent, thirds.evidence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun launchThirds(pair: SplitPair, wideIntent: Intent, evidence: String): Boolean {
        val narrowIntent = context.packageManager.getLaunchIntentForPackage(pair.narrowPkg)
            ?: return failed("no launch intent for ${pair.narrowPkg}")
        journal.append(
            "native split: mode=THIRDS wide=${pair.widePkg} narrow=${pair.narrowPkg} $evidence"
        )
        context.startActivity(wideIntent.addCategory(CATEGORY_TWO_THIRDS).addFlags(THIRDS_FLAGS))
        delay(THIRDS_GAP_MS)
        context.startActivity(narrowIntent.addCategory(CATEGORY_ONE_THIRD).addFlags(THIRDS_FLAGS))
        return true
    }

    private suspend fun launchToggle(pair: SplitPair, wideIntent: Intent, evidence: String): Boolean {
        journal.append("native split: mode=TOGGLE_50_50 wide=${pair.widePkg} $evidence")
        context.startActivity(wideIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(TOGGLE_GAP_MS)
        context.sendBroadcast(Intent(ACTION_TOGGLE_SPLIT))
        return true
    }

    private fun failed(reason: String): Boolean {
        Log.w(TAG, "native split failed: $reason")
        journal.append("native split failed: $reason")
        return false
    }

    /** Outcome of [detectThirds]: the decision plus the raw signals behind it, for the journal. */
    private class ThirdsSupport(val supported: Boolean, val evidence: String)

    /**
     * Decides whether this firmware can do 1/3 + 2/3 panes at all.
     *
     * Two independent signals, either of which is enough:
     *
     *  1. The firmware's own gate: `OneThirdSplitApiImpl` (三七分屏) enables the thirds API when
     *     [THIRDS_PROPS] read "1". Leopard 3 / DiLink 5.0 reads 0 with the second property absent
     *     (live probe 2026-07-27), so the 5.0 fleet lands on TOGGLE_50_50, which is what works there.
     *  2. Any activity on the device declaring the BYD split category in its manifest — an IMPLICIT
     *     query, because an intent with an explicit component (which is what
     *     `getLaunchIntentForPackage` returns) is resolved by PackageManager without matching
     *     categories at all and would answer "supported" on every firmware.
     *
     * Both signals are journalled on every launch, each with its RAW value — a property that reads
     * 0, one that does not exist, and a reflection call that did not go through are three different
     * firmware facts that all end in "not supported", and a dump from a car whose split came up in
     * the wrong mode has to tell them apart. Same for the query: no match and a PackageManager that
     * threw are not the same thing.
     */
    private fun detectThirds(): ThirdsSupport {
        val props = THIRDS_PROPS.map { it to systemProperty(it)?.trim() }
        val probe = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_ONE_THIRD)
        val filters = runCatching { context.packageManager.queryIntentActivities(probe, 0).size }
        val evidence = props.joinToString(" ") { (name, value) -> "prop[$name]=${value ?: "unset"}" } +
            " filters=" + filters.fold({ "$it" }, { "err(${it.javaClass.simpleName})" })
        return ThirdsSupport(
            supported = props.any { (_, value) -> value == "1" } || (filters.getOrNull() ?: 0) > 0,
            evidence = evidence,
        )
    }

    companion object {
        /** Category of the 1/3 pane (BYD Picker Manager on DiLink 5.1). */
        const val CATEGORY_ONE_THIRD = "byd.intent.category.START_IVI_PRIMARY"

        /** Category of the 2/3 pane. */
        const val CATEGORY_TWO_THIRDS = "byd.intent.category.START_IVI_SECOND"

        /** SystemUI listens for this on DiLink 5.0; a second one turns the split back off. */
        const val ACTION_TOGGLE_SPLIT = "byd.intent.action.toggleSplitScreen"

        /**
         * Flags OpenBYD uses for both thirds launches: NEW_TASK | MULTIPLE_TASK |
         * REORDER_TO_FRONT | NO_ANIMATION | RETAIN_IN_RECENTS | LAUNCH_ADJACENT.
         */
        const val THIRDS_FLAGS = 0x18033000

        /** Properties the firmware itself reads to decide whether thirds are available. */
        val THIRDS_PROPS = listOf("ro.byd.ui.splitscreen", "ro.build.baios.supportsplitscreen")

        /** Wide pane must be settled before the narrow one is launched into the layout. */
        const val THIRDS_GAP_MS = 450L

        /** The toggle must arrive after the wide app is on screen, or it splits the wrong task. */
        const val TOGGLE_GAP_MS = 600L
    }
}

/** Default [NativeSplitLauncher.systemProperty]: android.os.SystemProperties by reflection. */
internal fun readSystemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, name) as? String
}.getOrNull()?.takeIf { it.isNotEmpty() }
