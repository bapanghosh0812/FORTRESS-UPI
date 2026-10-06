package com.fortress.upi.core.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.util.Locale

/** Raised surface with a hairline edge; follows the active palette. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(FortressShape.card)
    Column(
        modifier
            .clip(shape)
            .background(FortressBrushes.surface)
            .border(1.dp, FortressBrushes.edge, shape)
            .padding(FortressSpace.l),
        content = content,
    )
}

/** Spring scale-down while pressed, read in the graphics layer (no recomposition per frame). */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, FortressMotion.press, label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun GoldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(FortressShape.control)
    Box(
        modifier
            .pressScale(interaction)
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (enabled) FortressBrushes.gold else Brush.linearGradient(listOf(FortressColors.Line, FortressColors.Line)))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FortressSpace.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) FortressColors.OnGold else FortressColors.InkDim)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(FortressShape.control)
    Box(
        modifier
            .pressScale(interaction)
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(1.dp, FortressColors.Line, shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = FortressSpace.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = FortressColors.Ink)
    }
}

/** Indian-grouped rupee formatting: ₹1,23,456.78. */
object Inr {
    private val format: ThreadLocal<NumberFormat> = ThreadLocal.withInitial {
        NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    }

    fun format(paise: Long): String = format.get()!!.format(paise / 100.0)

    fun formatWhole(paise: Long): String = format(paise).removeSuffix(".00")
}

@Composable
fun AmountText(paise: Long, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.displayLarge, masked: Boolean = false, color: Color = FortressColors.Ink) {
    val text = if (masked) "₹ ••••••" else Inr.format(paise)
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.semantics { contentDescription = if (masked) "Balance hidden" else text },
    )
}

/**
 * Living border: a platinum/gold sheen that orbits a card's hairline edge.
 * The angle is read only in the draw phase — zero recomposition while animating.
 */
@Composable
fun Modifier.liquidEdge(
    active: Boolean = true,
    colors: List<Color> = listOf(FortressColors.GoldAccent, FortressColors.Ink, FortressColors.GoldDeep),
): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "edge")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing)),
        label = "angle",
    )
    val stops = remember(colors) { listOf(Color.Transparent) + colors + Color.Transparent }
    return drawWithCache {
        val stroke = Stroke(width = 1.2.dp.toPx())
        val radius = FortressShape.card.toPx()
        val reach = size.maxDimension / 2f
        onDrawWithContent {
            drawContent()
            val sweep = Offset(cos(angle) * reach, sin(angle) * reach)
            drawRoundRectStroke(Brush.linearGradient(stops, start = center + sweep, end = center - sweep), radius, stroke)
        }
    }
}

/** Breathing ambient aura for offline mode (emerald → sapphire). */
@Composable
fun AmbientAura(active: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "aura")
    val breath by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(FortressMotion.AMBIENT_BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val presence by animateFloatAsState(if (active) 1f else 0f, tween(900), label = "presence")
    Box(
        modifier.drawWithCache {
            val aura = Brush.radialGradient(
                colors = listOf(Color(0x5500E59B), Color(0x222F6BFF), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.28f),
                radius = size.maxDimension * 0.75f,
            )
            onDrawBehind { drawRect(aura, alpha = presence * breath) }
        },
        content = content,
    )
}
