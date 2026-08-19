package com.bydmate.app.cluster

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.CMD_DICAR_READ
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.CMD_DICAR_SET
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.CMD_MENU_CLEAR
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.CMD_MENU_RESTORE
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.CMD_MENU_SET
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.CMD_READ
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.CMD_WATCH
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.FID_RIGHT_COVER
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.HOLD_DEFAULT_SEC
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.HOLD_MAX_SEC
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.KEY_SAVED_RIGHT_MENU
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.PREFS_NAME
import com.bydmate.app.cluster.ClusterProbeRunner.Companion.WATCH_MAX_SEC
import com.bydmate.app.cluster.ClusterProbeRunner.ProbeCommand
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The probe writes to the cluster of a car that is being driven, so the two things worth asserting
 * are that a pre-OTA car sees nothing at all and that every write is followed by its restore — the
 * hold path, the exception path and the unreadable-saved-state path alike. The wire decoders are
 * checked against hand-built Parcels, because the answer that matters on the car (permission code
 * 20004) is exactly the one we cannot get here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterProbeRunnerTest {

    // getClusterMenu(2) as the firmware answers it: two cards selected out of the catalogue.
    private val savedRightMenu = """
        {"cluster_menu_info":{"right_menu":{
          "all_items":[{"id":"clock_info","name":"Clock","type":10},
                       {"id":"tire_info","name":"Tires","type":1}],
          "selected":{"min":1,"max":4,"all_selected":["clock_info","tire_info"]}}}}
    """.trimIndent()

    private val helper = mockk<HelperClient>(relaxed = true)
    private val menu = FakeMenuClient()
    private val dicar = FakeDiCarClient()
    private val journal = mutableListOf<String>()
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        coEvery { helper.read(any(), any(), any()) } returns 1L
    }

    private fun runner(platformized: Boolean = true) = ClusterProbeRunner(
        helper = helper,
        journal = { journal += it },
        platformized = { platformized },
        menu = menu,
        dicar = dicar,
        prefs = prefs,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun command(
        cmd: String,
        holdSec: Int = 1,
        watchSec: Int = 1,
        items: List<String> = emptyList(),
        what: String? = null,
        value: Int = 0,
    ) = ProbeCommand(cmd, holdSec, watchSec, items, what, value)

    @Test
    fun `pre-OTA firmware is not touched`() = runTest {
        runner(platformized = false).run(command(CMD_MENU_CLEAR))

        assertEquals(listOf("probe: not platformized"), journal)
        assertEquals(0, menu.sets.size)
        assertEquals(0, menu.gets.size)
        coVerify(exactly = 0) { helper.read(any(), any(), any()) }
    }

    @Test
    fun `unknown command is reported and does nothing`() = runTest {
        runner().run(command("menu_wipe"))

        assertEquals(listOf("probe: unknown cmd menu_wipe"), journal)
        assertEquals(0, menu.sets.size)
    }

    @Test
    fun `a second command while one runs is refused`() = runTest {
        val probe = runner()
        menu.blockNextGet = CompletableDeferred()
        val first = launch { probe.run(command(CMD_MENU_CLEAR)) }
        runCurrent()

        probe.run(command(CMD_READ))
        assertTrue(journal.contains("probe: busy"))

        menu.blockNextGet?.complete(Unit)
        first.join()
    }

    @Test
    fun `read reports the fids and all three menu sides without writing`() = runTest {
        menu.getAnswer = savedRightMenu
        runner().run(command(CMD_READ))

        assertTrue(journal.first().startsWith("probe read: leftCover=1 rightCover=1"))
        assertEquals(listOf(0, 1, 2), menu.gets)
        assertEquals(0, menu.sets.size)
    }

    @Test
    fun `menu_clear saves the menu, clears it and restores the saved selection`() = runTest {
        menu.getAnswer = savedRightMenu
        coEvery { helper.read(any(), FID_RIGHT_COVER, any()) } returnsMany listOf(1L, 2L, 2L)

        runner().run(command(CMD_MENU_CLEAR, holdSec = 20))

        assertEquals(savedRightMenu, prefs.getString(KEY_SAVED_RIGHT_MENU, null))
        assertEquals(
            listOf(
                listOf(MENU_UNCHANGED) to listOf(MENU_ZERO_SELECTED),
                listOf(MENU_UNCHANGED) to listOf("clock_info", "tire_info"),
            ),
            menu.sets,
        )
        assertTrue(journal.any { it == "probe menu_clear: rc=0 right 1->2 hold=20s" })
        assertTrue(journal.any { it == "probe restore: items=clock_info+tire_info rc=0" })
    }

    @Test
    fun `menu_set puts the requested cards on the right side`() = runTest {
        menu.getAnswer = savedRightMenu
        runner().run(command(CMD_MENU_SET, items = listOf("clock_info")))

        assertEquals(listOf(MENU_UNCHANGED) to listOf("clock_info"), menu.sets.first())
    }

    @Test
    fun `menu_set without items writes nothing`() = runTest {
        runner().run(command(CMD_MENU_SET))

        assertEquals(listOf("probe menu_set: no items"), journal)
        assertEquals(0, menu.sets.size)
    }

    @Test
    fun `a menu that cannot be read back is not written`() = runTest {
        menu.getAnswer = null

        runner().run(command(CMD_MENU_CLEAR))

        assertEquals(listOf("probe menu_clear: right menu unreadable (-1 chars), skipped"), journal)
        assertEquals(0, menu.sets.size)
    }

    @Test
    fun `a failed write is still followed by the restore`() = runTest {
        menu.getAnswer = savedRightMenu
        menu.throwOnSet = 1

        runner().run(command(CMD_MENU_CLEAR))

        assertEquals(2, menu.sets.size)
        assertEquals(listOf(MENU_UNCHANGED) to listOf("clock_info", "tire_info"), menu.sets.last())
        assertTrue(journal.any { it == "probe menu_clear: exception IllegalStateException" })
    }

    @Test
    fun `an empty saved selection restores as zeroselected`() = runTest {
        prefs.edit().putString(
            KEY_SAVED_RIGHT_MENU,
            """{"cluster_menu_info":{"right_menu":{"selected":{"all_selected":[]}}}}""",
        ).commit()

        runner().run(command(CMD_MENU_RESTORE))

        assertEquals(listOf(MENU_UNCHANGED) to listOf(MENU_ZERO_SELECTED), menu.sets.single())
    }

    @Test
    fun `an unreadable saved selection leaves the menu alone`() = runTest {
        prefs.edit().putString(KEY_SAVED_RIGHT_MENU, "not json at all").commit()

        runner().run(command(CMD_MENU_RESTORE))

        assertEquals(listOf(MENU_UNCHANGED) to listOf(MENU_UNCHANGED), menu.sets.single())
        assertEquals(listOf("probe restore: unparsed, left as is"), journal)
    }

    @Test
    fun `watch journals transitions only`() = runTest {
        coEvery { helper.read(any(), FID_RIGHT_COVER, any()) } returnsMany listOf(1L, 2L)

        runner().run(command(CMD_WATCH, watchSec = 1))

        assertEquals(
            listOf("probe watch: start 1s", "probe watch: rightCover 1->2", "probe watch: done, 1 changes"),
            journal,
        )
    }

    @Test
    fun `dicar_read reports every getter answer`() = runTest {
        dicar.results[DiCarProviderClient.TX_GET_MENU_VERSION] = DiCarResult(0, null, 3.0)
        dicar.results[DiCarProviderClient.TX_IS_MENU_VISIBLE] = DiCarResult(20004, "no perm", null)

        runner().run(command(CMD_DICAR_READ))

        assertEquals(
            "probe dicar: menuVersion=3.0 config4=noanswer menuType=noanswer " +
                "menuVisible=err20004 navType=noanswer theme=noanswer",
            journal.single(),
        )
        assertEquals(DiCarProviderClient.TX_GET_INSTRUMENT_CONFIG to 4, dicar.resultCalls[1])
    }

    @Test
    fun `dicar_set does not write when the paired getter refuses`() = runTest {
        dicar.results[DiCarProviderClient.TX_IS_MENU_VISIBLE] = DiCarResult(20004, "no perm", null)

        runner().run(command(CMD_DICAR_SET, what = "menu_visible", value = 0))

        assertEquals(listOf("probe dicar_set: menu_visible getter=err20004, skipped"), journal)
        assertEquals(0, dicar.statusCalls.size)
    }

    @Test
    fun `dicar_set writes and puts the read-back value back after the hold`() = runTest {
        dicar.results[DiCarProviderClient.TX_IS_MENU_VISIBLE] = DiCarResult(0, null, true)

        runner().run(command(CMD_DICAR_SET, what = "menu_visible", value = 0, holdSec = 30))

        assertEquals(
            listOf(
                DiCarProviderClient.TX_SET_MENU_VISIBLE to 0,
                DiCarProviderClient.TX_SET_MENU_VISIBLE to 1,
            ),
            dicar.statusCalls,
        )
        assertTrue(journal.any { it == "probe dicar_set: menu_visible restored=1 rc=ok" })
    }

    @Test
    fun `dicar_set with an unknown target does nothing`() = runTest {
        runner().run(command(CMD_DICAR_SET, what = "brightness", value = 1))

        assertEquals(listOf("probe dicar_set: unknown what=brightness"), journal)
        assertEquals(0, dicar.statusCalls.size)
    }

    @Test
    fun `parse applies defaults and limits`() {
        val probe = runner()

        val bare = probe.parse(Intent().putExtra("cmd", " read "))
        assertEquals(CMD_READ, bare.cmd)
        assertEquals(HOLD_DEFAULT_SEC, bare.holdSec)
        assertEquals(emptyList<String>(), bare.items)

        val loud = probe.parse(
            Intent()
                .putExtra("cmd", CMD_MENU_SET)
                .putExtra("hold", 9000)
                .putExtra("secs", 9000)
                .putExtra("items", "clock_info, tire_info,,")
                .putExtra("what", "nav_type")
                .putExtra("v", 2)
        )
        assertEquals(HOLD_MAX_SEC, loud.holdSec)
        assertEquals(WATCH_MAX_SEC, loud.watchSec)
        assertEquals(listOf("clock_info", "tire_info"), loud.items)
        assertEquals("nav_type", loud.what)
        assertEquals(2, loud.value)
    }

    @Test
    fun `selected cards are read out of the firmware json`() {
        assertEquals(listOf("clock_info", "tire_info"), parseSelectedRightMenu(savedRightMenu))
        assertEquals(
            emptyList<String>(),
            parseSelectedRightMenu("""{"right_menu":{"selected":{"all_selected":[]}}}"""),
        )
        assertNull(parseSelectedRightMenu(""))
        assertNull(parseSelectedRightMenu("{}"))
        assertNull(parseSelectedRightMenu("not json"))
    }

    @Test
    fun `result decodes the typed payloads and the permission refusal`() {
        assertEquals(
            DiCarResult(0, "ok", 42),
            decodeResult(result(0, "ok", "java.lang.Integer") { it.writeInt(42) }),
        )
        assertEquals(
            DiCarResult(0, null, 3.0),
            decodeResult(result(0, null, "java.lang.Double") { it.writeDouble(3.0) }),
        )
        assertEquals(
            DiCarResult(0, null, true),
            decodeResult(result(0, null, "java.lang.Boolean") { it.writeInt(1) }),
        )
        assertEquals(
            DiCarResult(20004, "ERR_PERMISSION_GRANTED", null),
            decodeResult(result(20004, "ERR_PERMISSION_GRANTED", "java.lang.Void")),
        )
    }

    @Test
    fun `a null answer is not a value`() {
        val parcel = Parcel.obtain()
        parcel.writeInt(0) // no exception
        parcel.writeInt(0) // null parcelable
        parcel.setDataPosition(0)

        assertNull(decodeResult(parcel))
        parcel.setDataPosition(0)
        assertNull(decodeStatus(parcel))
    }

    @Test
    fun `status decodes code and message`() {
        val parcel = Parcel.obtain()
        parcel.writeInt(0)
        parcel.writeInt(1)
        parcel.writeInt(20004)
        parcel.writeString("no perm")
        parcel.setDataPosition(0)

        assertEquals(DiCarResult(20004, "no perm", null), decodeStatus(parcel))
    }

    /** A `Result<T>` reply as the DiCar server writes it, positioned for reading. */
    private fun result(
        code: Int,
        message: String?,
        className: String,
        payload: (Parcel) -> Unit = {},
    ): Parcel = Parcel.obtain().apply {
        writeInt(0) // no exception
        writeInt(1) // non-null parcelable
        writeInt(code)
        writeString(message)
        writeString(className)
        payload(this)
        setDataPosition(0)
    }

    private class FakeMenuClient : ClusterMenuClient {
        var getAnswer: String? = null
        var rc: Int? = 0
        var throwOnSet = 0
        var blockNextGet: CompletableDeferred<Unit>? = null
        val gets = mutableListOf<Int>()
        val sets = mutableListOf<Pair<List<String>, List<String>>>()

        override suspend fun getClusterMenu(side: Int): String? {
            gets += side
            blockNextGet?.let { it.await(); blockNextGet = null }
            return getAnswer
        }

        override suspend fun setClusterMenu(left: Array<String>, right: Array<String>): Int? {
            sets += left.toList() to right.toList()
            if (throwOnSet > 0) {
                throwOnSet--
                throw IllegalStateException("menu service is gone")
            }
            return rc
        }
    }

    private class FakeDiCarClient : DiCarInstrumentClient {
        val results = mutableMapOf<Int, DiCarResult>()
        var statusAnswer: DiCarResult? = DiCarResult(0, null, null)
        val resultCalls = mutableListOf<Pair<Int, Int?>>()
        val statusCalls = mutableListOf<Pair<Int, Int>>()

        override suspend fun result(tx: Int, arg: Int?): DiCarResult? {
            resultCalls += tx to arg
            return results[tx]
        }

        override suspend fun status(tx: Int, arg: Int): DiCarResult? {
            statusCalls += tx to arg
            return statusAnswer
        }
    }
}
