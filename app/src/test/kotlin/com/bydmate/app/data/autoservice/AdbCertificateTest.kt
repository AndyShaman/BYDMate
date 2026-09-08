package com.bydmate.app.data.autoservice

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator

/**
 * The certificate that carries our ADB key into the TLS handshake, plus its on-disk cache.
 * Robolectric supplies the app context [AdbKeyStore] writes into.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdbCertificateTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `certificate is self-signed and carries the keypair public key`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val cert = AdbCertificate.forKeyPair(keyPair)

        assertArrayEquals(keyPair.public.encoded, cert.publicKey.encoded)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        // Self-signed: it verifies against its own key, and the subject is ours.
        cert.verify(keyPair.public)
        assertTrue(cert.subjectX500Principal.name.contains("CN=bydmate"))
    }

    @Test
    fun `certificate survives a DER round trip`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = AdbCertificate.forKeyPair(keyPair)

        val restored = AdbCertificate.fromDer(cert.encoded)

        assertArrayEquals(cert.encoded, restored.encoded)
    }

    @Test
    fun `keystore caches the certificate on disk and reuses it`() {
        val store = AdbKeyStore(context)

        val first = store.loadOrGenerateCertificate()
        val cached = File(context.filesDir, "adb_keys/adb_key.crt")
        assertTrue("certificate must be cached as DER", cached.exists())
        assertArrayEquals(first.encoded, cached.readBytes())

        // A fresh store instance (no in-memory cache) must read the same certificate back.
        val second = AdbKeyStore(context).loadOrGenerateCertificate()
        assertArrayEquals(first.encoded, second.encoded)
        assertArrayEquals(store.loadOrGenerate().public.encoded, second.publicKey.encoded)
    }

    @Test
    fun `a certificate that does not match the key is regenerated`() {
        val store = AdbKeyStore(context)
        val original = store.loadOrGenerateCertificate()

        // Someone else's certificate in our cache file (key rotation, corrupted copy).
        val foreign = AdbCertificate.forKeyPair(
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        )
        File(context.filesDir, "adb_keys/adb_key.crt").writeBytes(foreign.encoded)

        val rebuilt = AdbKeyStore(context).loadOrGenerateCertificate()

        assertArrayEquals(original.publicKey.encoded, rebuilt.publicKey.encoded)
    }
}
