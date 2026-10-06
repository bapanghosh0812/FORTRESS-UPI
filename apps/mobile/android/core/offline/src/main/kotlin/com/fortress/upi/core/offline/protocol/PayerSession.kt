package com.fortress.upi.core.offline.protocol

import com.fortress.upi.core.crypto.Direction
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.FceException
import com.fortress.upi.core.crypto.P256
import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.SessionKeys
import com.fortress.upi.core.crypto.constantTimeEquals
import com.fortress.upi.core.crypto.sha256
import com.fortress.upi.core.crypto.wipe
import com.fortress.upi.core.offline.transport.OfflineTransport
import java.security.GeneralSecurityException
import java.security.interfaces.ECPublicKey
import javax.security.auth.DestroyFailedException
import javax.security.auth.Destroyable

/** Result of HELLO/OFFER: a verified payee and an encrypted channel. */
class NegotiatedOffer internal constructor(
    val receiver: ReceiverCertificate,
    /** 0 = payer chooses the amount (P2P); otherwise the payee's requested amount. */
    val requestedAmountPaise: Long,
    val transcript: ByteArray,
    /** Show on both screens for BLE pairing confirmation. */
    val sas: String,
    internal val keys: SessionKeys,
)

class VerifiedReceipt(val paymentId: ByteArray, val receivedAtMillis: Long, val receiverName: String)

/**
 * Payer state machine (initiator). Usage:
 * ```
 * PayerSession(transport, trust).use { s ->
 *     val offer = s.negotiate()                       // HELLO → OFFER (verified)
 *     // UI: confirm name/amount/SAS + biometric
 *     val prepared = vault.authorizePayment(offer, amount)   // single-use keys sign
 *     val receipt = s.pay(offer, prepared.paymentId, prepared.bundle)   // PAY → RECEIPT
 * }
 * ```
 */
class PayerSession(
    private val transport: OfflineTransport,
    private val trust: IssuerTrustStore,
) : AutoCloseable {
    private var negotiated: NegotiatedOffer? = null

    suspend fun negotiate(): NegotiatedOffer = guard {
        check(negotiated == null) { "negotiate() may be called once" }
        val ephemeral = P256.generateEphemeral()
        val payerEphemeral = P256.encodePoint(ephemeral.public as ECPublicKey)
        val payerNonce = SecureBytes.random(NONCE_SIZE)
        val helloPayload = Hello(PROTOCOL_VERSION, payerEphemeral, payerNonce).encode()

        val reply = expect(Frame.decode(transport.exchange(Frame(FrameType.HELLO, helloPayload).encode())), FrameType.OFFER)
        val offer = Offer.decode(reply.payload)

        val shared = P256.ecdh(ephemeral.private, P256.decodePoint(offer.payeeEphemeralPub))
        destroyQuietly(ephemeral.private)
        val keys = try {
            SessionKeys.derive(shared, payerNonce, offer.payeeNonce, payerEphemeral, offer.payeeEphemeralPub)
        } finally {
            shared.wipe()
        }

        try {
            val helloHash = sha256(helloPayload)
            val requestPlain = keys.open(
                Direction.PAYEE_TO_PAYER, 0,
                Transcript.offerAad(helloHash, offer.payeeEphemeralPub, offer.payeeNonce),
                offer.sealedRequest,
            )
            val request = OfferRequest.decode(requestPlain)
            val receiver = trust.verifyReceiver(request.signedReceiverCert)

            // The payee's identity key must sign over *both* ephemeral keys: this is
            // what makes a man-in-the-middle impossible without the payee's StrongBox key.
            val identity = P256.publicKeyFromSpki(receiver.identityPublicKey)
            val sigBody = offerSigBody(helloHash, offer.payeeEphemeralPub, offer.payeeNonce, receiver.receiverId, request.requestedAmountPaise)
            if (!P256.verify(identity, Domains.OFFER, sigBody, request.payeeSignature)) {
                throw OfflineProtocolException(Reason.BAD_PAYEE_SIGNATURE)
            }

            val transcript = Transcript.hash(helloPayload, offer.payeeEphemeralPub, offer.payeeNonce, requestPlain)
            NegotiatedOffer(receiver, request.requestedAmountPaise, transcript, Transcript.sas(transcript), keys)
                .also { negotiated = it }
        } catch (e: Exception) {
            keys.close()
            throw e
        }
    }

    /** Sends the signed bundle and verifies the payee's signed receipt. */
    suspend fun pay(offer: NegotiatedOffer, paymentId: ByteArray, bundle: ByteArray): VerifiedReceipt = guard {
        check(offer === negotiated) { "offer does not belong to this session" }
        val sealed = offer.keys.seal(Direction.PAYER_TO_PAYEE, 0, Transcript.payAad(offer.transcript), bundle)
        val reply = expect(Frame.decode(transport.exchange(Frame(FrameType.PAY, Pay(sealed).encode()).encode())), FrameType.RECEIPT)

        val envelope = SignedEnvelope.decode(
            offer.keys.open(Direction.PAYEE_TO_PAYER, 1, Transcript.receiptAad(offer.transcript), Receipt.decode(reply.payload).sealedReceipt),
        )
        val identity = P256.publicKeyFromSpki(offer.receiver.identityPublicKey)
        if (!P256.verify(identity, Domains.RECEIPT, envelope.body, envelope.signature)) {
            throw OfflineProtocolException(Reason.RECEIPT_INVALID)
        }
        val body = ReceiptBody.decode(envelope.body)
        val valid = body.paymentId.constantTimeEquals(paymentId) &&
            body.bundleHash.constantTimeEquals(sha256(bundle)) &&
            body.status == ReceiptBody.STATUS_ACCEPTED
        if (!valid) throw OfflineProtocolException(Reason.RECEIPT_INVALID)
        VerifiedReceipt(paymentId, body.receivedAtMillis, offer.receiver.displayName)
    }

    override fun close() {
        negotiated?.keys?.close()
        transport.close()
    }

    private fun expect(frame: Frame, type: FrameType): Frame {
        if (frame.type == FrameType.ABORT) {
            throw OfflineProtocolException(Reason.PEER_ABORTED, IllegalStateException(Abort.decode(frame.payload).reason.name))
        }
        if (frame.type != type) throw OfflineProtocolException(Reason.STATE)
        return frame
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: FceException) {
        throw OfflineProtocolException(Reason.MALFORMED, e)
    } catch (e: GeneralSecurityException) {
        // Includes AEADBadTagException: tampering or a different session.
        throw OfflineProtocolException(Reason.MALFORMED, e)
    }
}

internal fun destroyQuietly(key: Any) {
    try {
        (key as? Destroyable)?.destroy()
    } catch (e: DestroyFailedException) {
        // Provider keys may not support destroy(); the reference is dropped right after.
    }
}
