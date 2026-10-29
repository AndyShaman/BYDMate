package com.bydmate.app.data.dtc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException

class DtcDescriberTest {

    private val found = DtcFound(
        0x712, DtcRow("U1166-87", 0x09, DtcKind.NOW, 0x87), DtcBlockName(DtcBlock.ADAS, 0x712),
    )

    @Test fun `missing tables give no descriptions and no crash`() = runBlocking {
        val describer = DtcDescriber(
            open = { throw FileNotFoundException(it) },
            autoType = { 260 },
            energy = { DtcEnergy.EV },
            dispatcher = Dispatchers.Unconfined,
        )
        assertEquals(emptyMap<DtcFound, DtcDescription>(), describer.describe(listOf(found)))
        // the broken load is remembered, the second call does not throw either
        assertEquals(emptyMap<DtcFound, DtcDescription>(), describer.describe(listOf(found)))
    }
}
