package com.bydmate.app.data.autoservice

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Builds the self-signed X.509 certificate that wraps our ADB keypair for the
 * TLS (STLS) handshake with `adbd`.
 *
 * adbd is self-signed too and never validates the chain: it authorizes the peer
 * by matching the certificate's public key against `/data/misc/adb/adb_keys`.
 * So the certificate is nothing but a carrier for the same RSA key the classic
 * AUTH path already uses — which is why a key that was approved once through
 * the «Allow USB debugging?» dialog needs no pairing code here.
 *
 * BouncyCastle is passed to the builders explicitly ([BouncyCastleProvider] instance,
 * never `Security.insertProviderAt`): Android ships a stripped-down
 * `com.android.org.bouncycastle` under the same algorithm names, and registering
 * ours globally would shadow it for the whole process.
 */
internal object AdbCertificate {

    private const val SUBJECT = "CN=bydmate"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val VALIDITY_MS = 20L * 365 * 24 * 60 * 60 * 1000  // 20 years

    /** Generates a fresh self-signed certificate for [keyPair]. */
    fun forKeyPair(keyPair: KeyPair): X509Certificate {
        val provider = BouncyCastleProvider()
        val notBefore = Date(0)
        val notAfter = Date(System.currentTimeMillis() + VALIDITY_MS)
        val name = X500Name(SUBJECT)
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            notBefore,
            notAfter,
            name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(
            JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(provider).build(keyPair.private)
        )
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
    }

    /** Parses a DER-encoded certificate (the on-disk cache format). */
    fun fromDer(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
}
