package com.fortress.upi.ui.pay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressSpace
import com.fortress.upi.core.designsystem.GlassCard
import com.fortress.upi.core.designsystem.GoldButton
import com.fortress.upi.core.designsystem.Inr
import com.fortress.upi.core.designsystem.SuccessBurst
import com.fortress.upi.core.designsystem.rememberHaptics
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.GlyphIcon

/** What the success screen shows; rows are label → value. */
data class Receipt(val amountPaise: Long, val counterparty: String, val incoming: Boolean, val rows: List<Pair<String, String>>)

@Composable
fun SuccessScreen(receipt: Receipt, onDone: () -> Unit) {
    val haptics = rememberHaptics()
    var burst by remember { mutableIntStateOf(0) }
    val scale = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) {
        haptics.success()
        burst++
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f))
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(FortressSpace.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.size(32.dp))
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .clip(CircleShape)
                    .background(FortressColors.Emerald),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.SHIELD, tint = FortressColors.OnGold, size = 44.dp) }
            Text(Inr.format(receipt.amountPaise), style = MaterialTheme.typography.displayLarge, color = FortressColors.Ink)
            Text(
                (if (receipt.incoming) "Received from " else "Paid to ") + receipt.counterparty,
                style = MaterialTheme.typography.bodyLarge, color = FortressColors.InkMuted, textAlign = TextAlign.Center,
            )
            GlassCard(Modifier.fillMaxWidth()) {
                receipt.rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(k, style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted, modifier = Modifier.weight(1f))
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = FortressColors.Ink, textAlign = TextAlign.End)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            GoldButton("Done", onDone, Modifier.fillMaxWidth())
        }
        SuccessBurst(trigger = burst, modifier = Modifier.fillMaxSize())
    }
}
