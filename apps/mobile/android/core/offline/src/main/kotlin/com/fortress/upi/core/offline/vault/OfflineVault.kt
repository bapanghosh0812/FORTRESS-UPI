package com.fortress.upi.core.offline.vault

import androidx.room.withTransaction
import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.hexToBytes
import com.fortress.upi.core.crypto.toHex
import com.fortress.upi.core.offline.protocol.ID_SIZE
import com.fortress.upi.core.offline.protocol.IssuerTrustStore
import com.fortress.upi.core.offline.protocol.NegotiatedOffer
import com.fortress.upi.core.offline.protocol.OfflineProtocolException
import com.fortress.upi.core.offline.protocol.PaymentBundle
import com.fortress.upi.core.offline.protocol.Reason
import com.fortress.upi.core.offline.protocol.SpendItem
import com.fortress.upi.core.offline.protocol.spendSigBody
import com.fortress.upi.core.offline.store.NoteEntity
import com.fortress.upi.core.offline.store.NoteStatus
import com.fortress.upi.core.offline.store.OfflineDatabase
import com.fortress.upi.core.offline.store.OutboxDirection
import com.fortress.upi.core.offline.store.OutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A note key prepared for issuance (sent to the issuer with its attestation chain). */
class PreparedNoteKey(
    val keyAlias: String,
    val denominationPaise: Long,
    val publicKeySpki: ByteArray,
    val attestationChain: List<ByteArray>,
)

/** Issuer response: the HSM-signed certificate for one prepared key. */
class IssuedNote(val keyAlias: String, val signedCert: ByteArray)

class PreparedPayment(val paymentId: ByteArray, val bundle: ByteArray, val amountPaise: Long)

/**
 * The offline vault: pre-funded notes backed by single-use hardware keys.
 * See docs/OFFLINE_PROTOCOL.md §1 and §5 (failure semantics).
 */
class OfflineVault(
    private val db: OfflineDatabase,
    private val keys: NoteKeyFactory,
    private val trust: IssuerTrustStore,
    private val clock: Clock,
) {
    private val spendLock = Mutex()

    fun spendableBalance(): Flow<Long> = db.notes().observeSpendable(nowSec())

    fun denominations(): Flow<List<Long>> = db.notes().observeDenominations(nowSec())

    /** Load step 1 (online): attested single-use keys for the planned denominations. */
    suspend fun prepareNoteKeys(totalPaise: Long, attestationChallenge: ByteArray, authWindowSeconds: Int = 30): List<PreparedNoteKey> =
        withContext(Dispatchers.IO) {
            if (!keys.capability().supported) throw OfflineProtocolException(Reason.ENFORCEMENT_POLICY)
            DenominationPlanner.plan(totalPaise).map { denomination ->
                val alias = NOTE_ALIAS_PREFIX + SecureBytes.random(ID_SIZE).toHex()
                val k = keys.generate(alias, attestationChallenge, authWindowSeconds)
                PreparedNoteKey(alias, denomination, k.publicKeySpki, k.attestationChain)
            }
        }

    /** Load step 2: verify each certificate is issuer-signed and binds *our* key, then store. */
    suspend fun installNotes(issued: List<IssuedNote>) = withContext(Dispatchers.IO) {
        val now = clock.nowMillis()
        val rows = issued.map { n ->
            val cert = trust.verifyNote(n.signedCert)
            val localKey = keys.publicKeySpki(n.keyAlias) ?: throw OfflineProtocolException(Reason.MALFORMED)
            if (!localKey.contentEquals(cert.notePublicKey)) throw OfflineProtocolException(Reason.MALFORMED)
            NoteEntity(
                noteId = cert.noteId.toHex(),
                keyAlias = n.keyAlias,
                denominationPaise = cert.denominationPaise,
                expiresAtEpochSec = cert.expiresAtSec,
                enforcement = cert.enforcement,
                signedCert = n.signedCert,
                status = NoteStatus.AVAILABLE,
                updatedAtMillis = now,
            )
        }
        db.notes().insertAll(rows)
    }

    /**
     * Authorizes an offline payment: selects notes, signs one spend per note with
     * its single-use key (the key self-destructs), and persists the bundle to the
     * outbox **before** it is transmitted. Requires a recent biometric/credential
     * auth (keys carry a 30 s auth window); otherwise throws
     * [android.security.keystore.UserNotAuthenticatedException] before any key is used.
     */
    suspend fun authorizePayment(offer: NegotiatedOffer, amountPaise: Long): PreparedPayment = withContext(Dispatchers.IO) {
        if (amountPaise <= 0) throw OfflineProtocolException(Reason.AMOUNT)
        if (offer.requestedAmountPaise > 0 && offer.requestedAmountPaise != amountPaise) {
            throw OfflineProtocolException(Reason.AMOUNT)
        }
        spendLock.withLock {
            val picks = reserve(amountPaise)
            val paymentId = SecureBytes.random(ID_SIZE)
            val createdAt = clock.nowMillis()
            val receiverId = offer.receiver.receiverId

            val items = ArrayList<SpendItem>(picks.size)
            try {
                for (p in picks) {
                    val body = spendSigBody(
                        p.note.noteId.hexToBytes(), paymentId, receiverId, offer.transcript,
                        p.appliedPaise, amountPaise, createdAt,
                    )
                    items += SpendItem(p.note.signedCert, p.appliedPaise, keys.signOnce(p.note.keyAlias, Domains.SPEND, body))
                }
            } catch (e: Exception) {
                withContext(NonCancellable) { releaseAfterFailure(picks.map { it.note }) }
                throw e
            }

            val bundle = PaymentBundle(paymentId, offer.transcript, receiverId, amountPaise, createdAt, items).encode()
            val ids = picks.map { it.note.noteId }
            withContext(NonCancellable) {
                db.withTransaction {
                    db.notes().transition(ids, NoteStatus.RESERVED, NoteStatus.SPENT, createdAt)
                    db.outbox().insert(
                        OutboxEntity(
                            paymentId = paymentId.toHex(),
                            direction = OutboxDirection.SENT,
                            amountPaise = amountPaise,
                            counterpartyName = offer.receiver.displayName,
                            bundle = bundle,
                            createdAtMillis = createdAt,
                        ),
                    )
                }
            }
            PreparedPayment(paymentId, bundle, amountPaise)
        }
    }

    /**
     * Returns every spendable note to the online wallet (online only): each note
     * key signs a zero-amount spend to the redeem sentinel, consuming the key.
     */
    suspend fun redeemAll(): PreparedPayment? = withContext(Dispatchers.IO) {
        spendLock.withLock {
            val notes = db.notes().spendable(nowSec()).take(MAX_NOTES_PER_BUNDLE)
            if (notes.isEmpty()) return@withLock null
            val ids = notes.map { it.noteId }
            db.withTransaction {
                if (db.notes().transition(ids, NoteStatus.AVAILABLE, NoteStatus.RESERVED, clock.nowMillis()) != ids.size) {
                    throw OfflineProtocolException(Reason.STATE)
                }
            }
            val paymentId = SecureBytes.random(ID_SIZE)
            val createdAt = clock.nowMillis()
            val zeroReceiver = ByteArray(ID_SIZE)
            val zeroTranscript = ByteArray(32)
            val items = try {
                notes.map { n ->
                    val body = spendSigBody(n.noteId.hexToBytes(), paymentId, zeroReceiver, zeroTranscript, 0, 0, createdAt)
                    SpendItem(n.signedCert, 0, keys.signOnce(n.keyAlias, Domains.SPEND, body))
                }
            } catch (e: Exception) {
                withContext(NonCancellable) { releaseAfterFailure(notes) }
                throw e
            }
            val bundle = PaymentBundle(paymentId, zeroTranscript, zeroReceiver, 0, createdAt, items).encode()
            withContext(NonCancellable) {
                db.withTransaction {
                    db.notes().transition(ids, NoteStatus.RESERVED, NoteStatus.REDEEMED, createdAt)
                    db.outbox().insert(
                        OutboxEntity(
                            paymentId = paymentId.toHex(),
                            direction = OutboxDirection.REDEEM,
                            amountPaise = notes.sumOf { it.denominationPaise },
                            counterpartyName = "Fortress wallet",
                            bundle = bundle,
                            createdAtMillis = createdAt,
                        ),
                    )
                }
            }
            PreparedPayment(paymentId, bundle, notes.sumOf { it.denominationPaise })
        }
    }

    private suspend fun reserve(amountPaise: Long): List<NotePick<NoteEntity>> = db.withTransaction {
        val candidates = db.notes().spendable(nowSec())
        val picks = NoteSelector.select(candidates, amountPaise, NoteEntity::denominationPaise, MAX_NOTES_PER_BUNDLE)
            ?: throw OfflineProtocolException(Reason.INSUFFICIENT_FUNDS)
        val ids = picks.map { it.note.noteId }
        if (db.notes().transition(ids, NoteStatus.AVAILABLE, NoteStatus.RESERVED, clock.nowMillis()) != ids.size) {
            throw OfflineProtocolException(Reason.STATE) // rolls back the transaction
        }
        picks
    }

    /** Unused keys go back to AVAILABLE; consumed keys become ORPHANED (server auto-refunds after expiry). */
    private suspend fun releaseAfterFailure(notes: List<NoteEntity>) {
        val (intact, consumed) = notes.partition { keys.exists(it.keyAlias) }
        val now = clock.nowMillis()
        db.withTransaction {
            if (intact.isNotEmpty()) db.notes().transition(intact.map { it.noteId }, NoteStatus.RESERVED, NoteStatus.AVAILABLE, now)
            if (consumed.isNotEmpty()) db.notes().transition(consumed.map { it.noteId }, NoteStatus.RESERVED, NoteStatus.ORPHANED, now)
        }
    }

    private fun nowSec(): Long = clock.nowMillis() / 1000

    private companion object {
        const val NOTE_ALIAS_PREFIX = "fortress.note."
        const val MAX_NOTES_PER_BUNDLE = 16
    }
}
