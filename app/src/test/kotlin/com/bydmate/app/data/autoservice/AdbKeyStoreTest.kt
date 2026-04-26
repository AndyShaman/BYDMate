package com.bydmate.app.data.autoservice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdbKeyStoreTest {

    private lateinit var context: Context
    private lateinit var keyDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyDir = File(context.filesDir, "adb_keys")
        if (keyDir.exists()) keyDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `loadOrGenerate firstCall generates and persists keypair`() {
        val store = AdbKeyStore(context)

        val pair = store.loadOrGenerate()

        assertNotNull(pair.public)
        assertNotNull(pair.private)
        assertTrue(pair.public is RSAPublicKey)
        assertTrue(pair.private is RSAPrivateKey)
        assertEquals(2048, (pair.public as RSAPublicKey).modulus.bitLength())
        assertTrue(File(keyDir, "adb_key.priv").exists())
        assertTrue(File(keyDir, "adb_key.pub").exists())
    }

    @Test
    fun `loadOrGenerate secondCall returnsSameKeypair`() {
        val store1 = AdbKeyStore(context)
        val first = store1.loadOrGenerate()

        // Fresh instance — must not depend on in-memory cache.
        val store2 = AdbKeyStore(context)
        val second = store2.loadOrGenerate()

        assertArrayEquals(first.public.encoded, second.public.encoded)
        assertArrayEquals(first.private.encoded, second.private.encoded)
    }

    @Test
    fun `loadOrGenerate corruptedPrivateFile regenerates`() {
        val store1 = AdbKeyStore(context)
        val first = store1.loadOrGenerate()

        // Corrupt private file — must trigger regeneration on next call.
        File(keyDir, "adb_key.priv").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val store2 = AdbKeyStore(context)
        val second = store2.loadOrGenerate()

        // New keypair, files restored to valid state.
        assertNotNull(second)
        assertTrue(File(keyDir, "adb_key.priv").exists())
        assertTrue(File(keyDir, "adb_key.pub").exists())
        assertEquals(2048, (second.public as RSAPublicKey).modulus.bitLength())
        // The new key is different from the original (regeneration, not recovery).
        assertTrue(!first.public.encoded.contentEquals(second.public.encoded))
    }

    @Test
    fun `getFingerprint isStable across instances`() {
        val store1 = AdbKeyStore(context)
        val fp1 = store1.getFingerprint()

        val store2 = AdbKeyStore(context)
        val fp2 = store2.getFingerprint()

        assertEquals(fp1, fp2)
        // SHA-1 is 20 bytes → 40 hex chars
        assertEquals(40, fp1.length)
    }
}
