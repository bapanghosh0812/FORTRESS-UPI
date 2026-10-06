package com.fortress.upi.ui.offline

import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fortress.upi.core.designsystem.AmbientAura
import com.fortress.upi.core.designsystem.AmountText
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressShape
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GhostButton
import com.fortress.upi.core.designsystem.GlassCard
import com.fortress.upi.core.designsystem.GoldButton
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.core.designsystem.SuccessBurst
import com.fortress.upi.core.designsystem.liquidEdge
import com.fortress.upi.core.designsystem.rememberHaptics
import com.fortress.upi.core.offline.store.OutboxDirection
import com.fortress.upi.core.offline.store.OutboxEntity
import com.fortress.upi.core.offline.store.SyncState
import com.fortress.upi.core.offline.transport.ble.BlePermissions
import com.fortress.upi.core.offline.transport.nfc.NfcPayReader
import com.fortress.upi.di.AppContainer
import com.fortress.upi.platform.AuthGate
import com.fortress.upi.ui.components.AmountKeypad
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.GlyphIcon
import com.fortress.upi.ui.components.RoundIconButton
import com.fortress.upi.ui.components.SectionLabel
import com.fortress.upi.ui.components.StatusPill
import com.fortress.upi.ui.components.keypadToPaise

private enum class Intent { TAP, NEARBY, RECEIVE }

/**
 * Offline Mode: when connectivity drops the screen "wakes" — a breathing
 * emerald/sapphire aura, a three-beat haptic heartbeat, and the hardware-backed
 * secure balance front and centre. Every action below works with zero network.
 */
@Composable
fun OfflineScreen(container: AppContainer, authGate: AuthGate, online: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: OfflineViewModel = viewModel(factory = viewModelFactory { initializer { OfflineViewModel(app, container) } })
    val phase by vm.phase.collectAsStateWithLifecycle()
    val balance by vm.balance.collectAsStateWithLifecycle()
    val notes by vm.denominations.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    var burst by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Intent?>(null) }
    var entry by remember { mutableStateOf("") }

    LaunchedEffect(online) { if (!online) haptics.ambientPulse() }
    LaunchedEffect(phase) {
        when (phase) {
            is OfflinePhase.Success -> {
                haptics.success()
                burst++
            }
            is OfflinePhase.Failed -> haptics.reject()
            is OfflinePhase.Confirm -> haptics.confirm()
            else -> Unit
        }
    }

    val blePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            when (pending) {
                Intent.NEARBY -> vm.payNearby()
                Intent.RECEIVE -> vm.startReceiving(keypadToPaise(entry) ?: 0L)
                else -> Unit
            }
        }
    }

    // NFC reader mode only while armed: the phone is never a "reader" by accident.
    val activity = LocalActivity.current
    if (phase is OfflinePhase.ArmedTap && activity != null) {
        DisposableEffect(activity) {
            val reader = NfcPayReader(activity, onConnected = vm::onTapConnected, onError = { })
            reader.enable()
            onDispose { reader.disable() }
        }
    }

    AmbientAura(active = !online, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = FortressSpace.gutter, end = FortressSpace.gutter, top = FortressSpace.gutter, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "bar") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(
                        Glyph.BACK,
                        "Back",
                        onClick = {
                            vm.reset()
                            onBack()
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    if (online) StatusPill("ONLINE · VAULT READY", FortressColors.Emerald) else StatusPill("OFFLINE · SECURE MODE", FortressColors.Sapphire)
                }
            }
            item(key = "balance") { SecureBalance(balance, notes, vm.vaultSupported) }
            item(key = "phase") {
                AnimatedContent(phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { p ->
                    PhasePanel(
                        phase = p,
                        entry = entry,
                        onEntry = { entry = it },
                        haptics = haptics,
                        canReceive = vm.canReceive,
                        online = online,
                        onLoad = vm::loadVault,
                        onRedeem = vm::redeem,
                        onTap = {
                            keypadToPaise(entry)?.let { vm.armTap(it, authGate) }
                        },
                        onNearby = {
                            pending = Intent.NEARBY
                            if (BlePermissions.granted(app, BlePermissions.PAYER)) vm.payNearby() else blePermissions.launch(BlePermissions.PAYER)
                        },
                        onReceive = {
                            pending = Intent.RECEIVE
                            if (BlePermissions.granted(app, BlePermissions.PAYEE)) vm.startReceiving(keypadToPaise(entry) ?: 0L) else blePermissions.launch(BlePermissions.PAYEE)
                        },
                        onConfirm = { amount -> vm.confirmNearby(amount, authGate) },
                        onReset = {
                            entry = ""
                            vm.reset()
                        },
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-label") { SectionLabel("OFFLINE LEDGER · SETTLES AUTOMATICALLY") }
                items(recent, key = { it.id }) { PendingRow(it, Modifier.animateItem()) }
            }
        }
        SuccessBurst(trigger = burst, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecureBalance(balance: Long, notes: List<Long>, supported: Boolean) {
    GlassCard(Modifier.fillMaxWidth().liquidEdge(colors = listOf(FortressColors.Emerald, FortressColors.Ink, FortressColors.Sapphire))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.VAULT, tint = FortressColors.Emerald, size = 18.dp)
            SectionLabel("OFFLINE SECURE BALANCE", Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(10.dp))
        AmountText(balance)
        Spacer(Modifier.height(6.dp))
        Text(
            if (supported) "${notes.size} hardware-sealed notes · single-use StrongBox keys" else "This device can't enforce single-use keys in hardware",
            style = MaterialTheme.typography.bodyMedium,
            color = if (supported) FortressColors.InkMuted else FortressColors.Danger,
        )
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notes.forEach { NoteChip(it) }
            }
        }
    }
}

@Composable
private fun NoteChip(paise: Long) {
    val shape = RoundedCornerShape(FortressShape.chip)
    Text(
        Inr.formatWhole(paise),
        style = MaterialTheme.typography.labelLarge,
        color = FortressColors.GoldAccent,
        modifier = Modifier
            .clip(shape)
            .background(FortressColors.Gold.copy(alpha = 0.08f))
            .border(1.dp, FortressColors.Gold.copy(alpha = 0.4f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PhasePanel(
    phase: OfflinePhase,
    entry: String,
    onEntry: (String) -> Unit,
    haptics: com.fortress.upi.core.designsystem.FortressHaptics,
    canReceive: Boolean,
    online: Boolean,
    onLoad: (Long) -> Unit,
    onRedeem: () -> Unit,
    onTap: () -> Unit,
    onNearby: () -> Unit,
    onReceive: () -> Unit,
    onConfirm: (Long) -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (phase) {
            OfflinePhase.Idle -> {
                Text(
                    keypadToPaise(entry)?.let(Inr::format) ?: "Enter amount",
                    style = MaterialTheme.typography.displayMedium,
                    color = if (entry.isEmpty()) FortressColors.InkDim else FortressColors.Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                AmountKeypad(entry, onEntry, haptics)
                GoldButton("Tap & Pay · NFC", onTap, Modifier.fillMaxWidth(), enabled = keypadToPaise(entry) != null)
                GhostButton("Pay nearby · Bluetooth", onNearby, Modifier.fillMaxWidth())
                if (canReceive) GhostButton("Receive offline", onReceive, Modifier.fillMaxWidth())
                if (online) {
                    SectionLabel("MANAGE VAULT · NEEDS INTERNET")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(50_000L, 100_000L, 200_000L).forEach { amt ->
                            GhostButton("+ ${Inr.formatWhole(amt)}", { onLoad(amt) }, Modifier.weight(1f))
                        }
                    }
                    GhostButton("Return all notes to bank", onRedeem, Modifier.fillMaxWidth())
                }
            }
            is OfflinePhase.Info -> {
                StatusCard(phase.message, accent = FortressColors.Emerald)
                GoldButton("Done", onReset, Modifier.fillMaxWidth())
            }
            is OfflinePhase.Working -> StatusCard(phase.label)
            is OfflinePhase.ArmedTap -> TapRadar("Hold near the receiver's phone", Inr.format(phase.amountPaise))
            is OfflinePhase.Confirm -> {
                val amount = phase.amountPaise ?: keypadToPaise(entry)
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionLabel("VERIFIED RECEIVER")
                    Text(phase.offer.receiver.displayName, style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("Match code on both screens", style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
                    Text(phase.offer.sas, style = MaterialTheme.typography.displayMedium, color = FortressColors.GoldAccent)
                }
                GoldButton(
                    text = amount?.let { "Confirm ${Inr.format(it)}" } ?: "Enter amount",
                    onClick = { amount?.let(onConfirm) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = amount != null,
                )
                GhostButton("Cancel", onReset, Modifier.fillMaxWidth())
            }
            is OfflinePhase.Receiving -> {
                TapRadar(
                    title = if (phase.sas != null) "Payer connected · code ${phase.sas}" else "Ready to receive · tap or stay nearby",
                    amount = if (phase.amountPaise > 0) Inr.format(phase.amountPaise) else "Any amount",
                )
                GhostButton("Stop receiving", onReset, Modifier.fillMaxWidth())
            }
            is OfflinePhase.Success -> {
                StatusCard(
                    (if (phase.incoming) "Received " else "Paid ") + Inr.format(phase.amountPaise) +
                        (if (phase.incoming) "" else " to ${phase.counterparty}") + " · settles on next sync",
                    accent = FortressColors.Emerald,
                )
                GoldButton("Done", onReset, Modifier.fillMaxWidth())
            }
            is OfflinePhase.Failed -> {
                StatusCard(phase.message, accent = FortressColors.Danger)
                GhostButton("Try again", onReset, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StatusCard(text: String, accent: androidx.compose.ui.graphics.Color = FortressColors.Ink) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = accent)
    }
}

/** Expanding rings — the NFC/BLE "field" made visible. Draw-phase only. */
@Composable
private fun TapRadar(title: String, amount: String) {
    val transition = rememberInfiniteTransition(label = "radar")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800), RepeatMode.Restart), label = "t")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val ring = FortressColors.Emerald
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                for (i in 0 until 3) {
                    val p = (t + i / 3f) % 1f
                    drawCircle(
                        color = ring.copy(alpha = (1f - p) * 0.6f),
                        radius = size.minDimension / 2f * (0.25f + 0.75f * p),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            GlyphIcon(Glyph.TAP, tint = FortressColors.Emerald, size = 36.dp)
        }
        Text(amount, style = MaterialTheme.typography.displayMedium, color = FortressColors.Ink)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = FortressColors.InkMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PendingRow(item: OutboxEntity, modifier: Modifier = Modifier) {
    val (label, color) = when (item.syncState) {
        SyncState.PENDING -> "PENDING SYNC" to FortressColors.Sapphire
        SyncState.SYNCED -> "SETTLED" to FortressColors.Emerald
        SyncState.REJECTED -> "REJECTED" to FortressColors.Danger
    }
    val sign = when (item.direction) {
        OutboxDirection.RECEIVED -> "+ "
        OutboxDirection.SENT -> "− "
        OutboxDirection.REDEEM -> "↺ "
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.control))
            .background(FortressColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.counterpartyName, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
        Text(sign + Inr.format(item.amountPaise), style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
    }
}
