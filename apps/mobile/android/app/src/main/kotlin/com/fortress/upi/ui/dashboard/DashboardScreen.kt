package com.fortress.upi.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fortress.upi.R
import com.fortress.upi.core.designsystem.AmountText
import com.fortress.upi.core.designsystem.FortressBrushes
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressShape
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GlassCard
import com.fortress.upi.core.designsystem.GoldButton
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.core.designsystem.LocalFortressPalette
import com.fortress.upi.core.designsystem.pressScale
import com.fortress.upi.core.designsystem.rememberHaptics
import com.fortress.upi.data.ApiException
import com.fortress.upi.data.DirectoryRepository
import com.fortress.upi.data.UserProfile
import com.fortress.upi.data.VerifiedPayee
import com.fortress.upi.di.AppContainer
import com.fortress.upi.ui.components.Avatar
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.GlyphIcon
import com.fortress.upi.ui.components.SectionLabel
import com.fortress.upi.ui.components.StatusPill
import com.fortress.upi.upi.UpiPaymentIntent
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(container: AppContainer) : ViewModel() {
    /** Live offline-vault balance from the encrypted store (0 until the vault is loaded). */
    val offlineBalance: StateFlow<Long> = flow { emitAll(container.offlineEngine.vault().spendableBalance()) }
        .catch { emit(0L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
}

/** The three ways to pay someone without a QR code. */
enum class PayEntry(val title: String) {
    UPI_ID("Pay a UPI ID"),
    PHONE("Pay a phone number"),
    BANK("Pay a bank account"),
}

@Composable
fun DashboardScreen(
    container: AppContainer,
    online: Boolean,
    onScan: () -> Unit,
    onOffline: () -> Unit,
    onHistory: () -> Unit,
    onMyQr: () -> Unit,
    onPay: (UpiPaymentIntent) -> Unit,
) {
    val vm: DashboardViewModel = viewModel(factory = viewModelFactory { initializer { DashboardViewModel(container) } })
    val offlineBalance by vm.offlineBalance.collectAsStateWithLifecycle()
    val hide by container.prefs.hideBalances.collectAsStateWithLifecycle()
    val serverProfile by container.profile.collectAsStateWithLifecycle()
    val limits by container.limits.collectAsStateWithLifecycle()
    val profile = serverProfile ?: DemoProfile.takeIf { container.demoMode }
    var entry by rememberSaveable { mutableStateOf<PayEntry?>(null) }
    val haptics = rememberHaptics()

    // One lazy list for the whole screen: a single scroll container, stable keys,
    // and items composed only when visible keep 120 Hz scrolling jank-free.
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = FortressSpace.gutter, end = FortressSpace.gutter, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "hero") {
            Hero(
                profile = profile,
                online = online,
                hidden = hide,
                totalPaise = DemoData.BANK_BALANCE_PAISE + offlineBalance,
                onToggleHidden = { container.prefs.setHideBalances(!hide) },
                onMyQr = onMyQr,
                onOffline = onOffline,
            )
        }
        item(key = "grid") {
            FeatureGrid { action ->
                haptics.tick()
                when (action) {
                    Feature.SCAN -> onScan()
                    Feature.UPI_ID -> entry = PayEntry.UPI_ID
                    Feature.PHONE -> entry = PayEntry.PHONE
                    Feature.BANK -> entry = PayEntry.BANK
                    Feature.TAP, Feature.RECEIVE -> onOffline()
                    Feature.MY_QR -> onMyQr()
                    Feature.HISTORY -> onHistory()
                }
            }
        }
        item(key = "vault") { VaultCard(offlineBalance, hide, limits.offlineWalletPaise, onOffline) }
        item(key = "people-label") { SectionLabel("PEOPLE") }
        item(key = "people") {
            People { c ->
                haptics.tick()
                onPay(UpiPaymentIntent(c.vpa, c.name, null, null, null, null, carriesSignature = false))
            }
        }
        item(key = "spend") { SpendCard(DemoData.MONTH_SPEND_PAISE) }
        item(key = "recent-label") { SectionLabel("RECENT ACTIVITY", Modifier.padding(top = 4.dp)) }
        items(DemoData.recent, key = { it.id }) { tx -> TxRow(tx, Modifier.animateItem()) }
        item(key = "shield") { ShieldFooter() }
    }

    entry?.let { kind ->
        PayEntrySheet(
            kind,
            container,
            onDismiss = { entry = null },
            onIntent = {
                entry = null
                onPay(it)
            },
        )
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun Hero(
    profile: UserProfile?,
    online: Boolean,
    hidden: Boolean,
    totalPaise: Long,
    onToggleHidden: () -> Unit,
    onMyQr: () -> Unit,
    onOffline: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.card))
            .background(FortressBrushes.hero)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.fortress_mark), contentDescription = null, modifier = Modifier.size(40.dp))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("FORTRESS", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.14.em), color = FortressColors.HeroGold)
                Text("UPI · PAY ANYTIME", style = MaterialTheme.typography.labelSmall, color = FortressColors.OnHero.copy(alpha = 0.7f))
            }
            AnimatedContent(online, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "net") { isOnline ->
                if (isOnline) {
                    StatusPill("SECURE", Color(0xFF7FE7BF))
                } else {
                    StatusPill("OFFLINE · VAULT", FortressColors.HeroGold, Modifier.clickable(onClick = onOffline))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            greeting() + (profile?.name?.substringBefore(' ')?.let { ", $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = FortressColors.OnHero.copy(alpha = 0.82f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(hidden, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mask", modifier = Modifier.weight(1f)) { h ->
                AmountText(totalPaise, masked = h, style = MaterialTheme.typography.displayMedium, color = FortressColors.OnHero)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Switch, onClickLabel = if (hidden) "Show balance" else "Hide balance", onClick = onToggleHidden)
                    .padding(8.dp),
            ) { GlyphIcon(Glyph.EYE, tint = FortressColors.OnHero.copy(alpha = 0.85f), size = 22.dp) }
        }
        Text("Bank + offline vault", style = MaterialTheme.typography.labelSmall, color = FortressColors.OnHero.copy(alpha = 0.6f))
        if (profile != null) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "UPI ID: ${profile.vpa}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FortressColors.OnHero.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                HeroChip("Copy", Glyph.COPY) { copyToClipboard(context, "UPI ID", profile.vpa) }
                Spacer(Modifier.size(6.dp))
                HeroChip("My QR", Glyph.QR, onMyQr)
            }
        }
    }
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun HeroChip(label: String, glyph: Glyph, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, tint = FortressColors.OnHero, size = 13.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = FortressColors.OnHero, modifier = Modifier.padding(start = 6.dp))
    }
}

private enum class Feature(val label: String, val glyph: Glyph) {
    SCAN("Scan & pay", Glyph.SCAN),
    UPI_ID("Pay UPI ID", Glyph.AT),
    PHONE("Pay phone number", Glyph.PHONE),
    BANK("Bank transfer", Glyph.BANK),
    TAP("Tap & Pay", Glyph.TAP),
    RECEIVE("Receive offline", Glyph.RECEIVE),
    MY_QR("My QR", Glyph.QR),
    HISTORY("History", Glyph.HISTORY),
}

@Composable
private fun FeatureGrid(onAction: (Feature) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.card))
            .background(FortressColors.Surface)
            .border(1.dp, FortressColors.Line, RoundedCornerShape(FortressShape.card))
            .padding(vertical = 18.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Feature.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { f -> FeatureTile(f, gold = f == Feature.SCAN, modifier = Modifier.weight(1f)) { onAction(f) } }
            }
        }
    }
}

@Composable
private fun FeatureTile(f: Feature, gold: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val dark = LocalFortressPalette.current.isDark
    Column(
        modifier.pressScale(interaction, 0.93f).clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (gold) FortressBrushes.gold else SolidColor(FortressColors.Tile)),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(f.glyph, tint = if (gold) FortressColors.OnGold else if (dark) FortressColors.GoldAccent else FortressColors.Ink, size = 24.dp)
        }
        Text(
            f.label,
            style = MaterialTheme.typography.labelMedium,
            color = FortressColors.Ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun VaultCard(balancePaise: Long, hidden: Boolean, walletCapPaise: Long, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.card))
            .background(Brush.linearGradient(listOf(Color(0xFF0B1838), Color(0xFF14305E), Color(0xFF0E5A4A))))
            .clickable(role = Role.Button, onClickLabel = "Open offline vault", onClick = onOpen)
            .padding(18.dp),
    ) {
        Text("OFFLINE SECURE BALANCE", style = MaterialTheme.typography.labelSmall, color = FortressColors.OnHero.copy(alpha = 0.7f))
        Text(
            if (hidden) "₹ ••••" else Inr.format(balancePaise),
            style = MaterialTheme.typography.headlineSmall,
            color = FortressColors.OnHero,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "Pay without internet · holds up to ${Inr.formatWhole(walletCapPaise)}",
            style = MaterialTheme.typography.bodyMedium,
            color = FortressColors.OnHero.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun People(onContact: (DemoContact) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DemoData.contacts.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { c ->
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier
                            .weight(1f)
                            .pressScale(interaction, 0.92f)
                            .clickable(interaction, indication = null, role = Role.Button) { onContact(c) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(c.name, size = 50.dp)
                        Text(
                            c.name.substringBefore(' '),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FortressColors.InkMuted,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SpendCard(monthSpendPaise: Long) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionLabel("THIS MONTH")
        Text(Inr.formatWhole(monthSpendPaise), style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        SpendSparkline(DemoData.spendSeries, Modifier.fillMaxWidth().height(56.dp))
    }
}

@Composable
private fun SpendSparkline(series: FloatArray, modifier: Modifier) {
    val fillColor = FortressColors.Gold
    val lineEnd = FortressColors.GoldAccent
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val max = series.max()
        val dx = size.width / (series.size - 1)
        val line = Path()
        series.forEachIndexed { i, v ->
            val p = Offset(i * dx, size.height * (1f - v / max) * 0.9f + size.height * 0.05f)
            if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(fillColor.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(line, Brush.horizontalGradient(listOf(FortressColors.GoldDeep, lineEnd)), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun TxRow(tx: DemoTx, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.control))
            .background(FortressColors.Surface)
            .border(1.dp, FortressColors.Line, RoundedCornerShape(FortressShape.control))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(tx.counterparty, size = 42.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(tx.counterparty, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tx.subtitle, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (tx.credit) "+ " else "− ") + Inr.format(tx.amountPaise),
                style = MaterialTheme.typography.titleMedium,
                color = if (tx.credit) FortressColors.Emerald else FortressColors.Ink,
            )
            if (tx.status == TxStatus.OFFLINE_PENDING) {
                Text("SETTLES ON SYNC", style = MaterialTheme.typography.labelSmall, color = FortressColors.Sapphire)
            }
        }
    }
}

@Composable
private fun ShieldFooter() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.control))
            .background(FortressColors.SurfaceRaised)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.fortress_mark), contentDescription = null, modifier = Modifier.size(30.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text("Fortress Shield is on", style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
            Text(
                "Device-bound keys · data stored only in India · every payment signed",
                style = MaterialTheme.typography.bodyMedium,
                color = FortressColors.InkMuted,
            )
        }
    }
}

/**
 * Pay without a QR. The payee's registered name is fetched from their bank
 * (UPI validate-address / mobile mapper) and shown on the review screen before
 * any money moves. Debug demo mode has no PSP, so names stay unverified there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayEntrySheet(kind: PayEntry, container: AppContainer, onDismiss: () -> Unit, onIntent: (UpiPaymentIntent) -> Unit) {
    val scope = rememberCoroutineScope()
    var primary by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var ifsc by rememberSaveable { mutableStateOf("") }
    var holder by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val valid = when (kind) {
        PayEntry.UPI_ID -> DirectoryRepository.isVpa(primary.trim())
        PayEntry.PHONE -> DirectoryRepository.isMobile(primary)
        PayEntry.BANK -> DirectoryRepository.isAccount(primary) && primary == confirm && DirectoryRepository.isIfsc(ifsc) && holder.trim().length >= 3
    }

    fun submit() {
        if (!valid || busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                when {
                    container.demoMode -> VerifiedPayee(
                        vpa = when (kind) {
                            PayEntry.UPI_ID -> primary.trim()
                            PayEntry.PHONE -> "$primary@fortress"
                            PayEntry.BANK -> DirectoryRepository.accountVpa(primary, ifsc)
                        },
                        name = if (kind == PayEntry.BANK) holder.trim() else "",
                    )
                    kind == PayEntry.PHONE -> container.directory.resolveMobile(primary)
                    kind == PayEntry.BANK -> container.directory.verifyVpa(DirectoryRepository.accountVpa(primary, ifsc))
                    else -> container.directory.verifyVpa(primary.trim())
                }
            }
            busy = false
            result
                .onSuccess { p -> onIntent(UpiPaymentIntent(p.vpa, p.name.ifBlank { null }, null, null, null, null, carriesSignature = false)) }
                .onFailure { e ->
                    error = when ((e as? ApiException)?.code) {
                        "vpa_not_found", "not_found" -> "No UPI account found for this. Check it and try again."
                        "network" -> "No internet connection. Check it and try again."
                        else -> "We couldn't verify this right now. Try again in a moment."
                    }
                }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = FortressColors.Surface, contentColor = FortressColors.Ink) {
        Column(
            Modifier.fillMaxWidth().padding(start = FortressSpace.gutter, end = FortressSpace.gutter, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(kind.title, style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink)
            when (kind) {
                PayEntry.UPI_ID -> Field(primary, {
                    primary = it.trim()
                    error = null
                }, "UPI ID", "name@bank", KeyboardType.Email)
                PayEntry.PHONE -> Field(
                    primary,
                    { v ->
                        primary = v.filter(Char::isDigit).take(10)
                        error = null
                    },
                    "Mobile number linked to UPI",
                    "98765 43210",
                    KeyboardType.Phone,
                    prefix = "+91 ",
                )
                PayEntry.BANK -> {
                    Field(primary, { v ->
                        primary = v.filter(Char::isDigit).take(18)
                        error = null
                    }, "Account number", "", KeyboardType.NumberPassword, secret = true)
                    Field(confirm, { v -> confirm = v.filter(Char::isDigit).take(18) }, "Re-enter account number", "", KeyboardType.Number)
                    if (confirm.isNotEmpty() && confirm != primary) {
                        Text("Account numbers don't match", style = MaterialTheme.typography.bodyMedium, color = FortressColors.Danger)
                    }
                    Field(ifsc, { v -> ifsc = v.uppercase().filter(Char::isLetterOrDigit).take(11) }, "IFSC", "FTRS0000402", KeyboardType.Ascii, caps = true)
                    Field(holder, { v -> holder = v.take(60) }, "Account holder name", "", KeyboardType.Text, words = true)
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FortressColors.Danger) }
            Text(
                when (kind) {
                    PayEntry.UPI_ID -> "We check the name with the payee's bank before you pay."
                    PayEntry.PHONE -> "We find the UPI account linked to this number and show its verified name."
                    PayEntry.BANK -> "Sent instantly over UPI to this account and IFSC. The bank confirms the holder's name before you pay."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = FortressColors.InkMuted,
            )
            GoldButton(if (busy) "Verifying…" else "Verify & continue", onClick = ::submit, enabled = valid && !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboard: KeyboardType,
    prefix: String? = null,
    secret: Boolean = false,
    caps: Boolean = false,
    words: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        prefix = prefix?.let { p -> { Text(p) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            autoCorrectEnabled = false,
            capitalization = when {
                caps -> KeyboardCapitalization.Characters
                words -> KeyboardCapitalization.Words
                else -> KeyboardCapitalization.None
            },
        ),
        shape = RoundedCornerShape(FortressShape.control),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = FortressColors.Gold,
            unfocusedBorderColor = FortressColors.Line,
            focusedTextColor = FortressColors.Ink,
            unfocusedTextColor = FortressColors.Ink,
            cursorColor = FortressColors.Gold,
            focusedLabelColor = FortressColors.GoldAccent,
            unfocusedLabelColor = FortressColors.InkMuted,
            focusedContainerColor = FortressColors.SurfaceRaised,
            unfocusedContainerColor = FortressColors.SurfaceRaised,
            focusedPlaceholderColor = FortressColors.InkDim,
            unfocusedPlaceholderColor = FortressColors.InkDim,
            focusedPrefixColor = FortressColors.Ink,
            unfocusedPrefixColor = FortressColors.Ink,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
