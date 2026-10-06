package com.fortress.upi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fortress.upi.BuildConfig
import com.fortress.upi.R
import com.fortress.upi.core.designsystem.FortressBrushes
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressShape
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GhostButton
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.core.designsystem.ThemeMode
import com.fortress.upi.data.PaymentLimits
import com.fortress.upi.di.AppContainer
import com.fortress.upi.platform.AutoLock
import com.fortress.upi.ui.components.Avatar
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.GlyphIcon
import com.fortress.upi.ui.components.RoundIconButton
import com.fortress.upi.ui.components.SectionLabel
import com.fortress.upi.ui.dashboard.DemoProfile
import com.fortress.upi.ui.security.SecurityCenterScreen
import java.text.DateFormat
import java.util.Date

private enum class Page { MAIN, SECURITY, LIMITS, HELP, ABOUT }

/** Profile & settings: every preference is one tap from here, and each sub-page is one level deep. */
@Composable
fun SettingsScreen(container: AppContainer, onMyQr: () -> Unit, onOpenVault: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
    val back = { page = Page.MAIN }
    AnimatedContent(page, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "settings") { p ->
        when (p) {
            Page.MAIN -> Main(container, onMyQr, onOpenVault) { page = it }
            Page.SECURITY -> SecurityCenterScreen(container, onLockNow = container.appLock::lockNow, onBack = back)
            Page.LIMITS -> LimitsPage(container, back)
            Page.HELP -> HelpPage(back)
            Page.ABOUT -> AboutPage(back)
        }
    }
}

@Composable
private fun Main(container: AppContainer, onMyQr: () -> Unit, onOpenVault: () -> Unit, open: (Page) -> Unit) {
    val prefs = container.prefs
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val hide by prefs.hideBalances.collectAsStateWithLifecycle()
    val warn by prefs.payeeWarnings.collectAsStateWithLifecycle()
    val autoLock by prefs.autoLock.collectAsStateWithLifecycle()
    val limits by container.limits.collectAsStateWithLifecycle()
    val serverProfile by container.profile.collectAsStateWithLifecycle()
    val profile = serverProfile ?: DemoProfile.takeIf { container.demoMode }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    PageList {
        item { Text("Profile & settings", style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink) }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FortressShape.card))
                    .background(FortressBrushes.hero)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(profile?.name ?: "Fortress", size = 58.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(profile?.name ?: "Not linked yet", style = MaterialTheme.typography.titleMedium, color = FortressColors.OnHero, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    profile?.let {
                        Text(it.mobile, style = MaterialTheme.typography.bodyMedium, color = FortressColors.OnHero.copy(alpha = 0.75f))
                        Text(it.vpa, style = MaterialTheme.typography.bodyMedium, color = FortressColors.OnHero.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).clickable(role = Role.Button, onClickLabel = "My QR", onClick = onMyQr),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.QR, tint = FortressColors.OnHero, size = 20.dp) }
            }
        }

        item { SectionLabel("APPEARANCE") }
        item { ThemePicker(theme, prefs::setTheme) }

        item { SectionLabel("PAYMENTS") }
        item {
            Group {
                NavRow(Glyph.GAUGE, "Payment limits", "${Inr.formatWhole(limits.dailyUpiPaise)} a day · offline ${Inr.formatWhole(limits.offlinePerTxnPaise)} per payment") { open(Page.LIMITS) }
                Divider()
                NavRow(Glyph.VAULT, "Offline vault", "Pay without internet, sealed in hardware", onOpenVault)
            }
        }

        item { SectionLabel("SECURITY & PRIVACY") }
        item {
            Group {
                NavRow(Glyph.SHIELD, "Security center", "Device binding, hardware keys, integrity checks") { open(Page.SECURITY) }
                Divider()
                ToggleRow(Glyph.EYE, "Hide balances", "Show ₹ •••• until you tap to reveal", hide, prefs::setHideBalances)
                Divider()
                ToggleRow(Glyph.INFO, "New payee warnings", "Extra check on the first payment to someone", warn, prefs::setPayeeWarnings)
            }
        }
        item {
            Group {
                Text("Auto-lock", style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink, modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp))
                Text(
                    "Lock Fortress when it has been in the background",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FortressColors.InkMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Segmented(AutoLock.entries.map { it.label.removePrefix("After ") }, AutoLock.entries.indexOf(autoLock), Modifier.padding(14.dp)) {
                    prefs.setAutoLock(AutoLock.entries[it])
                }
            }
        }

        item { SectionLabel("SUPPORT") }
        item {
            Group {
                NavRow(Glyph.HELP, "Help & complaints", "Payment issues, UPI PIN, grievance officer") { open(Page.HELP) }
                Divider()
                NavRow(Glyph.INFO, "About Fortress", "Version ${BuildConfig.VERSION_NAME}") { open(Page.ABOUT) }
            }
        }
        item { GhostButton("Lock Fortress now", container.appLock::lockNow, Modifier.fillMaxWidth()) }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clip(RoundedCornerShape(FortressShape.control))
                    .background(FortressColors.Danger.copy(alpha = 0.1f))
                    .clickable(role = Role.Button) { confirmSignOut = true },
                contentAlignment = Alignment.Center,
            ) { Text("Sign out from this device", style = MaterialTheme.typography.labelLarge, color = FortressColors.Danger) }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out from this device?") },
            text = { Text("Your keys and binding on this phone are deleted. To use UPI here again, you'll verify your SIM and set up Fortress from the start.") },
            confirmButton = {
                TextButton({
                    confirmSignOut = false
                    container.signOut()
                }) { Text("Sign out", color = FortressColors.Danger) }
            },
            dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel", color = FortressColors.Ink) } },
            containerColor = FortressColors.Surface,
            titleContentColor = FortressColors.Ink,
            textContentColor = FortressColors.InkMuted,
        )
    }
}

@Composable
private fun LimitsPage(container: AppContainer, onBack: () -> Unit) {
    val l: PaymentLimits by container.limits.collectAsStateWithLifecycle()
    PageList {
        item { SubHeader("Payment limits", onBack) }
        item {
            Group {
                Kv("Per UPI payment", Inr.formatWhole(l.perTxnUpiPaise))
                Kv("UPI per day", Inr.formatWhole(l.dailyUpiPaise))
                Kv("Payments per day", l.dailyCount.toString())
                Kv("Offline, per payment", Inr.formatWhole(l.offlinePerTxnPaise))
                Kv("Offline vault maximum", Inr.formatWhole(l.offlineWalletPaise))
                Kv("First 24 hours on a new phone", Inr.formatWhole(l.newDeviceCapPaise))
            }
        }
        item {
            Note(
                if (l.fetchedAtMillis == 0L) {
                    "These are built-in defaults. Fortress replaces them with the live limits from its server as soon as you're online."
                } else {
                    "${l.source} · updated ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(l.fetchedAtMillis))}"
                },
            )
        }
        item {
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Why offline payments have a cap", style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
                    Text(
                        "Without internet, no bank can check your balance in real time, so the RBI limits offline payments to keep the risk small if a phone is lost. " +
                            "With internet you can pay up to your full UPI limit. When the RBI or NPCI changes a cap, Fortress updates it from the server; no app update is needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FortressColors.InkMuted,
                    )
                }
            }
        }
    }
}

private val FAQ = listOf(
    "Money was debited but the payee didn't get it" to
        "Failed payments are usually reversed automatically within 1 working day. If not, open the payment in History and raise a complaint. It goes to your bank through NPCI's dispute system.",
    "How do I change or reset my UPI PIN?" to
        "Your UPI PIN is set on NPCI's secure screen, never inside Fortress. Use Change PIN for your linked account, or Forgot PIN and verify with your debit card.",
    "Why is offline limited?" to
        "Without internet no bank can confirm your balance, so the RBI caps offline payments. Fortress applies the current caps automatically.",
    "Someone asked for my PIN so I can receive money" to
        "That's fraud. You never need your UPI PIN to receive money. Decline the request and report it.",
    "I lost my phone" to
        "Call your bank to block UPI. Your Fortress account only works on your bound phone and SIM, and offline notes can't be copied out of the hardware chip.",
)

@Composable
private fun HelpPage(onBack: () -> Unit) {
    PageList {
        item { SubHeader("Help & complaints", onBack) }
        item { SectionLabel("COMMON QUESTIONS") }
        item {
            Group {
                FAQ.forEachIndexed { i, (q, a) ->
                    if (i > 0) Divider()
                    Faq(q, a)
                }
            }
        }
        item { SectionLabel("GRIEVANCE REDRESSAL") }
        item {
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Fortress support, in the app. We reply within 24 hours.", style = MaterialTheme.typography.bodyMedium, color = FortressColors.Ink)
                    Text("2. Grievance Officer. Name and contact are published on our website and Play Store listing.", style = MaterialTheme.typography.bodyMedium, color = FortressColors.Ink)
                    Text("3. If it isn't resolved in 30 days: RBI Integrated Ombudsman at cms.rbi.org.in.", style = MaterialTheme.typography.bodyMedium, color = FortressColors.Ink)
                }
            }
        }
    }
}

@Composable
private fun Faq(question: String, answer: String) {
    var open by rememberSaveable(question) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(question, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink, modifier = Modifier.weight(1f))
            GlyphIcon(if (open) Glyph.CLOSE else Glyph.CHEVRON, tint = FortressColors.InkDim, size = 16.dp)
        }
        if (open) Text(answer, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    PageList {
        item { SubHeader("About Fortress", onBack) }
        item {
            Group {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(painterResource(R.drawable.fortress_mark), contentDescription = "Fortress", modifier = Modifier.size(88.dp))
                    Text("Fortress UPI", style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink)
                    Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
                }
            }
        }
        item {
            Note(
                "Fortress is a UPI app (Third-Party Application Provider). Payments are processed by our sponsor PSP bank over NPCI's UPI network. " +
                    "The partner bank's name, our grievance officer and our privacy policy are listed on the Play Store page and on our website.",
            )
        }
        item { Text("Made in India", style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
}

/* ── Building blocks ─────────────────────────────────────────────── */

@Composable
private fun PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = FortressSpace.gutter, end = FortressSpace.gutter, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Glyph.BACK, "Back", onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(FortressShape.card)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(FortressColors.Surface).border(1.dp, FortressColors.Line, shape),
        content = content,
    )
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(FortressColors.Line))

@Composable
private fun Lead(glyph: Glyph) = Box(
    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(FortressColors.Tile),
    contentAlignment = Alignment.Center,
) { GlyphIcon(glyph, tint = FortressColors.GoldAccent, size = 19.dp) }

@Composable
private fun NavRow(glyph: Glyph, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lead(glyph)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
        }
        GlyphIcon(Glyph.CHEVRON, tint = FortressColors.InkDim, size = 16.dp)
    }
}

@Composable
private fun ToggleRow(glyph: Glyph, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lead(glyph)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = FortressColors.Emerald,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = FortressColors.Line,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = FortressColors.Line,
            ),
        )
    }
}

@Composable
private fun Kv(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = FortressColors.InkMuted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.control))
            .background(FortressColors.SurfaceRaised)
            .padding(14.dp),
    )
}

@Composable
private fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FortressColors.Tile).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) FortressColors.Surface else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) FortressColors.Ink else FortressColors.InkMuted, maxLines = 1)
            }
        }
    }
}

private val PEARL_SWATCH = Brush.verticalGradient(0f to Color(0xFF0E1E4A), 0.4f to Color(0xFF0E1E4A), 0.4f to Color(0xFFF3F4F8), 1f to Color(0xFFF3F4F8))
private val MIDNIGHT_SWATCH = Brush.verticalGradient(0f to Color(0xFF132A62), 0.4f to Color(0xFF132A62), 0.4f to Color(0xFF070E22), 1f to Color(0xFF070E22))
private val SYSTEM_SWATCH = Brush.horizontalGradient(0f to Color(0xFFF3F4F8), 0.5f to Color(0xFFF3F4F8), 0.5f to Color(0xFF070E22), 1f to Color(0xFF070E22))

@Composable
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple(ThemeMode.PEARL, "Pearl", PEARL_SWATCH), Triple(ThemeMode.MIDNIGHT, "Midnight", MIDNIGHT_SWATCH), Triple(ThemeMode.SYSTEM, "System", SYSTEM_SWATCH))
            .forEach { (mode, label, swatch) ->
                val on = mode == current
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(FortressColors.Surface)
                        .border(2.dp, if (on) FortressColors.Gold else FortressColors.Line, shape)
                        .clickable(role = Role.RadioButton, onClickLabel = "Use $label theme") { onPick(mode) }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(12.dp)).background(swatch))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = FortressColors.Ink)
                }
            }
    }
}
