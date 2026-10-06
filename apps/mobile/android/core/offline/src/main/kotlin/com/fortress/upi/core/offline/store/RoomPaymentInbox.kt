package com.fortress.upi.core.offline.store

import androidx.room.withTransaction
import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.toHex
import com.fortress.upi.core.offline.protocol.OfflineProtocolException
import com.fortress.upi.core.offline.protocol.PaymentInbox
import com.fortress.upi.core.offline.protocol.Reason
import com.fortress.upi.core.offline.protocol.VerifiedBundle

/**
 * Payee inbox: atomically records the note IDs (replay guard) and queues the
 * bundle for settlement. Either both commit or neither does.
 */
class RoomPaymentInbox(
    private val db: OfflineDatabase,
    private val clock: Clock,
    private val payerLabel: String = "Offline payment",
) : PaymentInbox {

    override suspend fun record(verified: VerifiedBundle, rawBundle: ByteArray) {
        val now = clock.nowMillis()
        val paymentId = verified.bundle.paymentId.toHex()
        db.withTransaction {
            if (db.receivedNotes().countExisting(verified.noteIds) > 0) {
                throw OfflineProtocolException(Reason.REPLAY)
            }
            db.receivedNotes().insertAll(verified.noteIds.map { ReceivedNoteEntity(it, paymentId, now) })
            db.outbox().insert(
                OutboxEntity(
                    paymentId = paymentId,
                    direction = OutboxDirection.RECEIVED,
                    amountPaise = verified.bundle.totalPaise,
                    counterpartyName = payerLabel,
                    bundle = rawBundle,
                    createdAtMillis = now,
                ),
            )
        }
    }
}
