package com.bydmate.app.data.autoservice

import android.annotation.SuppressLint
import java.net.Socket
import java.security.KeyPair
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS layer for the STLS variant of the ADB handshake (wireless debugging).
 *
 * Both sides are self-signed and neither validates a chain: adbd authorizes the
 * client by comparing the certificate's public key against `/data/misc/adb/adb_keys`,
 * so the trust manager here accepts whatever adbd presents — the authentication is
 * the key, not the certificate issuer. Anything else would reject the daemon on
 * every car.
 */
internal object AdbTls {

    private const val ALIAS = "bydmate"

    /**
     * Wraps an already-connected [plain] socket in TLS and completes the handshake.
     * The returned socket owns [plain]: closing it closes the underlying connection.
     */
    fun upgrade(plain: Socket, keyPair: KeyPair, certificate: X509Certificate): Socket {
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(
            arrayOf(keyManager(keyPair.private, certificate)),
            arrayOf(trustManager()),
            SecureRandom(),
        )
        val tls = context.socketFactory.createSocket(
            plain, plain.inetAddress.hostAddress, plain.port, true
        ) as SSLSocket
        tls.startHandshake()
        return tls
    }

    private fun keyManager(privateKey: PrivateKey, certificate: X509Certificate) =
        object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?
            ): String? = if (keyTypes?.any { it == "RSA" } == true) ALIAS else null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == ALIAS) privateKey else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(
                keyType: String?, issuers: Array<out Principal>?, socket: Socket?
            ): String? = null
        }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun trustManager() = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
