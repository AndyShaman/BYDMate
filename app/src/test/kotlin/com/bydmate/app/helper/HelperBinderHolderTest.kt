package com.bydmate.app.helper

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import org.junit.After
import org.junit.Assert.assertEquals
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
        HelperBinderHolder.expectedToken = null
    }

    @Test
    fun `a binder matching the spawn token is held`() {
        HelperBinderHolder.expectedToken = TOKEN
        val fake = FakeIBinder()

        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(fake, TOKEN)))
        assertSame(fake, HelperBinderHolder.binder)
        assertEquals(HelperBinderHolder.TRANSPORT_BROADCAST, HelperBinderHolder.transport)
        assertNull("a clean accept clears the previous reject", HelperBinderHolder.lastReject)
    }

    @Test
    fun `a foreign token is rejected`() {
        HelperBinderHolder.expectedToken = TOKEN

        assertEquals(
            BinderAcceptResult.TOKEN_MISMATCH,
            HelperBinderHolder.accept(payload(FakeIBinder(), "deadbeefdeadbeefdeadbeefdeadbeef")),
        )
        assertNull(HelperBinderHolder.binder)
        assertEquals("token_mismatch", HelperBinderHolder.lastReject)
    }

    @Test
    fun `a binder that is not our daemon stub is rejected`() {
        HelperBinderHolder.expectedToken = TOKEN

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
        HelperBinderHolder.expectedToken = TOKEN

        assertEquals(BinderAcceptResult.NO_BINDER, HelperBinderHolder.accept(payload(null, TOKEN)))
        assertEquals(BinderAcceptResult.NO_BINDER, HelperBinderHolder.accept(null))
    }

    @Test
    fun `daemon death clears the held binder`() {
        HelperBinderHolder.expectedToken = TOKEN
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

        HelperBinderHolder.expectedToken = TOKEN
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
        // Respawn: A is replaced by B, then A's death notice finally arrives. Clearing on it
        // would leave HelperClient with no binder while a healthy daemon is running.
        HelperBinderHolder.expectedToken = TOKEN
        val first = FakeIBinder()
        HelperBinderHolder.accept(payload(first, TOKEN))
        HelperBinderHolder.expectedToken = SECOND_TOKEN
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
    fun `a spawn token is single-use`() {
        // One spawn, one binder: a replay of the accepted intent (or anyone who saw the token)
        // must not be able to swap the binder afterwards.
        HelperBinderHolder.expectedToken = TOKEN
        val fake = FakeIBinder()
        assertEquals(BinderAcceptResult.ACCEPTED, HelperBinderHolder.accept(payload(fake, TOKEN)))
        assertNull("the token is consumed by the accept", HelperBinderHolder.expectedToken)

        val replay = FakeIBinder()
        assertEquals(BinderAcceptResult.NOT_EXPECTED, HelperBinderHolder.accept(payload(replay, TOKEN)))
        assertEquals("not_expected", HelperBinderHolder.lastReject)
        assertSame("the accepted binder must stay", fake, HelperBinderHolder.binder)
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val SECOND_TOKEN = "fedcba9876543210fedcba9876543210"
    }
}
