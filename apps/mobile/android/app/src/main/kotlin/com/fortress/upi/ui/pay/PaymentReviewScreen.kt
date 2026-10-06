package com.fortress.upi.ui.pay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GhostButton
import com.fortress.upi.core.designsystem.GlassCard
import com.fortress.upi.core.designsystem.GoldButton
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.core.designsystem.rememberHaptics
import com.fortress.upi.data.displayName
import com.fortress.upi.di.AppContainer
import com.fortress.upi.ui.components.AmountKeypad
import com.fortress.upi.ui.components.Avatar
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.RoundIconButton
import com.fortress.upi.ui.components.StatusPill
import com.fortress.upi.ui.components.keypadToPaise
import com.fortress.upi.upi.UpiPaymentIntent

/**
 * Review → authorize. "Pay" creates the payment server-side, then hands off to
 * the NPCI Common Library for the UPI PIN (Fortress never renders a PIN pad),
 * then follows the status to a final state.
 */
@Composable
fun PaymentReviewScreen(
    container: AppContainer,
    intent: UpiPaymentIntent,
    online: Boolean,
    onUseOffline: () -> Unit,
    onPaid: (Receipt) -> Unit,
    onBack: () -> Unit,
) {
    val vm: PaymentViewModel = viewModel(key = intent.toString(), factory = viewModelFactory { initializer { PaymentViewModel(container) } })
    val phase by vm.phase.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val fixedAmount = intent.amountPaise
    var entry by remember { mutableStateOf("") }
    val amount = fixedAmount ?: keypadToPaise(entry)

    LaunchedEffect(phase) {
        when (val p = phase) {
            is PayPhase.Done -> onPaid(p.receipt)
            is PayPhase.Failed -> haptics.reject()
            else -> Unit
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(FortressSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Glyph.BACK, "Back", onBack)
            Spacer(Modifier.weight(1f))
            if (!online) StatusPill("NO NETWORK", FortressColors.Sapphire)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(intent.displayName())
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(intent.displayName(), style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
                    Text(intent.payeeVpa, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
                }
            }
            if (intent.carriesSignature) {
                Spacer(Modifier.height(12.dp))
                StatusPill("SIGNED QR", FortressColors.Emerald)
            }
            intent.note?.let {
                Spacer(Modifier.height(10.dp))
                Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
            }
        }

        Text(
            text = amount?.let(Inr::format) ?: "₹0",
            style = MaterialTheme.typography.displayLarge,
            color = if (amount == null) FortressColors.InkDim else FortressColors.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        (phase as? PayPhase.Failed)?.let {
            Text(it.message, style = MaterialTheme.typography.bodyMedium, color = FortressColors.Danger, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.weight(1f))
        if (fixedAmount == null) AmountKeypad(entry, { entry = it; vm.reset() }, haptics)

        Box(Modifier.fillMaxWidth()) {
            when {
                !online -> GhostButton("Pay from offline vault", onUseOffline, Modifier.fillMaxWidth())
                phase == PayPhase.Processing -> GoldButton("Waiting for your bank…", {}, Modifier.fillMaxWidth(), enabled = false)
                else -> GoldButton(
                    text = amount?.let { "Pay ${Inr.format(it)}" } ?: "Enter amount",
                    enabled = amount != null,
                    onClick = { amount?.let { vm.pay(intent, it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
