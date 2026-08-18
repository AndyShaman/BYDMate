package com.bydmate.app.media

import com.bydmate.app.media.KnobPlayPause.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnobPlayPauseTest {

    // PlaybackState.STATE_* values as literals — the pure logic is JVM-tested.
    private val none = 0
    private val stopped = 1
    private val paused = 2
    private val playing = 3

    @Test fun `no active session means nothing to send`() {
        assertNull(KnobPlayPause.pickTarget(emptyList()))
    }

    @Test fun `a playing session wins over a paused one`() {
        val target = KnobPlayPause.pickTarget(
            listOf(
                SessionSnapshot("com.byd.mediacenter", paused),
                SessionSnapshot("ru.yandex.music", playing),
            )
        )
        assertEquals(1, target)
    }

    @Test fun `playing session is picked wherever it sits in the list`() {
        val target = KnobPlayPause.pickTarget(
            listOf(
                SessionSnapshot("a", none),
                SessionSnapshot("b", null),
                SessionSnapshot("c", playing),
                SessionSnapshot("d", paused),
            )
        )
        assertEquals(2, target)
    }

    @Test fun `without a playing session the first one with a state wins`() {
        val target = KnobPlayPause.pickTarget(
            listOf(
                SessionSnapshot("a", none),
                SessionSnapshot("b", null),
                SessionSnapshot("c", stopped),
            )
        )
        assertEquals(2, target)
    }

    @Test fun `all stateless falls back to the highest-priority session`() {
        val target = KnobPlayPause.pickTarget(
            listOf(
                SessionSnapshot("a", null),
                SessionSnapshot("b", none),
            )
        )
        assertEquals(0, target)
    }
}
