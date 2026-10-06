package com.fortress.upi.core.offline.protocol

import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.FceException
import com.fortress.upi.core.crypto.P256
import com.fortress.upi.core.crypto.toHex
import java.security.GeneralSecurityException
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Issuer public keys pinned into the app at build time (`fortress.issuerKeys`).
 * The private halves live only in the issuer HSM. Rotation ships new keys in an
 * app update before the HSM starts signing with them (overlap window).
 */
class IssuerTrustStore(private val keys: Map<String, ECPublicKey>, private val clock: Clock) {

    fun isEmpty(): Boolean = keys.isEmpty()

    fun verifyNote(envelope: ByteArray): NoteCertificate = guard {
        val env = SignedEnvelope.decode(envelope)
        val cert = NoteCertificate.decodeBody(env.body)
        val key = keys[cert.issuerKeyId.toHex()] ?: throw OfflineProtocolException(Reason.UNKNOWN_ISSUER)
        if (!P256.verify(key, Domains.NOTE_CERT, env.body, env.signature)) {
            throw OfflineProtocolException(Reason.BAD_ISSUER_SIGNATURE)
        }
        cert
    }

    fun verifyReceiver(envelope: ByteArray): ReceiverCertificate = guard {
        val env = SignedEnvelope.decode(envelope)
        val cert = ReceiverCertificate.decodeBody(env.body)
        val key = keys[cert.issuerKeyId.toHex()] ?: throw OfflineProtocolException(Reason.UNKNOWN_ISSUER)
        if (!P256.verify(key, Domains.RECEIVER_CERT, env.body, env.signature)) {
            throw OfflineProtocolException(Reason.BAD_ISSUER_SIGNATURE)
        }
        val now = clock.nowMillis() / 1000
        if (now > cert.expiresAtSec + CLOCK_TOLERANCE_SEC || now + CLOCK_TOLERANCE_SEC < cert.issuedAtSec) {
            throw OfflineProtocolException(Reason.EXPIRED)
        }
        cert
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: FceException) {
        throw OfflineProtocolException(Reason.MALFORMED, e)
    } catch (e: GeneralSecurityException) {
        throw OfflineProtocolException(Reason.MALFORMED, e)
    }

    companion object {
        private const val CLOCK_TOLERANCE_SEC = 300L

        /** Parses `kidHex:base64Spki;kidHex:base64Spki`. */
        fun parse(spec: String, clock: Clock): IssuerTrustStore {
            val keys = spec.split(';').map(String::trim).filter(String::isNotEmpty).associate { entry ->
                val kid = entry.substringBefore(':', missingDelimiterValue = "")
                val b64 = entry.substringAfter(':', missingDelimiterValue = "")
                require(kid.isNotEmpty() && b64.isNotEmpty()) { "issuer key entry must be kid:base64spki" }
                kid.lowercase() to P256.publicKeyFromSpki(Base64.getDecoder().decode(b64))
            }
            return IssuerTrustStore(keys, clock)
        }
    }
}
