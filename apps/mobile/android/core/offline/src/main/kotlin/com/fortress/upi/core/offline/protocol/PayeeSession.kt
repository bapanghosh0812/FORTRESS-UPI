package com.fortress.upi.core.offline.protocol

import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.Direction
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.FceException
import com.fortress.upi.core.crypto.P256
import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.SessionKeys
import com.fortress.upi.core.crypto.sha256
import com.fortress.upi.core.crypto.toHex
import com.fortress.upi.core.crypto.wipe
import com.fortress.upi.core.offline.transport.OfflineResponder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.security.interfaces.ECPublicKey

/** The payee's bound identity: issuer-signed receiver certificate + hardware identity key. */
interface ReceiverIdentity {
    val signedReceiverCert: ByteArray
    val receiverId: ByteArray

    /** Signs inside StrongBox/TEE (DeviceIdentity.signWithIdentity). */
    fun sign(domain: String, body: ByteArray): ByteArray
}

/** Durable, replay-safe inbox (Room/SQLCipher). Must throw REPLAY if any noteId was seen before. */
fun interface PaymentInbox {
    suspend fun record(verified: VerifiedBundle, rawBundle: ByteArray)
}

sealed interface PayeeEvent {
    class PayerConnected(val sas: String) : PayeeEvent
    class Received(val amountPaise: Long, val paymentIdHex: String) : PayeeEvent
    class Failed(val reason: Reason) : PayeeEvent
}

/**
 * Payee state machine (responder): AwaitingHello → AwaitingPay → Done | Failed.
 * One instance serves exactly one payment; create a new one per payment.
 */
class PayeeSession(
    private val identity: ReceiverIdentity,
    private val requestedAmountPaise: Long,
    private val verifier: BundleVerifier,
    private val inbox: PaymentInbox,
    private val clock: Clock,
    private val onEvent: (PayeeEvent) -> Unit,
) : OfflineResponder, AutoCloseable {

    private sealed interface State {
        data object AwaitingHello : State
        class AwaitingPay(val keys: SessionKeys, val transcript: ByteArray) : State
        data object Done : State
        data object Failed : State
    }

    private val mutex = Mutex()
    private var state: State = State.AwaitingHello

    init {
        require(requestedAmountPaise >= 0) { "requested amount must be >= 0" }
    }

    override suspend fun respond(request: ByteArray): ByteArray = mutex.withLock {
        try {
            val frame = Frame.decode(request)
            when (val s = state) {
                State.AwaitingHello -> onHello(frame)
                is State.AwaitingPay -> onPay(s, frame)
                State.Done, State.Failed -> throw OfflineProtocolException(Reason.STATE)
            }
        } catch (e: OfflineProtocolException) {
            fail(e.reason)
        } catch (e: FceException) {
            fail(Reason.MALFORMED)
        } catch (e: GeneralSecurityException) {
            fail(Reason.MALFORMED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keystore/storage failure: abort cleanly instead of leaving the payer hanging.
            fail(Reason.INTERNAL)
        }
    }

    private fun onHello(frame: Frame): ByteArray {
        if (frame.type != FrameType.HELLO) throw OfflineProtocolException(Reason.STATE)
        val hello = Hello.decode(frame.payload)
        if (hello.version != PROTOCOL_VERSION) throw OfflineProtocolException(Reason.UNSUPPORTED_VERSION)
        val payerEphemeral = P256.decodePoint(hello.payerEphemeralPub)

        val ephemeral = P256.generateEphemeral()
        val payeeEphemeral = P256.encodePoint(ephemeral.public as ECPublicKey)
        val payeeNonce = SecureBytes.random(NONCE_SIZE)
        val shared = P256.ecdh(ephemeral.private, payerEphemeral)
        destroyQuietly(ephemeral.private)
        val keys = try {
            SessionKeys.derive(shared, hello.payerNonce, payeeNonce, hello.payerEphemeralPub, payeeEphemeral)
        } finally {
            shared.wipe()
        }

        try {
            val helloHash = sha256(frame.payload)
            val signature = identity.sign(
                Domains.OFFER,
                offerSigBody(helloHash, payeeEphemeral, payeeNonce, identity.receiverId, requestedAmountPaise),
            )
            val requestPlain = OfferRequest(identity.signedReceiverCert, requestedAmountPaise, signature).encode()
            val transcript = Transcript.hash(frame.payload, payeeEphemeral, payeeNonce, requestPlain)
            val sealed = keys.seal(
                Direction.PAYEE_TO_PAYER, 0,
                Transcript.offerAad(helloHash, payeeEphemeral, payeeNonce),
                requestPlain,
            )
            state = State.AwaitingPay(keys, transcript)
            onEvent(PayeeEvent.PayerConnected(Transcript.sas(transcript)))
            return Frame(FrameType.OFFER, Offer(payeeEphemeral, payeeNonce, sealed).encode()).encode()
        } catch (e: Exception) {
            keys.close()
            throw e
        }
    }

    private suspend fun onPay(s: State.AwaitingPay, frame: Frame): ByteArray {
        if (frame.type != FrameType.PAY) throw OfflineProtocolException(Reason.STATE)
        val bundleBytes = s.keys.open(Direction.PAYER_TO_PAYEE, 0, Transcript.payAad(s.transcript), Pay.decode(frame.payload).sealedBundle)
        val verified = verifier.verify(bundleBytes, identity.receiverId, s.transcript, requestedAmountPaise)

        // Persist (with UNIQUE noteIds) *before* acknowledging: a receipt is a promise.
        inbox.record(verified, bundleBytes)

        val body = ReceiptBody(verified.bundle.paymentId, sha256(bundleBytes), ReceiptBody.STATUS_ACCEPTED, clock.nowMillis()).encode()
        val envelope = SignedEnvelope(body, identity.sign(Domains.RECEIPT, body)).encode()
        val sealed = s.keys.seal(Direction.PAYEE_TO_PAYER, 1, Transcript.receiptAad(s.transcript), envelope)
        s.keys.close()
        state = State.Done
        onEvent(PayeeEvent.Received(verified.bundle.totalPaise, verified.bundle.paymentId.toHex()))
        return Frame(FrameType.RECEIPT, Receipt(sealed).encode()).encode()
    }

    private fun fail(reason: Reason): ByteArray {
        (state as? State.AwaitingPay)?.keys?.close()
        state = State.Failed
        onEvent(PayeeEvent.Failed(reason))
        return Frame(FrameType.ABORT, Abort(reason).encode()).encode()
    }

    override fun close() {
        (state as? State.AwaitingPay)?.keys?.close()
        if (state !is State.Done) state = State.Failed
    }
}
