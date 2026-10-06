package com.fortress.upi.core.offline.protocol

import com.fortress.upi.core.crypto.Fce
import com.fortress.upi.core.crypto.FceException

/*
 * FOP/1 wire structures (docs/OFFLINE_PROTOCOL.md §3). Tag numbers are part of
 * the signed format and must match services/offline-mesh/internal/domain.
 * This package is pure JVM (no android.*) so the whole protocol is unit-tested.
 */

const val PROTOCOL_VERSION = 1L
const val MAX_SPENDS_PER_BUNDLE = 16
internal const val ID_SIZE = 16
internal const val NONCE_SIZE = 32
internal const val HASH_SIZE = 32
internal const val POINT_SIZE = 65
private const val MIN_SIG = 8
private const val MAX_SIG = 80
private const val MAX_SEALED = 16 * 1024
private const val MAX_NAME_BYTES = 64

/** `{1: body, 2: DER signature}` */
class SignedEnvelope(val body: ByteArray, val signature: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().bytes(1, body).bytes(2, signature).build()

    companion object {
        fun decode(raw: ByteArray): SignedEnvelope {
            val m = Fce.decode(raw, 1, 2)
            return SignedEnvelope(m.bytes(1), m.bytes(2, MIN_SIG, MAX_SIG))
        }
    }
}

/** Issuer-certified binding of a single-use device key to a denomination. */
class NoteCertificate(
    val noteId: ByteArray,
    val walletId: ByteArray,
    val notePublicKey: ByteArray,
    val denominationPaise: Long,
    val issuedAtSec: Long,
    val expiresAtSec: Long,
    val issuerKeyId: ByteArray,
    val enforcement: Int,
) {
    fun encodeBody(): ByteArray = Fce.Writer()
        .bytes(1, noteId).bytes(2, walletId).bytes(3, notePublicKey)
        .u64(4, denominationPaise).u64(5, issuedAtSec).u64(6, expiresAtSec)
        .bytes(7, issuerKeyId).u64(8, enforcement.toLong())
        .build()

    companion object {
        const val ENFORCEMENT_SOFTWARE = 0
        const val ENFORCEMENT_TEE = 1
        const val ENFORCEMENT_STRONGBOX = 2

        fun decodeBody(body: ByteArray): NoteCertificate {
            val m = Fce.decode(body, 1, 2, 3, 4, 5, 6, 7, 8)
            val enforcement = m.u64(8)
            val c = NoteCertificate(
                noteId = m.fixed(1, ID_SIZE),
                walletId = m.fixed(2, ID_SIZE),
                notePublicKey = m.bytes(3, 1, 256),
                denominationPaise = m.u64(4),
                issuedAtSec = m.u64(5),
                expiresAtSec = m.u64(6),
                issuerKeyId = m.bytes(7, 1, 32),
                enforcement = if (enforcement <= ENFORCEMENT_STRONGBOX) enforcement.toInt() else throw FceException("enforcement"),
            )
            if (c.expiresAtSec <= c.issuedAtSec || c.denominationPaise <= 0) throw FceException("note certificate semantics")
            return c
        }
    }
}

/** Issuer-certified binding of a payee's device identity key to an opaque receiver ID. */
class ReceiverCertificate(
    val receiverId: ByteArray,
    val displayName: String,
    val identityPublicKey: ByteArray,
    val issuedAtSec: Long,
    val expiresAtSec: Long,
    val issuerKeyId: ByteArray,
) {
    fun encodeBody(): ByteArray = Fce.Writer()
        .bytes(1, receiverId).string(2, displayName).bytes(3, identityPublicKey)
        .u64(4, issuedAtSec).u64(5, expiresAtSec).bytes(6, issuerKeyId)
        .build()

    companion object {
        fun decodeBody(body: ByteArray): ReceiverCertificate {
            val m = Fce.decode(body, 1, 2, 3, 4, 5, 6)
            val c = ReceiverCertificate(
                receiverId = m.fixed(1, ID_SIZE),
                displayName = m.string(2, MAX_NAME_BYTES),
                identityPublicKey = m.bytes(3, 1, 256),
                issuedAtSec = m.u64(4),
                expiresAtSec = m.u64(5),
                issuerKeyId = m.bytes(6, 1, 32),
            )
            if (c.expiresAtSec <= c.issuedAtSec || c.displayName.isBlank()) throw FceException("receiver certificate semantics")
            return c
        }
    }
}

enum class FrameType(val code: Long) {
    HELLO(1), OFFER(2), PAY(3), RECEIPT(4), ABORT(15);

    companion object {
        fun of(code: Long): FrameType = entries.firstOrNull { it.code == code } ?: throw FceException("unknown frame type")
    }
}

class Frame(val type: FrameType, val payload: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().u64(1, type.code).bytes(2, payload).build()

    companion object {
        fun decode(raw: ByteArray): Frame {
            val m = Fce.decode(raw, 1, 2)
            return Frame(FrameType.of(m.u64(1)), m.bytes(2))
        }
    }
}

class Hello(val version: Long, val payerEphemeralPub: ByteArray, val payerNonce: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().u64(1, version).bytes(2, payerEphemeralPub).bytes(3, payerNonce).build()

    companion object {
        fun decode(raw: ByteArray): Hello {
            val m = Fce.decode(raw, 1, 2, 3)
            return Hello(m.u64(1), m.fixed(2, POINT_SIZE), m.fixed(3, NONCE_SIZE))
        }
    }
}

class Offer(val payeeEphemeralPub: ByteArray, val payeeNonce: ByteArray, val sealedRequest: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().bytes(1, payeeEphemeralPub).bytes(2, payeeNonce).bytes(3, sealedRequest).build()

    companion object {
        fun decode(raw: ByteArray): Offer {
            val m = Fce.decode(raw, 1, 2, 3)
            return Offer(m.fixed(1, POINT_SIZE), m.fixed(2, NONCE_SIZE), m.bytes(3, 1, MAX_SEALED))
        }
    }
}

class OfferRequest(val signedReceiverCert: ByteArray, val requestedAmountPaise: Long, val payeeSignature: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().bytes(1, signedReceiverCert).u64(2, requestedAmountPaise).bytes(3, payeeSignature).build()

    companion object {
        fun decode(raw: ByteArray): OfferRequest {
            val m = Fce.decode(raw, 1, 2, 3)
            return OfferRequest(m.bytes(1), m.u64(2), m.bytes(3, MIN_SIG, MAX_SIG))
        }
    }
}

/** Body the payee's identity key signs in OFFER: binds both ephemeral keys to the receiver. */
fun offerSigBody(
    helloHash: ByteArray,
    payeeEphemeralPub: ByteArray,
    payeeNonce: ByteArray,
    receiverId: ByteArray,
    requestedAmountPaise: Long,
): ByteArray = Fce.Writer()
    .bytes(1, helloHash).bytes(2, payeeEphemeralPub).bytes(3, payeeNonce)
    .bytes(4, receiverId).u64(5, requestedAmountPaise)
    .build()

class Pay(val sealedBundle: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().bytes(1, sealedBundle).build()

    companion object {
        fun decode(raw: ByteArray): Pay = Pay(Fce.decode(raw, 1).bytes(1, 1, MAX_SEALED * 3))
    }
}

class SpendItem(val signedNoteCert: ByteArray, val appliedPaise: Long, val signature: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().bytes(1, signedNoteCert).u64(2, appliedPaise).bytes(3, signature).build()

    companion object {
        fun decode(raw: ByteArray): SpendItem {
            val m = Fce.decode(raw, 1, 2, 3)
            return SpendItem(m.bytes(1), m.u64(2), m.bytes(3, MIN_SIG, MAX_SIG))
        }
    }
}

class PaymentBundle(
    val paymentId: ByteArray,
    val transcript: ByteArray,
    val receiverId: ByteArray,
    val totalPaise: Long,
    val createdAtMillis: Long,
    val spends: List<SpendItem>,
) {
    fun encode(): ByteArray = Fce.Writer()
        .bytes(1, paymentId).bytes(2, transcript).bytes(3, receiverId)
        .u64(4, totalPaise).u64(5, createdAtMillis)
        .list(6, spends.map(SpendItem::encode))
        .build()

    companion object {
        fun decode(raw: ByteArray): PaymentBundle {
            val m = Fce.decode(raw, 1, 2, 3, 4, 5, 6)
            val items = m.list(6)
            if (items.isEmpty() || items.size > MAX_SPENDS_PER_BUNDLE) throw FceException("spend count")
            return PaymentBundle(
                paymentId = m.fixed(1, ID_SIZE),
                transcript = m.fixed(2, HASH_SIZE),
                receiverId = m.fixed(3, ID_SIZE),
                totalPaise = m.u64(4),
                createdAtMillis = m.u64(5),
                spends = items.map(SpendItem::decode),
            )
        }
    }
}

/** Body a single-use note key signs. Identical to Go `domain.SpendSigBody`. */
fun spendSigBody(
    noteId: ByteArray,
    paymentId: ByteArray,
    receiverId: ByteArray,
    transcript: ByteArray,
    appliedPaise: Long,
    totalPaise: Long,
    createdAtMillis: Long,
): ByteArray = Fce.Writer()
    .bytes(1, noteId).bytes(2, paymentId).bytes(3, receiverId).bytes(4, transcript)
    .u64(5, appliedPaise).u64(6, totalPaise).u64(7, createdAtMillis)
    .build()

class ReceiptBody(val paymentId: ByteArray, val bundleHash: ByteArray, val status: Long, val receivedAtMillis: Long) {
    fun encode(): ByteArray = Fce.Writer().bytes(1, paymentId).bytes(2, bundleHash).u64(3, status).u64(4, receivedAtMillis).build()

    companion object {
        const val STATUS_ACCEPTED = 1L

        fun decode(raw: ByteArray): ReceiptBody {
            val m = Fce.decode(raw, 1, 2, 3, 4)
            return ReceiptBody(m.fixed(1, ID_SIZE), m.fixed(2, HASH_SIZE), m.u64(3), m.u64(4))
        }
    }
}

class Receipt(val sealedReceipt: ByteArray) {
    fun encode(): ByteArray = Fce.Writer().bytes(1, sealedReceipt).build()

    companion object {
        fun decode(raw: ByteArray): Receipt = Receipt(Fce.decode(raw, 1).bytes(1, 1, MAX_SEALED))
    }
}

class Abort(val reason: Reason) {
    fun encode(): ByteArray = Fce.Writer().u64(1, reason.code).build()

    companion object {
        fun decode(raw: ByteArray): Abort = Abort(Reason.of(Fce.decode(raw, 1).u64(1)))
    }
}
