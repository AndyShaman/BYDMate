package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the BYDMate-side ADB RSA keypair under `filesDir/adb_keys/`.
 *
 * Format mirrors the competitor (BYD EV Pro): raw bytes — `PKCS#8` for the
 * private key, `X.509` (`SubjectPublicKeyInfo`) for the public key — same as
 * `Key.getEncoded()` returns for `KeyFactory.getInstance("RSA")`. This avoids
 * an extra serialization layer and lets us reuse `KeyFactory.generate*` directly.
 *
 * The keystore is intentionally NOT the Android Keystore: standard ADB pubkey
 * auth needs raw modulus/exponent in a non-standard 524-byte ADB layout
 * ([AdbProtocolClient]), which Android Keystore-protected keys cannot expose.
 */
@Singleton
class AdbKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val keyDir: File get() = File(context.filesDir, "adb_keys")
    private val privFile: File get() = File(keyDir, "adb_key.priv")
    private val pubFile: File get() = File(keyDir, "adb_key.pub")

    @Volatile private var cached: KeyPair? = null

    /**
     * Loads the persisted keypair if both files exist & parse, otherwise
     * generates a fresh 2048-bit RSA pair and writes it. On corrupted files
     * (parse failure) regenerates and overwrites — never throws to the caller
     * for a recoverable state.
     */
    @Synchronized
    fun loadOrGenerate(): KeyPair {
        cached?.let { return it }

        if (privFile.exists() && pubFile.exists()) {
            try {
                val factory = KeyFactory.getInstance("RSA")
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = factory.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                val pair = KeyPair(pub, priv)
                cached = pair
                return pair
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load saved keypair, regenerating: $e")
            }
        }

        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = gen.generateKeyPair()
        keyDir.mkdirs()
        privFile.writeBytes(pair.private.encoded)
        pubFile.writeBytes(pair.public.encoded)
        cached = pair
        return pair
    }

    /**
     * SHA-1 hex (uppercase) of the encoded public key bytes. For diagnostic
     * logs only — NOT the same fingerprint format the ADB daemon shows in the
     * "Allow USB debugging?" dialog (that one is computed from the 524-byte
     * ADB pubkey blob, not X.509).
     */
    fun getFingerprint(): String {
        val pair = loadOrGenerate()
        val digest = MessageDigest.getInstance("SHA-1").digest(pair.public.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    private companion object {
        const val TAG = "AdbKeyStore"
    }
}
