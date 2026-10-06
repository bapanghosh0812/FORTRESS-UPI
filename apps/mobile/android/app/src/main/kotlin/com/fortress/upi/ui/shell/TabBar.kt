package com.fortress.upi.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fortress.upi.core.designsystem.FortressBrushes
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.pressScale
import com.fortress.upi.core.designsystem.rememberHaptics
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.GlyphIcon

enum class Tab(val label: String, val glyph: Glyph) {
    HOME("Home", Glyph.HOME),
    ACTIVITY("History", Glyph.HISTORY),
    VAULT("Offline", Glyph.VAULT),
    PROFILE("Profile", Glyph.USER),
}

/** Four tabs around a raised gold Scan button. */
@Composable
fun FortressTabBar(selected: Tab, onSelect: (Tab) -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Box(
        modifier
            .fillMaxWidth()
            .background(FortressColors.Surface.copy(alpha = 0.96f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            TabItem(Tab.HOME, selected, onSelect)
            TabItem(Tab.ACTIVITY, selected, onSelect)
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .offset(y = (-14).dp)
                    .pressScale(interaction, 0.9f)
                    .size(60.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(FortressBrushes.gold)
                    .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Scan and pay") {
                        haptics.tick()
                        onScan()
                    },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.SCAN, tint = FortressColors.OnGold, size = 26.dp) }
            TabItem(Tab.VAULT, selected, onSelect)
            TabItem(Tab.PROFILE, selected, onSelect)
        }
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Tab, onSelect: (Tab) -> Unit) {
    val active = tab == selected
    Column(
        Modifier
            .semantics { this.selected = active }
            .clip(CircleShape)
            .clickable(role = Role.Tab) { onSelect(tab) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(tab.glyph, tint = if (active) FortressColors.GoldAccent else FortressColors.InkDim, size = 22.dp)
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = if (active) FortressColors.Ink else FortressColors.InkDim)
    }
}
