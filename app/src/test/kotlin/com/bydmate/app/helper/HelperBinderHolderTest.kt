package com.bydmate.app.helper

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H2 (#64/#148): the receiver that takes the daemon's Binder off a broadcast is exported — the
 * sender runs as the shell uid. Everything that keeps a forged intent out lives in the holder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperBinderHolderTest {

    /** Minimal IBinder that does not extend android.os.Binder (transact is final there).
     *  [descriptorReads] counts the descriptor transactions we would have made into the sender. */
    private open class FakeIBinder(
        private val descriptor: String = HelperBinderProtocol.DESCRIPTOR,
    ) : IBinder {
        var recipient: IBinder.DeathRecipient? = null
        var descriptorReads = 0
        override fun isBinderAlive(): Boolean = true
        override fun pingBinder(): Boolean = true
        override fun getInterfaceDescriptor(): String {
            descriptorReads++
            return descriptor
        }
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun transact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int) = false
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { this.recipient = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    private fun payload(binder: IBinder?, token: String?): Bundle = Bundle().apply {
        if (binder != null) putBinder(HelperBinderProtocol.KEY_BINDER, binder)
        if (token != null) putString(HelperBinderProtocol.KEY_TOKEN, token)
        putLong(HelperBinderProtocol.KEY_VERSION, 428L)
        putInt(HelperBinderProtocol.KEY_PID, 4242)
    }

    @Before @After
    fun reset() {
        HelperBinderHolder.clear()
        HelperBinderHolder.armToken(null)
        HelperBinderHolder.onAccepted = null
    }

    @Test
    fun `a binder matching the spawn token is held`() {
        HelperBinderHolder.armToken(TOKEN)
        val fake = FakeIBinder()

        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(fake, TOKEN)))
        assertSame(fake, HelperBinderHolder.binder)
        assertEquals(HelperBinderHolder.TRANSPORT_BROADCAST, HelperBinderHolder.transport)
        assertNull("a clean accept clears the previous reject", HelperBinderHolder.lastReject)
    }

    @Test
    fun `a foreign token is rejected`() {
        HelperBinderHolder.armToken(TOKEN)

        assertEquals(
            BinderAcceptResult.TOKEN_MISMATCH,
            HelperBinderHolder.accept(payload(FakeIBinder(), "deadbeefdeadbeefdeadbeefdeadbeef")),
        )
        assertNull(HelperBinderHolder.binder)
        assertEquals("token_mismatch", HelperBinderHolder.lastReject)
    }

    @Test
    fun `a binder that is not our daemon stub is rejected`() {
        HelperBinderHolder.armToken(TOKEN)

        assertEquals(
            BinderAcceptResult.DESCRIPTOR_MISMATCH,
            HelperBinderHolder.accept(payload(FakeIBinder("com.evil.IHelper"), TOKEN)),
        )
        assertNull(HelperBinderHolder.binder)
        assertEquals("descriptor_mismatch", HelperBinderHolder.lastReject)
    }

    @Test
    fun `nothing is accepted while no spawn is in flight`() {
        // expectedToken stays null: no spawn of ours is waiting for a binder.
        assertEquals(
            BinderAcceptResult.NOT_EXPECTED,
            HelperBinderHolder.accept(payload(FakeIBinder(), TOKEN)),
        )
        assertNull(HelperBinderHolder.binder)
    }

    @Test
    fun `an intent without a binder is rejected`() {
        HelperBinderHolder.armToken(TOKEN)

        assertEquals(BinderAcceptResult.NO_BINDER, HelperBinderHolder.accept(payload(null, TOKEN)))
        assertEquals(BinderAcceptResult.NO_BINDER, HelperBinderHolder.accept(null))
    }

    @Test
    fun `daemon death clears the held binder`() {
        HelperBinderHolder.armToken(TOKEN)
        val fake = FakeIBinder()
        HelperBinderHolder.accept(payload(fake, TOKEN))

        // There is no service name behind a broadcast-delivered binder, so nothing but the
        // death recipient can invalidate it.
        fake.recipient!!.binderDied()

        assertNull(HelperBinderHolder.binder)
        assertEquals(HelperBinderHolder.TRANSPORT_NONE, HelperBinderHolder.transport)
        assertEquals(0L, HelperBinderHolder.receivedAt)
    }

    @Test
    fun `decision order puts the cheapest checks first`() {
        assertEquals(
            BinderAcceptResult.NO_BINDER,
            decideBinderAccept(hasBinder = false, token = TOKEN, expectedToken = TOKEN),
        )
        assertEquals(
            BinderAcceptResult.NOT_EXPECTED,
            decideBinderAccept(hasBinder = true, token = TOKEN, expectedToken = null),
        )
        assertEquals(
            BinderAcceptResult.TOKEN_MISMATCH,
            decideBinderAccept(hasBinder = true, token = "ffff", expectedToken = TOKEN),
        )
        // A matching token still loses to a binder we already hold — the token is no longer
        // consumed by an accept, so this is what keeps a replay out.
        assertEquals(
            BinderAcceptResult.ALREADY_HELD,
            decideBinderAccept(hasBinder = true, token = TOKEN, expectedToken = TOKEN, alreadyHolding = true),
        )
        // null = nothing left to decide from the intent alone; the descriptor step takes over.
        assertNull(decideBinderAccept(hasBinder = true, token = TOKEN, expectedToken = TOKEN))
        assertEquals(
            BinderAcceptResult.ACCEPTED,
            decideBinderDescriptor(HelperBinderProtocol.DESCRIPTOR),
        )
        // An unreadable descriptor (dead binder) must not pass as a match.
        assertEquals(BinderAcceptResult.DESCRIPTOR_MISMATCH, decideBinderDescriptor(null))
    }

    @Test
    fun `a rejected intent is never asked for its descriptor`() {
        // The descriptor read is a synchronous transaction into the sender's process, made on the
        // main thread by the manifest receiver: a hostile app that never answers would ANR us.
        // Nothing may touch the binder before the spawn token has matched.
        val whileIdle = FakeIBinder()
        assertEquals(BinderAcceptResult.NOT_EXPECTED, HelperBinderHolder.accept(payload(whileIdle, TOKEN)))
        assertEquals("no spawn in flight must not touch the binder", 0, whileIdle.descriptorReads)

        HelperBinderHolder.armToken(TOKEN)
        val foreign = FakeIBinder()
        assertEquals(
            BinderAcceptResult.TOKEN_MISMATCH,
            HelperBinderHolder.accept(payload(foreign, "deadbeefdeadbeefdeadbeefdeadbeef")),
        )
        assertEquals("a token mismatch must not touch the binder", 0, foreign.descriptorReads)

        val ours = FakeIBinder()
        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(ours, TOKEN)))
        assertEquals("a matching token is what earns the descriptor read", 1, ours.descriptorReads)
    }

    @Test
    fun `a late death notice from the previous daemon does not wipe its successor`() {
        // Respawn: A dies, B takes over, then A's death notice finally arrives. Clearing on it
        // would leave HelperClient with no binder while a healthy daemon is running.
        HelperBinderHolder.armToken(TOKEN)
        val first = FakeIBinder()
        HelperBinderHolder.accept(payload(first, TOKEN))
        HelperBinderHolder.clear()                      // the kill path: A is gone
        HelperBinderHolder.armToken(SECOND_TOKEN)
        val second = FakeIBinder()
        HelperBinderHolder.accept(payload(second, SECOND_TOKEN))

        first.recipient!!.binderDied()

        assertSame("the live daemon must survive its predecessor's death", second, HelperBinderHolder.binder)
        assertEquals(HelperBinderHolder.TRANSPORT_BROADCAST, HelperBinderHolder.transport)

        second.recipient!!.binderDied()

        assertNull("the held binder's own death still clears", HelperBinderHolder.binder)
        assertEquals(HelperBinderHolder.TRANSPORT_NONE, HelperBinderHolder.transport)
    }

    @Test
    fun `a held binder cannot be swapped by a replay of the same intent`() {
        // The token now outlives the accept (the daemon re-announces to every new app process),
        // so holding a binder is what makes the delivery single-use.
        HelperBinderHolder.armToken(TOKEN)
        val fake = FakeIBinder()
        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(fake, TOKEN)))
        assertEquals("the token stays armed for the next process", TOKEN, HelperBinderHolder.expectedToken)

        val replay = FakeIBinder()
        assertEquals(BinderAcceptResult.ALREADY_HELD, HelperBinderHolder.accept(payload(replay, TOKEN)))
        assertEquals("already_held", HelperBinderHolder.lastReject)
        assertSame("the accepted binder must stay", fake, HelperBinderHolder.binder)
        assertEquals("a rejected re-announce must not touch the binder", 0, replay.descriptorReads)
    }

    @Test
    fun `the same token is accepted again once the daemon binder is gone`() {
        // A recreated app process: the daemon is the same one, so it re-announces with the token
        // it was spawned with — and that must be adopted, not turned away.
        HelperBinderHolder.armToken(TOKEN)
        val first = FakeIBinder()
        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(first, TOKEN)))

        HelperBinderHolder.clear()                      // process restart / daemon death

        val reannounced = FakeIBinder()
        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(reannounced, TOKEN)))
        assertSame(reannounced, HelperBinderHolder.binder)
    }

    @Test
    fun `a new spawn token replaces the old one`() {
        HelperBinderHolder.armToken(TOKEN)
        HelperBinderHolder.accept(payload(FakeIBinder(), TOKEN))
        HelperBinderHolder.clear()
        HelperBinderHolder.armToken(SECOND_TOKEN)

        assertEquals(
            "the superseded token must not open the receiver any more",
            BinderAcceptResult.TOKEN_MISMATCH,
            HelperBinderHolder.accept(payload(FakeIBinder(), TOKEN)),
        )
        assertEquals(
            BinderAcceptResult.ACCEPTED,
            HelperBinderHolder.accept(payload(FakeIBinder(), SECOND_TOKEN)),
        )
    }

    @Test
    fun `the persisted spawn token is restored into a fresh process`() {
        val prefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences(HelperBinderHolder.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(HelperBinderHolder.KEY_SPAWN_TOKEN, TOKEN).apply()

        // Process restart: nothing is armed in memory, and without the restore the live daemon's
        // re-announce would be rejected as not_expected.
        HelperBinderHolder.armToken(null)
        HelperBinderHolder.restore(prefs)
        assertEquals(TOKEN, HelperBinderHolder.expectedToken)

        val reannounced = FakeIBinder()
        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(reannounced, TOKEN)))

        // A spawn in flight owns the holder: restore must not overwrite its token.
        HelperBinderHolder.clear()
        HelperBinderHolder.armToken(SECOND_TOKEN)
        HelperBinderHolder.restore(prefs)
        assertEquals(SECOND_TOKEN, HelperBinderHolder.expectedToken)
        prefs.edit().clear().apply()
    }

    @Test
    fun `the receiver persists the token of the daemon that handed us a binder`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            HelperBinderHolder.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        HelperBinderHolder.armToken(TOKEN)

        val intent = android.content.Intent(HelperBinderProtocol.ACTION_BINDER)
            .putExtra(HelperBinderProtocol.EXTRA_BUNDLE, payload(FakeIBinder(), TOKEN))
        HelperBinderReceiver().onReceive(context, intent)

        assertNotNull("the binder must be held", HelperBinderHolder.binder)
        assertEquals(TOKEN, prefs.getString(HelperBinderHolder.KEY_SPAWN_TOKEN, null))
        assertEquals(
            HelperBinderHolder.TRANSPORT_BROADCAST,
            prefs.getString(HelperBinderHolder.KEY_LAST_TRANSPORT, null),
        )
        prefs.edit().clear().apply()
    }

    @Test
    fun `a rejected intent persists nothing`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            HelperBinderHolder.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        HelperBinderHolder.armToken(TOKEN)

        val intent = android.content.Intent(HelperBinderProtocol.ACTION_BINDER)
            .putExtra(HelperBinderProtocol.EXTRA_BUNDLE, payload(FakeIBinder(), SECOND_TOKEN))
        HelperBinderReceiver().onReceive(context, intent)

        assertNull(prefs.getString(HelperBinderHolder.KEY_SPAWN_TOKEN, null))
        assertNull(prefs.getString(HelperBinderHolder.KEY_LAST_TRANSPORT, null))
    }

    @Test
    fun `a restore racing a fresh spawn leaves the new token in place`() {
        // The receiver thread restores from prefs while HelperBootstrap arms the next spawn on
        // Dispatchers.IO. A lost update here would make the new daemon's broadcast — and every
        // re-announce after it — fail with token_mismatch for the life of the process.
        val prefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences(HelperBinderHolder.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(HelperBinderHolder.KEY_SPAWN_TOKEN, TOKEN).apply()

        HelperBinderHolder.armToken(SECOND_TOKEN)
        HelperBinderHolder.restore(prefs)

        assertEquals("the spawn in flight owns the token", SECOND_TOKEN, HelperBinderHolder.expectedToken)
        assertEquals(
            BinderAcceptResult.ACCEPTED,
            HelperBinderHolder.accept(payload(FakeIBinder(), SECOND_TOKEN)),
        )
        prefs.edit().clear().apply()
    }

    @Test
    fun `onAccepted fires once a binder is accepted`() {
        // The binder arrival is the one signal every daemon spawn path shares, whoever called
        // ensureRunning() — that is what the fid-catalog retry hangs off.
        var fired = 0
        HelperBinderHolder.onAccepted = { fired++ }
        HelperBinderHolder.armToken(TOKEN)
        val fake = FakeIBinder()

        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(fake, TOKEN)))
        assertEquals(1, fired)
        assertSame(fake, HelperBinderHolder.binder)
    }

    @Test
    fun `installing the callback over a held binder fires it once`() {
        // A re-announce can start the process and be accepted before TrackingService exists.
        // Every later broadcast is then rejected as already_held, so installing the callback is
        // the last chance to register with the daemon — otherwise it broadcasts forever.
        HelperBinderHolder.armToken(TOKEN)
        HelperBinderHolder.accept(payload(FakeIBinder(), TOKEN))

        var fired = 0
        HelperBinderHolder.installOnAccepted { fired++ }

        assertEquals("a held binder must trigger the freshly installed callback", 1, fired)
    }

    @Test
    fun `installing the callback with an empty holder fires nothing`() {
        var fired = 0
        HelperBinderHolder.installOnAccepted { fired++ }
        assertEquals(0, fired)

        // Still wired up for the binder that has yet to arrive.
        HelperBinderHolder.armToken(TOKEN)
        HelperBinderHolder.accept(payload(FakeIBinder(), TOKEN))
        assertEquals(1, fired)
    }

    @Test
    fun `onAccepted stays silent on a rejected intent`() {
        var fired = 0
        HelperBinderHolder.onAccepted = { fired++ }
        HelperBinderHolder.armToken(TOKEN)

        assertEquals(
            BinderAcceptResult.TOKEN_MISMATCH,
            HelperBinderHolder.accept(payload(FakeIBinder(), "deadbeefdeadbeefdeadbeefdeadbeef")),
        )
        assertEquals(
            BinderAcceptResult.DESCRIPTOR_MISMATCH,
            HelperBinderHolder.accept(payload(FakeIBinder("com.evil.IHelper"), TOKEN)),
        )
        assertEquals("a rejected intent must not trigger the callback", 0, fired)
    }

    @Test
    fun `a throwing callback does not break acceptance`() {
        HelperBinderHolder.onAccepted = { error("callback blew up") }
        HelperBinderHolder.armToken(TOKEN)
        val fake = FakeIBinder()

        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(fake, TOKEN)))
        assertSame(fake, HelperBinderHolder.binder)
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val SECOND_TOKEN = "fedcba9876543210fedcba9876543210"
    }
}
