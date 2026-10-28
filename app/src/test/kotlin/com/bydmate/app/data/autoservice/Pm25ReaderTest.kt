package com.bydmate.app.data.autoservice

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Pm25ReaderTest {

    private val dev = 1008
    private val valueIn = 1331691536
    private val valueOut = 1331691548
    private val levelIn = 1331691568
    private val levelOut = 1331691571

    private val reads = mutableListOf<Int>()

    private fun reader(answers: Map<Int, Int?>): Pm25Reader {
        val client = mockk<AutoserviceClient>()
        coEvery { client.getInt(any(), any()) } answers {
            val fid = secondArg<Int>()
            reads += fid
            assertEquals(dev, firstArg<Int>())
            answers[fid]
        }
        return Pm25Reader(client)
    }

    @Test fun `no level answers - no sensor, only the two level fids read`() = runBlocking {
        val r = reader(emptyMap())
        assertNull(r.read())
        assertEquals(setOf(levelIn, levelOut), reads.toSet())
        assertEquals(2, reads.size)
    }

    @Test fun `both levels zero - no sensor, two reads only`() = runBlocking {
        val r = reader(mapOf(levelIn to 0, levelOut to 0))
        assertNull(r.read())
        assertEquals(setOf(levelIn, levelOut), reads.toSet())
        assertEquals(2, reads.size)
    }

    @Test fun `only the inside level valid - outside value is never read`() = runBlocking {
        val r = reader(mapOf(levelIn to 2, levelOut to 0, valueIn to 8))
        assertEquals(Pm25(inside = 8, outside = null, levelIn = 2, levelOut = null), r.read())
        assertEquals(false, valueOut in reads)
    }

    @Test fun `value above 3000 is dropped but the sensor still counts as present`() = runBlocking {
        val r = reader(mapOf(levelIn to 1, valueIn to 3001))
        val pm = r.read()
        assertNotNull(pm)
        assertNull(pm!!.inside)
    }

    @Test fun `level 7 is not a valid level`() = runBlocking {
        val r = reader(mapOf(levelIn to 7))
        assertNull(r.read())
    }

    @Test fun `both sensors present`() = runBlocking {
        val r = reader(mapOf(levelIn to 3, levelOut to 5, valueIn to 12, valueOut to 40))
        assertEquals(Pm25(12, 40, 3, 5), r.read())
    }
}
