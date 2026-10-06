package com.fortress.upi.core.offline.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class NoteStatus {
    /** Spendable. */
    AVAILABLE,

    /** Locked for an in-flight authorization. */
    RESERVED,

    /** Signed into a persisted bundle (settles via outbox). */
    SPENT,

    /** Key consumed but no bundle persisted (crash mid-sign): auto-refunded by the server after expiry. */
    ORPHANED,

    /** Returned to the online wallet via a redeem bundle. */
    REDEEMED,
}

enum class OutboxDirection { SENT, RECEIVED, REDEEM }

enum class SyncState { PENDING, SYNCED, REJECTED }

/** A note this device can spend. The private key lives in StrongBox under [keyAlias]. */
@Entity(tableName = "notes", indices = [Index("status"), Index("expiresAtEpochSec")])
class NoteEntity(
    @PrimaryKey val noteId: String,
    val keyAlias: String,
    val denominationPaise: Long,
    val expiresAtEpochSec: Long,
    val enforcement: Int,
    val signedCert: ByteArray,
    val status: NoteStatus,
    val updatedAtMillis: Long,
)

/** Every bundle that must reach the settlement engine (sent, received or redeemed). */
@Entity(tableName = "outbox", indices = [Index(value = ["paymentId", "direction"], unique = true), Index("syncState")])
class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paymentId: String,
    val direction: OutboxDirection,
    val amountPaise: Long,
    val counterpartyName: String,
    val bundle: ByteArray,
    val createdAtMillis: Long,
    val syncState: SyncState = SyncState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** Payee-side replay guard: a note can be accepted once per device, ever. */
@Entity(tableName = "received_notes")
class ReceivedNoteEntity(
    @PrimaryKey val noteId: String,
    val paymentId: String,
    val receivedAtMillis: Long,
)
