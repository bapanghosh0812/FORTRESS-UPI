package com.fortress.upi.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressShape
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.core.offline.store.OutboxDirection
import com.fortress.upi.core.offline.store.SyncState
import com.fortress.upi.di.AppContainer
import com.fortress.upi.ui.components.Avatar
import com.fortress.upi.ui.components.SectionLabel
import com.fortress.upi.ui.components.StatusPill
import com.fortress.upi.ui.dashboard.DemoData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One row in Activity, merged from the payments service and the offline outbox. */
data class ActivityItem(
    val id: String,
    val counterparty: String,
    val subtitle: String,
    val amountPaise: Long,
    val credit: Boolean,
    val atMillis: Long,
    val offline: Boolean,
    val pendingSync: Boolean,
)

enum class ActivityFilter(val label: String) { ALL("All"), SENT("Sent"), RECEIVED("Received"), OFFLINE("Offline") }

class ActivityViewModel(private val container: AppContainer) : ViewModel() {
    private val online = MutableStateFlow<List<ActivityItem>>(emptyList())

    private val offline = flow { emitAll(container.offlineEngine.database().outbox().observeRecent(100)) }
        .catch { emit(emptyList()) }

    val items: StateFlow<List<ActivityItem>> = combine(online, offline) { remote, outbox ->
        val local = outbox.filter { it.direction != OutboxDirection.REDEEM }.map {
            ActivityItem(
                id = "o-${it.id}",
                counterparty = it.counterpartyName,
                subtitle = if (it.direction == OutboxDirection.RECEIVED) "Offline · Received" else "Offline · Sent",
                amountPaise = it.amountPaise,
                credit = it.direction == OutboxDirection.RECEIVED,
                atMillis = it.createdAtMillis,
                offline = true,
                pendingSync = it.syncState == SyncState.PENDING,
            )
        }
        (remote + local).sortedByDescending { it.atMillis }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            online.value = try {
                container.payments.transactions().map {
                    ActivityItem(it.id, it.counterparty, it.note.ifEmpty { "UPI" }, it.amountPaise, it.credit, it.atMillis, false, false)
                }
            } catch (e: java.io.IOException) {
                if (container.demoMode) demoItems() else emptyList()
            }
        }
    }

    private fun demoItems(): List<ActivityItem> {
        val now = System.currentTimeMillis()
        return DemoData.recent.mapIndexed { i, t ->
            ActivityItem(t.id, t.counterparty, t.subtitle, t.amountPaise, t.credit, now - i * 7_200_000L, false, false)
        }
    }
}

@Composable
fun ActivityScreen(container: AppContainer) {
    val vm: ActivityViewModel = viewModel(factory = viewModelFactory { initializer { ActivityViewModel(container) } })
    val items by vm.items.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    val shown = items.filter {
        when (filter) {
            ActivityFilter.ALL -> true
            ActivityFilter.SENT -> !it.credit
            ActivityFilter.RECEIVED -> it.credit
            ActivityFilter.OFFLINE -> it.offline
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = FortressSpace.gutter, end = FortressSpace.gutter, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") { Text("Activity", style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink) }
        item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityFilter.entries.forEach { f ->
                    val active = f == filter
                    Text(
                        f.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) FortressColors.OnGold else FortressColors.InkMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) FortressColors.Ink else FortressColors.Surface)
                            .border(1.dp, if (active) FortressColors.Ink else FortressColors.Line, RoundedCornerShape(50))
                            .clickable(role = Role.RadioButton) { filter = f }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") { SectionLabel("NO ${filter.label.uppercase()} PAYMENTS YET", Modifier.padding(top = 24.dp)) }
        }
        items(shown, key = { it.id }) { ActivityRow(it, Modifier.animateItem()) }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FortressShape.control))
            .background(FortressColors.Surface)
            .border(1.dp, FortressColors.Line, RoundedCornerShape(FortressShape.control))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.counterparty, size = 42.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.counterparty, style = MaterialTheme.typography.titleMedium, color = FortressColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.subtitle} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.atMillis))}",
                style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (item.credit) "+ " else "− ") + Inr.format(item.amountPaise),
                style = MaterialTheme.typography.titleMedium,
                color = if (item.credit) FortressColors.Emerald else FortressColors.Ink,
            )
            if (item.pendingSync) StatusPill("SYNC", FortressColors.Sapphire, Modifier.padding(top = 4.dp))
        }
    }
}
