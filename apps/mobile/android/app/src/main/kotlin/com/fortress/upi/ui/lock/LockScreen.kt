package com.fortress.upi.ui.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fortress.upi.R
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.rememberHaptics
import com.fortress.upi.platform.AuthGate
import com.fortress.upi.ui.components.Glyph
import com.fortress.upi.ui.components.GlyphIcon
import kotlinx.coroutines.launch

/** Cold-start and background-timeout lock. Prompts immediately; the button retries. */
@Composable
fun LockScreen(authGate: AuthGate, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val unlock: () -> Unit = {
        scope.launch {
            if (authGate.authenticate("Unlock Fortress", "Confirm it's you")) {
                haptics.confirm()
                onUnlocked()
            }
        }
    }
    LaunchedEffect(Unit) { unlock() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(painterResource(R.drawable.fortress_mark), contentDescription = null, modifier = Modifier.size(84.dp))
            Text("Fortress is locked", style = MaterialTheme.typography.titleMedium, color = FortressColors.InkMuted)
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .border(1.dp, FortressColors.Gold.copy(alpha = 0.4f), CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Unlock", onClick = unlock),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.SHIELD, tint = FortressColors.GoldAccent, size = 36.dp) }
            Text("Touch to unlock", style = MaterialTheme.typography.bodyMedium, color = FortressColors.InkMuted)
        }
    }
}
