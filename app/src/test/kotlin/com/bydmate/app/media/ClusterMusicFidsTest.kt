package com.bydmate.app.media

import com.bydmate.app.data.nativestack.FidCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// Review point 1: addresses come from the unit's own catalog, never a CAN-FD constant.
class ClusterMusicFidsTest {

    private val devices = mapOf("INSTRUMENT" to 1007, "AUDIO" to 1002)

    private fun catalog(symbols: Map<String, Int>, devs: Map<String, Int> = devices) = FidCatalog(symbols, devs)

    private val canFd = mapOf(
        ClusterMusicFids.INFO to 1140527112,
        ClusterMusicFids.STATE to 1138753546,
        ClusterMusicFids.SOURCE to 871366704,
        ClusterMusicFids.PROGRESS to 1138753552,
        ClusterMusicFids.SINGER to 1140396040,
    ) + ClusterMusicFids.PLAY_TIME.zip(listOf(1309671432, 1309671440, 1309671448)) +
        ClusterMusicFids.TOTAL_TIME.zip(listOf(1309671456, 1309671464, 1309671472))

    @Test fun `can-fd unit resolves every fid from its catalog`() {
        val fids = ClusterMusicFids.resolve(catalog(canFd))!!
        assertEquals(871366704, fids.source)
        assertEquals(1007, fids.instrumentDev)
        assertEquals(1140396040, fids.singer)
        assertEquals(listOf(1309671432, 1309671440, 1309671448), fids.playTime)
    }

    @Test fun `dilink 3 and 4 get their own source address`() {
        val fids = ClusterMusicFids.resolve(catalog(canFd + (ClusterMusicFids.SOURCE to 1138753584)))!!
        assertEquals(1138753584, fids.source)
    }

    @Test fun `song plus without the singer fid still resolves, singer skipped`() {
        val fids = ClusterMusicFids.resolve(catalog(canFd - ClusterMusicFids.SINGER))!!
        assertNull(fids.singer)
        assertNotNull(fids.info)
    }

    @Test fun `a missing required fid switches the card off`() {
        assertNull(ClusterMusicFids.resolve(catalog(canFd - ClusterMusicFids.SOURCE)))
        assertNull(ClusterMusicFids.resolve(catalog(canFd - ClusterMusicFids.INFO)))
        assertNull(ClusterMusicFids.resolve(catalog(canFd - ClusterMusicFids.STATE)))
        assertEquals(listOf(ClusterMusicFids.SOURCE), ClusterMusicFids.missing(catalog(canFd - ClusterMusicFids.SOURCE)))
    }

    @Test fun `no catalog yet means no fids`() {
        assertNull(ClusterMusicFids.resolve(null))
        assertEquals(listOf("catalog"), ClusterMusicFids.missing(null))
    }

    @Test fun `no instrument device means no fids`() {
        assertNull(ClusterMusicFids.resolve(catalog(canFd, mapOf("AUDIO" to 1002))))
    }

    @Test fun `no audio device drops singer and times, keeps the card`() {
        val fids = ClusterMusicFids.resolve(catalog(canFd, mapOf("INSTRUMENT" to 1007)))!!
        assertNull(fids.singer)
        assertNull(fids.playTime)
        assertNull(fids.totalTime)
    }

    @Test fun `a partial time triple is dropped`() {
        val fids = ClusterMusicFids.resolve(catalog(canFd - ClusterMusicFids.PLAY_TIME[1]))!!
        assertNull(fids.playTime)
        assertNotNull(fids.totalTime)
    }
}
