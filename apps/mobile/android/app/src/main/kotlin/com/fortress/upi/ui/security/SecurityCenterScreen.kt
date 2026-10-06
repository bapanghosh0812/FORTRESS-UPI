package com.fortress.upi.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GhostButton
import com.fortress.upi.core.designsystem.GlassCard
import com.fortress.upi.core.security.integrity.IntegrityReport
import com.fortress.upi.core.security.integrity.Severity
import com.fortress.upi.core.security.keystore.KeyLocation
import com.fortress.upi.data.BankAccount
import com.fortress.upi.di.AppContainer
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.RoundIconButton
import com.fortress.upi.ui.components.SectionLabel
import com.fortress.upi.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class SecurityFacts(
    val identity: KeyLocation?,
    val request: KeyLocation?,
    val singleUseHardware: Boolean,
    val accounts: List<BankAccount>,
)

class SecurityViewModel(private val container: AppContainer) : ViewModel() {
    val facts: StateFlow<SecurityFacts?> = flow {
        val material = runCatching { container.identity.material() }.getOrNull()
        val accounts = runCatching { container.payments.accounts() }.getOrDefault(emptyList())
        emit(
            SecurityFacts(
                identity = material?.identityLocation,
                request = material?.requestLocation,
                singleUseHardware = container.offlineEngine.keys.capability().supported,
                accounts = accounts,
            ),
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Everything the user can verify about how their money is protected, in one place. */
@Composable
fun SecurityCenterScreen(container: AppContainer, onLockNow: () -> Unit, onBack: (() -> Unit)? = null) {
    val vm: SecurityViewModel = viewModel(factory = viewModelFactory { initializer { SecurityViewModel(container) } })
    val facts by vm.facts.collectAsStateWithLifecycle()
    val integrity by container.integrity.collectAsStateWithLifecycle()
    val online by container.online.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = FortressSpace.gutter, end = FortressSpace.gutter, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) RoundIconButton(Glyph.BACK, "Back", onBack, Modifier.padding(end = 12.dp))
                Text("Security center", style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink, modifier = Modifier.weight(1f))
                integrity?.let { StatusPill(verdictLabel(it), verdictColor(it)) }
            }
        }
        item { SectionLabel("DEVICE BINDING") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Fact("SIM binding", if (container.isBound) "Bound · verified by SMS" else if (container.demoMode) "Demo mode · not bound" else "Not bound", container.isBound)
                Fact("Identity key", keyText(facts?.identity), facts?.identity == KeyLocation.STRONGBOX)
                Fact("Request-signing key", keyText(facts?.request), facts?.request != null && facts?.request != KeyLocation.SOFTWARE)
                Fact("Offline note keys", if (facts?.singleUseHardware == true) "Single use enforced in hardware" else "Hardware single use unavailable", facts?.singleUseHardware == true)
            }
        }
        item { SectionLabel("RUNTIME INTEGRITY") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                val report = integrity
                if (report == null) {
                    Fact("Scanning", "Checking this device…", false)
                } else {
                    Fact("Hooking frameworks", noneOr(report, "hook."), report.signals.none { it.id.startsWith("hook.") })
                    Fact("Root", noneOr(report, "root."), report.signals.none { it.id.startsWith("root.") })
                    Fact("Debugger", noneOr(report, "debug."), report.signals.none { it.id.startsWith("debug.") && it.severity != Severity.INFO })
                    Fact("App signature", noneOr(report, "tamper."), report.signals.none { it.id.startsWith("tamper.") })
                }
                Fact("Network", if (online) "TLS 1.3 · certificate pinned" else "Offline · radio sessions end-to-end encrypted", true)
            }
        }
        item { SectionLabel("LINKED ACCOUNTS") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                val accounts = facts?.accounts.orEmpty()
                if (accounts.isEmpty()) {
                    Text("Accounts appear here after binding.", style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
                }
                accounts.forEach { Fact("${it.bankName} ${it.maskedNumber}", if (it.primary) "Primary" else "Savings", true) }
            }
        }
        item { GhostButton("Lock Fortress now", onLockNow, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun Fact(title: String, detail: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
        }
        Text(if (ok) "✓" else "!", style = MaterialTheme.typography.titleMedium, color = if (ok) FortressColors.Emerald else FortressColors.GoldAccent)
    }
}

private fun keyText(location: KeyLocation?): String = when (location) {
    KeyLocation.STRONGBOX -> "StrongBox secure element · attested"
    KeyLocation.TEE -> "Trusted execution environment · attested"
    KeyLocation.SOFTWARE -> "Software keystore (not accepted for payments)"
    null -> "Created during binding"
}

private fun noneOr(report: IntegrityReport, prefix: String): String =
    report.signals.filter { it.id.startsWith(prefix) }.joinToString { it.id.substringAfter('.').replace('_', ' ') }.ifEmpty { "None detected" }

private fun verdictLabel(report: IntegrityReport): String = when (report.maxSeverity) {
    Severity.INFO -> "PROTECTED"
    Severity.ELEVATED -> "REVIEW"
    Severity.CRITICAL -> "BLOCKED"
}

@Composable
private fun verdictColor(report: IntegrityReport): Color = when (report.maxSeverity) {
    Severity.INFO -> FortressColors.Emerald
    Severity.ELEVATED -> FortressColors.GoldAccent
    Severity.CRITICAL -> FortressColors.Danger
}
