package com.bydmate.app.data.nativestack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FidCatalogCacheTest {

    private val fingerprint = "BYD/DiLink5.0/trinket:12/SKQ1.230128.001/release-keys"

    @Test fun `a header written by this build on this firmware is current`() {
        val header = FidCatalogCache.header(fingerprint, 453, 14022)
        assertTrue(FidCatalogCache.isCurrent(header, fingerprint, 453))
        assertEquals(14022, FidCatalogCache.totalSymbols(header))
    }

    /** The file keeps only the symbols the app knows, so a newer build must re-read it. */
    @Test fun `a header from another app version is stale`() {
        val header = FidCatalogCache.header(fingerprint, 452, 14022)
        assertFalse(FidCatalogCache.isCurrent(header, fingerprint, 453))
    }

    @Test fun `a header from another firmware is stale`() {
        val header = FidCatalogCache.header("BYD/other:10/QKQ1/release-key", 453, 6000)
        assertFalse(FidCatalogCache.isCurrent(header, fingerprint, 453))
    }

    @Test fun `a header without the app key is stale`() {
        assertFalse(FidCatalogCache.isCurrent("fingerprint=$fingerprint symbols_total=10", fingerprint, 453))
    }

    @Test fun `a file that is not a catalog is stale`() {
        assertFalse(FidCatalogCache.isCurrent("Ac.AC_TEMP_MAIN=1", fingerprint, 453))
        assertFalse(FidCatalogCache.isCurrent("", fingerprint, 453))
        assertNull(FidCatalogCache.totalSymbols("Ac.AC_TEMP_MAIN=1"))
    }
}
