package com.fortress.upi.ui.offline

import android.app.Application
import android.security.keystore.UserNotAuthenticatedException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.core.offline.protocol.NegotiatedOffer
import com.fortress.upi.core.offline.protocol.OfflineProtocolException
import com.fortress.upi.core.offline.protocol.PayeeEvent
import com.fortress.upi.core.offline.protocol.PayerSession
import com.fortress.upi.core.offline.protocol.Reason
import com.fortress.upi.core.offline.store.OutboxEntity
import com.fortress.upi.core.offline.sync.ReconciliationWorker
import com.fortress.upi.core.offline.transport.OfflineResponder
import com.fortress.upi.core.offline.transport.OfflineTransport
import com.fortress.upi.core.offline.transport.ble.BleCentralTransport
import com.fortress.upi.core.offline.transport.ble.BlePeripheralServer
import com.fortress.upi.core.offline.transport.nfc.ReceiveSessionRegistry
import com.fortress.upi.data.ApiException
import com.fortress.upi.di.AppContainer
import com.fortress.upi.platform.AuthGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface OfflinePhase {
    data object Idle : OfflinePhase
    data class Working(val label: String) : OfflinePhase
    /** BLE: payee verified, waiting for the user to confirm name + SAS + amount. */
    class Confirm(val offer: NegotiatedOffer, val amountPaise: Long?) : OfflinePhase
    /** NFC: amount authorized; reader mode on, waiting for a tap. */
    data class ArmedTap(val amountPaise: Long) : OfflinePhase
    data class Receiving(val amountPaise: Long, val sas: String?) : OfflinePhase
    data class Success(val amountPaise: Long, val counterparty: String, val incoming: Boolean) : OfflinePhase
    data class Failed(val message: String) : OfflinePhase
    data class Info(val message: String) : OfflinePhase
}

class OfflineViewModel(private val app: Application, private val container: AppContainer) : ViewModel() {
    private val engine = container.offlineEngine
    private val _phase = MutableStateFlow<OfflinePhase>(OfflinePhase.Idle)
    val phase: StateFlow<OfflinePhase> = _phase.asStateFlow()

    val balance: StateFlow<Long> = flow { emitAll(engine.vault().spendableBalance()) }
        .catch { emit(0L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val denominations: StateFlow<List<Long>> = flow { emitAll(engine.vault().denominations()) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<OutboxEntity>> = flow { emitAll(engine.database().outbox().observeRecent(12)) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vaultSupported: Boolean = engine.keys.capability().supported
    val canReceive: Boolean get() = container.isBound

    private var payer: PayerSession? = null
    private var bleServer: BlePeripheralServer? = null

    // ── Pay nearby over BLE ───────────────────────────────────────────────
    fun payNearby() = launchSafely {
        _phase.value = OfflinePhase.Working("Finding the nearest Fortress receiver…")
        val device = BleCentralTransport.findNearestPayee(app)
        _phase.value = OfflinePhase.Working("Securing channel…")
        val session = PayerSession(BleCentralTransport.connect(app, device), engine.trust).also { payer = it }
        val offer = session.negotiate()
        _phase.value = OfflinePhase.Confirm(offer, offer.requestedAmountPaise.takeIf { it > 0 })
    }

    fun confirmNearby(amountPaise: Long, auth: AuthGate) = launchSafely {
        val confirm = _phase.value as? OfflinePhase.Confirm ?: return@launchSafely
        val session = payer ?: return@launchSafely
        if (!withinOfflineCap(amountPaise)) return@launchSafely
        if (!auth.authenticate("Authorize offline payment", "${Inr.format(amountPaise)} to ${confirm.offer.receiver.displayName}")) {
            throw OfflineProtocolException(Reason.STATE)
        }
        settle(session, confirm.offer, amountPaise)
    }

    // ── Tap to pay over NFC (armed before the tap) ────────────────────────
    fun armTap(amountPaise: Long, auth: AuthGate) = launchSafely {
        if (!withinOfflineCap(amountPaise)) return@launchSafely
        if (!auth.authenticate("Arm Tap & Pay", "Up to ${Inr.format(amountPaise)} · valid 30 seconds")) {
            throw OfflineProtocolException(Reason.STATE)
        }
        _phase.value = OfflinePhase.ArmedTap(amountPaise)
    }

    /** Called from the NFC reader thread once a receiver is selected. */
    fun onTapConnected(transport: OfflineTransport) = launchSafely {
        val armed = _phase.value as? OfflinePhase.ArmedTap ?: return@launchSafely transport.close()
        _phase.value = OfflinePhase.Working("Hold steady…")
        PayerSession(transport, engine.trust).use { session ->
            val offer = session.negotiate()
            if (offer.requestedAmountPaise > 0 && offer.requestedAmountPaise != armed.amountPaise) {
                throw OfflineProtocolException(Reason.AMOUNT)
            }
            settle(session, offer, armed.amountPaise)
        }
    }

    /** Offline caps come from server policy (RBI/NPCI); larger amounts need an online UPI payment. */
    private fun withinOfflineCap(amountPaise: Long): Boolean {
        val cap = container.limits.value.offlinePerTxnPaise
        if (amountPaise <= cap) return true
        _phase.value = OfflinePhase.Info(
            "Offline payments are capped at ${Inr.format(cap)} each by RBI rules. Pay larger amounts with UPI when you're online.",
        )
        return false
    }

    private suspend fun settle(session: PayerSession, offer: NegotiatedOffer, amountPaise: Long) {
        _phase.value = OfflinePhase.Working("Signing inside StrongBox…")
        val prepared = engine.vault().authorizePayment(offer, amountPaise)
        // From here the payment is durable in the outbox and will settle even if the radio drops.
        ReconciliationWorker.enqueue(app)
        _phase.value = OfflinePhase.Working("Delivering…")
        val receipt = session.pay(offer, prepared.paymentId, prepared.bundle)
        _phase.value = OfflinePhase.Success(amountPaise, receipt.receiverName, incoming = false)
        closePayer()
    }

    // ── Vault management (online) ─────────────────────────────────────────
    /** Attested single-use keys → issuer HSM certificates → installed notes. */
    fun loadVault(amountPaise: Long) = launchSafely {
        if (container.demoMode) {
            _phase.value = OfflinePhase.Info("Loading the vault needs a bound device and the Fortress issuer. Demo mode can't mint notes.")
            return@launchSafely
        }
        val room = container.limits.value.offlineWalletPaise - balance.value
        if (amountPaise > room) {
            _phase.value = OfflinePhase.Info(
                "Your vault can hold ${Inr.format(container.limits.value.offlineWalletPaise)} under RBI's offline rules. " +
                    "You can add up to ${Inr.format(room.coerceAtLeast(0))} more.",
            )
            return@launchSafely
        }
        val account = container.payments.accounts().firstOrNull { it.primary } ?: throw OfflineProtocolException(Reason.STATE)
        _phase.value = OfflinePhase.Working("Requesting an issuance challenge…")
        val challenge = container.issuer.challenge()
        _phase.value = OfflinePhase.Working("Generating single-use keys inside StrongBox…")
        val vault = engine.vault()
        val keys = vault.prepareNoteKeys(amountPaise, challenge)
        try {
            _phase.value = OfflinePhase.Working("Issuer is verifying attestation and debiting ${Inr.format(amountPaise)}…")
            vault.installNotes(container.issuer.issue(challenge, keys, account.ref))
        } catch (e: Exception) {
            keys.forEach { engine.keys.delete(it.keyAlias) } // never leave uncertified keys behind
            throw e
        }
        _phase.value = OfflinePhase.Info("Vault loaded · ${Inr.format(amountPaise)} in ${keys.size} hardware-sealed notes")
    }

    /** Returns every unspent note to the bank account (each note key signs once, then self-destructs). */
    fun redeem() = launchSafely {
        val prepared = engine.vault().redeemAll() ?: return@launchSafely
        ReconciliationWorker.enqueue(app)
        _phase.value = OfflinePhase.Info("${Inr.format(prepared.amountPaise)} is returning to your bank")
    }

    // ── Receive (BLE + NFC simultaneously) ─────────────────────────────────
    fun startReceiving(amountPaise: Long) = launchSafely {
        stopReceivingInternal()
        val factory: suspend () -> OfflineResponder = {
            engine.newPayeeSession(amountPaise) { event -> onPayeeEvent(amountPaise, event) }
        }
        ReceiveSessionRegistry.activate(factory)
        bleServer = BlePeripheralServer(app, viewModelScope, factory) { e -> fail(e) }.also { it.start() }
        _phase.value = OfflinePhase.Receiving(amountPaise, null)
    }

    private fun onPayeeEvent(requested: Long, event: PayeeEvent) {
        when (event) {
            is PayeeEvent.PayerConnected -> _phase.value = OfflinePhase.Receiving(requested, event.sas)
            is PayeeEvent.Received -> {
                _phase.value = OfflinePhase.Success(event.amountPaise, "Offline payer", incoming = true)
                ReconciliationWorker.enqueue(app)
            }
            is PayeeEvent.Failed -> _phase.value = OfflinePhase.Failed(message(OfflineProtocolException(event.reason)))
        }
    }

    fun reset() {
        stopReceivingInternal()
        closePayer()
        _phase.value = OfflinePhase.Idle
    }

    override fun onCleared() {
        stopReceivingInternal()
        closePayer()
    }

    private fun stopReceivingInternal() {
        ReceiveSessionRegistry.deactivate()
        bleServer?.close()
        bleServer = null
    }

    private fun closePayer() {
        payer?.close()
        payer = null
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closePayer()
                fail(e)
            }
        }
    }

    private fun fail(e: Throwable) {
        _phase.value = OfflinePhase.Failed(message(e))
    }

    private fun message(e: Throwable): String = when (e) {
        is UserNotAuthenticatedException -> "Authentication expired. Confirm again to sign."
        is SecurityException -> "Allow Nearby devices permission to pay offline."
        is ApiException -> if (e.status == 0) "This needs the internet. Try again when you're online." else "The server refused this request (${e.code})."
        is IOException -> e.message ?: "Connection lost. Nothing was charged unless a receipt is shown."
        is OfflineProtocolException -> when (e.reason) {
            Reason.INSUFFICIENT_FUNDS -> "Not enough offline balance."
            Reason.AMOUNT -> "Amount doesn't match the receiver's request."
            Reason.EXPIRED -> "Some notes expired. Reconnect to refresh the vault."
            Reason.UNKNOWN_ISSUER, Reason.BAD_ISSUER_SIGNATURE, Reason.BAD_PAYEE_SIGNATURE ->
                "Receiver could not be verified. Payment blocked."
            Reason.ENFORCEMENT_POLICY -> "This phone can't hold offline notes securely."
            Reason.STATE -> "Cancelled."
            else -> "Secure channel failed (${e.reason.name.lowercase()}). Nothing was charged."
        }
        else -> "Something went wrong. Nothing was charged."
    }
}
