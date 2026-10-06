package com.fortress.upi.ui.pay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.toHex
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.data.ApiException
import com.fortress.upi.data.PaymentState
import com.fortress.upi.data.displayName
import com.fortress.upi.di.AppContainer
import com.fortress.upi.upi.UpiPaymentIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PayPhase {
    data object Editing : PayPhase
    data object Processing : PayPhase
    data class Done(val receipt: Receipt) : PayPhase
    data class Failed(val message: String) : PayPhase
}

/** Online UPI payment: PSP create → NPCI CL PIN → authorize → status. */
class PaymentViewModel(private val container: AppContainer) : ViewModel() {
    private val state = MutableStateFlow<PayPhase>(PayPhase.Editing)
    val phase: StateFlow<PayPhase> = state.asStateFlow()

    fun pay(intent: UpiPaymentIntent, amountPaise: Long) {
        if (state.value == PayPhase.Processing) return
        state.value = PayPhase.Processing
        viewModelScope.launch {
            state.value = try {
                if (container.demoMode) demo(intent, amountPaise) else live(intent, amountPaise)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                PayPhase.Failed("UPI PIN entry isn't available in this build yet.")
            } catch (e: ApiException) {
                PayPhase.Failed(if (e.status == 0) "No connection. Use the offline vault instead." else "Payment was declined (${e.code}).")
            }
        }
    }

    fun reset() {
        state.value = PayPhase.Editing
    }

    private suspend fun live(intent: UpiPaymentIntent, amountPaise: Long): PayPhase {
        val account = container.payments.accounts().firstOrNull { it.primary } ?: return PayPhase.Failed("Link a bank account first.")
        val result = container.payments.pay(intent, amountPaise, account) ?: return PayPhase.Editing
        return when (result.state) {
            PaymentState.SUCCESS -> PayPhase.Done(
                Receipt(
                    amountPaise, intent.displayName(), incoming = false,
                    rows = listOf(
                        "UPI reference" to (result.upiRef ?: "—"),
                        "From" to "${account.bankName} ${account.maskedNumber}",
                        "To" to intent.payeeVpa,
                    ),
                ),
            )
            PaymentState.DEEMED, PaymentState.PENDING, PaymentState.CREATED -> PayPhase.Failed("Your bank is still confirming this payment. We'll notify you; don't pay again.")
            PaymentState.FAILED -> PayPhase.Failed("Payment failed: ${result.failureReason ?: "declined by bank"}. No money was debited.")
        }
    }

    /** Debug demo mode only: shows the motion system without moving money. */
    private suspend fun demo(intent: UpiPaymentIntent, amountPaise: Long): PayPhase {
        delay(1_200)
        val ref = SecureBytes.random(6).toHex().take(12).uppercase()
        return PayPhase.Done(
            Receipt(
                amountPaise, intent.displayName(), incoming = false,
                rows = listOf("Mode" to "Demo · no money moved", "Reference" to ref, "Amount" to Inr.format(amountPaise)),
            ),
        )
    }
}
