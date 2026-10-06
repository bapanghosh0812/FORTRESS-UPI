package com.fortress.upi.core.offline.protocol

import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.FceException
import com.fortress.upi.core.crypto.P256
import com.fortress.upi.core.crypto.constantTimeEquals
import com.fortress.upi.core.crypto.toHex
import java.security.GeneralSecurityException

class VerifiedBundle(val bundle: PaymentBundle, val noteIds: List<String>, val walletId: ByteArray)

/**
 * Payee-side verification of an incoming bundle — the same invariants the
 * settlement engine enforces (services/offline-mesh/internal/domain), applied
 * before the payee shows "Received". Clock checks here are advisory; the server
 * re-checks expiry at settlement with a trusted clock.
 */
class BundleVerifier(
    private val trust: IssuerTrustStore,
    private val clock: Clock,
    private val minEnforcement: Int = NoteCertificate.ENFORCEMENT_TEE,
) {
    fun verify(
        bundleBytes: ByteArray,
        expectedReceiverId: ByteArray,
        expectedTranscript: ByteArray,
        requestedAmountPaise: Long,
    ): VerifiedBundle {
        val bundle = try {
            PaymentBundle.decode(bundleBytes)
        } catch (e: FceException) {
            throw OfflineProtocolException(Reason.MALFORMED, e)
        }
        if (!bundle.receiverId.constantTimeEquals(expectedReceiverId)) throw OfflineProtocolException(Reason.WRONG_RECEIVER)
        if (!bundle.transcript.constantTimeEquals(expectedTranscript)) throw OfflineProtocolException(Reason.TRANSCRIPT_MISMATCH)
        if (bundle.totalPaise <= 0 || (requestedAmountPaise > 0 && bundle.totalPaise != requestedAmountPaise)) {
            throw OfflineProtocolException(Reason.AMOUNT)
        }

        val nowSec = clock.nowMillis() / 1000
        val seen = LinkedHashSet<String>()
        var wallet: ByteArray? = null
        var sum = 0L
        for (spend in bundle.spends) {
            val cert = trust.verifyNote(spend.signedNoteCert)
            if (!seen.add(cert.noteId.toHex())) throw OfflineProtocolException(Reason.DUPLICATE_NOTE)
            val w = wallet
            if (w == null) wallet = cert.walletId else if (!w.constantTimeEquals(cert.walletId)) throw OfflineProtocolException(Reason.MIXED_WALLETS)
            if (cert.enforcement < minEnforcement) throw OfflineProtocolException(Reason.ENFORCEMENT_POLICY)
            if (nowSec > cert.expiresAtSec) throw OfflineProtocolException(Reason.EXPIRED)
            if (spend.appliedPaise <= 0 || spend.appliedPaise > cert.denominationPaise) throw OfflineProtocolException(Reason.AMOUNT)
            sum = try {
                Math.addExact(sum, spend.appliedPaise)
            } catch (e: ArithmeticException) {
                throw OfflineProtocolException(Reason.AMOUNT, e)
            }

            val body = spendSigBody(
                cert.noteId, bundle.paymentId, bundle.receiverId, bundle.transcript,
                spend.appliedPaise, bundle.totalPaise, bundle.createdAtMillis,
            )
            val notePub = try {
                P256.publicKeyFromSpki(cert.notePublicKey)
            } catch (e: GeneralSecurityException) {
                throw OfflineProtocolException(Reason.MALFORMED, e)
            }
            if (!P256.verify(notePub, Domains.SPEND, body, spend.signature)) throw OfflineProtocolException(Reason.BAD_SPEND_SIGNATURE)
        }
        if (sum != bundle.totalPaise) throw OfflineProtocolException(Reason.AMOUNT)
        return VerifiedBundle(bundle, seen.toList(), checkNotNull(wallet))
    }
}
