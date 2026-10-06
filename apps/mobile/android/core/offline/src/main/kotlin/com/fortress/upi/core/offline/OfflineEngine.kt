package com.fortress.upi.core.offline

import android.content.Context
import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.offline.protocol.BundleVerifier
import com.fortress.upi.core.offline.protocol.IssuerTrustStore
import com.fortress.upi.core.offline.protocol.PayeeEvent
import com.fortress.upi.core.offline.protocol.PayeeSession
import com.fortress.upi.core.offline.protocol.ReceiverIdentity
import com.fortress.upi.core.offline.store.OfflineDatabase
import com.fortress.upi.core.offline.store.RoomPaymentInbox
import com.fortress.upi.core.offline.sync.SettlementApi
import com.fortress.upi.core.offline.vault.NoteKeyFactory
import com.fortress.upi.core.offline.vault.OfflineVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Implemented by the Application so system-instantiated components (worker, HCE) reach the engine. */
interface OfflineEngineProvider {
    fun offlineEngine(): OfflineEngine
}

/**
 * Facade over the offline subsystem. The encrypted database opens lazily on a
 * background thread on first use (it needs an unlocked device).
 */
class OfflineEngine(
    private val context: Context,
    val trust: IssuerTrustStore,
    val settlementApi: SettlementApi,
    val clock: Clock,
    /** Null until the device is bound (receiver certificate issued). */
    private val receiverIdentity: () -> ReceiverIdentity?,
) {
    private val dbLock = Mutex()
    private val vaultLock = Mutex()

    @Volatile
    private var db: OfflineDatabase? = null

    @Volatile
    private var vaultInstance: OfflineVault? = null

    val keys: NoteKeyFactory by lazy { NoteKeyFactory(context) }

    suspend fun database(): OfflineDatabase = db ?: dbLock.withLock {
        db ?: withContext(Dispatchers.IO) { OfflineDatabase.open(context) }.also { db = it }
    }

    suspend fun vault(): OfflineVault = vaultInstance ?: vaultLock.withLock {
        vaultInstance ?: OfflineVault(database(), keys, trust, clock).also { vaultInstance = it }
    }

    /** New responder for one incoming payment (BLE or NFC). */
    suspend fun newPayeeSession(requestedAmountPaise: Long, onEvent: (PayeeEvent) -> Unit): PayeeSession {
        val identity = receiverIdentity() ?: throw IllegalStateException("device is not bound; cannot receive offline")
        return PayeeSession(
            identity = identity,
            requestedAmountPaise = requestedAmountPaise,
            verifier = BundleVerifier(trust, clock),
            inbox = RoomPaymentInbox(database(), clock),
            clock = clock,
            onEvent = onEvent,
        )
    }
}
