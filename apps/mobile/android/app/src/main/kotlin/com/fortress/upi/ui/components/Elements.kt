package com.fortress.upi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fortress.upi.core.designsystem.FortressColors
import com.fortress.upi.core.designsystem.FortressHaptics
import com.fortress.upi.core.designsystem.FortressShape
import com.fortress.upi.core.designsystem.pressScale

/** Minimal vector glyphs drawn in the draw phase (no icon-font dependency). */
enum class Glyph {
    SCAN, TAP, RECEIVE, VAULT, EYE, BACK, TORCH, SHIELD,
    HOME, HISTORY, USER, AT, PHONE, BANK, QR, MOON, HELP, INFO, CHEVRON, LOCK, GAUGE, CHECK, CLOSE, COPY,
}

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = FortressColors.Ink, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.SCAN -> {
                val l = s * 0.3f
                listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(s, 0f) to Offset(-1f, 1f), Offset(0f, s) to Offset(1f, -1f), Offset(s, s) to Offset(-1f, -1f))
                    .forEach { (o, d) ->
                        drawLine(tint, o, o + Offset(l * d.x, 0f), stroke.width, StrokeCap.Round)
                        drawLine(tint, o, o + Offset(0f, l * d.y), stroke.width, StrokeCap.Round)
                    }
                drawLine(tint, Offset(s * 0.2f, s / 2), Offset(s * 0.8f, s / 2), stroke.width, StrokeCap.Round)
            }
            Glyph.TAP -> for (i in 1..3) {
                val r = s * 0.16f * i
                drawArc(tint, -50f, 100f, false, Offset(s * 0.2f - r, s / 2 - r), Size(r * 2, r * 2), style = stroke)
            }
            Glyph.RECEIVE -> {
                drawLine(tint, Offset(s / 2, s * 0.1f), Offset(s / 2, s * 0.75f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.25f, s * 0.5f), Offset(s / 2, s * 0.75f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.75f, s * 0.5f), Offset(s / 2, s * 0.75f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.15f, s * 0.92f), Offset(s * 0.85f, s * 0.92f), stroke.width, StrokeCap.Round)
            }
            Glyph.VAULT, Glyph.SHIELD -> {
                val p = Path().apply {
                    moveTo(s / 2, s * 0.06f)
                    lineTo(s * 0.88f, s * 0.22f)
                    cubicTo(s * 0.88f, s * 0.62f, s * 0.7f, s * 0.84f, s / 2, s * 0.96f)
                    cubicTo(s * 0.3f, s * 0.84f, s * 0.12f, s * 0.62f, s * 0.12f, s * 0.22f)
                    close()
                }
                drawPath(p, tint, style = stroke)
                if (glyph == Glyph.VAULT) drawCircle(tint, s * 0.09f, Offset(s / 2, s * 0.48f))
            }
            Glyph.EYE -> {
                drawOval(tint, Offset(s * 0.06f, s * 0.26f), Size(s * 0.88f, s * 0.48f), style = stroke)
                drawCircle(tint, s * 0.12f, Offset(s / 2, s / 2))
            }
            Glyph.BACK -> {
                drawLine(tint, Offset(s * 0.62f, s * 0.18f), Offset(s * 0.3f, s / 2), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.3f, s / 2), Offset(s * 0.62f, s * 0.82f), stroke.width, StrokeCap.Round)
            }
            Glyph.TORCH -> {
                drawLine(tint, Offset(s * 0.55f, s * 0.05f), Offset(s * 0.3f, s * 0.55f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.3f, s * 0.55f), Offset(s * 0.62f, s * 0.48f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.62f, s * 0.48f), Offset(s * 0.42f, s * 0.95f), stroke.width, StrokeCap.Round)
            }
            else -> drawExtraGlyph(glyph, tint, s, stroke)
        }
    }
}

private fun DrawScope.drawExtraGlyph(glyph: Glyph, tint: Color, s: Float, stroke: Stroke) {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(s * x1, s * y1), Offset(s * x2, s * y2), stroke.width, StrokeCap.Round)
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), tint, style = stroke)
    when (glyph) {
        Glyph.HOME -> {
            path { moveTo(s * 0.1f, s * 0.48f); lineTo(s * 0.5f, s * 0.12f); lineTo(s * 0.9f, s * 0.48f) }
            path { moveTo(s * 0.22f, s * 0.4f); lineTo(s * 0.22f, s * 0.88f); lineTo(s * 0.78f, s * 0.88f); lineTo(s * 0.78f, s * 0.4f) }
        }
        Glyph.HISTORY -> {
            drawArc(tint, -40f, 300f, false, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), style = stroke)
            line(0.5f, 0.3f, 0.5f, 0.52f)
            line(0.5f, 0.52f, 0.66f, 0.62f)
        }
        Glyph.USER -> {
            drawCircle(tint, s * 0.17f, Offset(s * 0.5f, s * 0.32f), style = stroke)
            drawArc(tint, 200f, 140f, false, Offset(s * 0.14f, s * 0.6f), Size(s * 0.72f, s * 0.7f), style = stroke)
        }
        Glyph.AT -> {
            drawCircle(tint, s * 0.15f, Offset(s * 0.5f, s * 0.5f), style = stroke)
            drawArc(tint, 25f, 300f, false, Offset(s * 0.08f, s * 0.08f), Size(s * 0.84f, s * 0.84f), style = stroke)
            line(0.65f, 0.36f, 0.65f, 0.58f)
        }
        Glyph.PHONE -> {
            drawRoundRect(tint, Offset(s * 0.28f, s * 0.08f), Size(s * 0.44f, s * 0.84f), CornerRadius(s * 0.1f), style = stroke)
            line(0.44f, 0.78f, 0.56f, 0.78f)
        }
        Glyph.BANK -> {
            path { moveTo(s * 0.1f, s * 0.36f); lineTo(s * 0.5f, s * 0.1f); lineTo(s * 0.9f, s * 0.36f); close() }
            listOf(0.24f, 0.41f, 0.59f, 0.76f).forEach { x -> line(x, 0.46f, x, 0.76f) }
            line(0.1f, 0.9f, 0.9f, 0.9f)
        }
        Glyph.QR -> {
            listOf(Offset(0.1f, 0.1f), Offset(0.6f, 0.1f), Offset(0.1f, 0.6f)).forEach { o ->
                drawRoundRect(tint, Offset(s * o.x, s * o.y), Size(s * 0.3f, s * 0.3f), CornerRadius(s * 0.05f), style = stroke)
            }
            drawRect(tint, Offset(s * 0.6f, s * 0.6f), Size(s * 0.12f, s * 0.12f))
            drawRect(tint, Offset(s * 0.78f, s * 0.78f), Size(s * 0.12f, s * 0.12f))
        }
        Glyph.MOON -> path {
            moveTo(s * 0.6f, s * 0.1f)
            cubicTo(s * 0.18f, s * 0.18f, s * 0.12f, s * 0.76f, s * 0.5f, s * 0.88f)
            cubicTo(s * 0.7f, s * 0.95f, s * 0.86f, s * 0.82f, s * 0.9f, s * 0.62f)
            cubicTo(s * 0.6f, s * 0.72f, s * 0.38f, s * 0.42f, s * 0.6f, s * 0.1f)
            close()
        }
        Glyph.HELP -> {
            drawCircle(tint, s * 0.4f, Offset(s / 2, s / 2), style = stroke)
            drawArc(tint, 180f, 250f, false, Offset(s * 0.37f, s * 0.26f), Size(s * 0.26f, s * 0.24f), style = stroke)
            line(0.5f, 0.52f, 0.5f, 0.58f)
            drawCircle(tint, s * 0.045f, Offset(s * 0.5f, s * 0.71f))
        }
        Glyph.INFO -> {
            drawCircle(tint, s * 0.4f, Offset(s / 2, s / 2), style = stroke)
            line(0.5f, 0.46f, 0.5f, 0.7f)
            drawCircle(tint, s * 0.045f, Offset(s * 0.5f, s * 0.32f))
        }
        Glyph.CHEVRON -> {
            line(0.38f, 0.2f, 0.66f, 0.5f)
            line(0.66f, 0.5f, 0.38f, 0.8f)
        }
        Glyph.LOCK -> {
            drawRoundRect(tint, Offset(s * 0.2f, s * 0.46f), Size(s * 0.6f, s * 0.44f), CornerRadius(s * 0.08f), style = stroke)
            drawArc(tint, 180f, 180f, false, Offset(s * 0.32f, s * 0.14f), Size(s * 0.36f, s * 0.44f), style = stroke)
            line(0.32f, 0.36f, 0.32f, 0.46f)
            line(0.68f, 0.36f, 0.68f, 0.46f)
        }
        Glyph.GAUGE -> {
            drawArc(tint, 180f, 180f, false, Offset(s * 0.1f, s * 0.24f), Size(s * 0.8f, s * 0.8f), style = stroke)
            line(0.5f, 0.64f, 0.68f, 0.4f)
        }
        Glyph.CHECK -> {
            line(0.2f, 0.52f, 0.42f, 0.74f)
            line(0.42f, 0.74f, 0.8f, 0.3f)
        }
        Glyph.CLOSE -> {
            line(0.24f, 0.24f, 0.76f, 0.76f)
            line(0.76f, 0.24f, 0.24f, 0.76f)
        }
        Glyph.COPY -> {
            drawRoundRect(tint, Offset(s * 0.34f, s * 0.34f), Size(s * 0.52f, s * 0.52f), CornerRadius(s * 0.08f), style = stroke)
            path { moveTo(s * 0.66f, s * 0.2f); lineTo(s * 0.66f, s * 0.14f); lineTo(s * 0.14f, s * 0.14f); lineTo(s * 0.14f, s * 0.66f); lineTo(s * 0.2f, s * 0.66f) }
        }
        else -> Unit
    }
}

@Composable
fun RoundIconButton(glyph: Glyph, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction, 0.9f)
            .size(44.dp)
            .clip(CircleShape)
            .background(FortressColors.SurfaceRaised)
            .border(1.dp, FortressColors.Line, CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, size = 18.dp) }
}

private val AVATAR_TONES = listOf(
    Color(0xFF13275A) to Color(0xFF2C4C9E), Color(0xFF4A1D4F) to Color(0xFF8A3E7A), Color(0xFF0D4D4A) to Color(0xFF1E8A7A),
    Color(0xFF6B4211) to Color(0xFFB07A2A), Color(0xFF2F3B52) to Color(0xFF5B6B8C), Color(0xFF16452B) to Color(0xFF2F7D4F),
    Color(0xFF5A1E2B) to Color(0xFFA23B52),
)

/** Initials on a deep jewel-tone gradient picked from the name, so each person keeps one colour. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 52.dp, ring: Brush? = null) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    val (a, b) = AVATAR_TONES[name.fold(7) { h, c -> h * 31 + c.code }.mod(AVATAR_TONES.size)]
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(a, b)))
            .then(if (ring != null) Modifier.border(1.5.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(initials, style = MaterialTheme.typography.titleMedium, color = Color.White) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = FortressColors.InkMuted, modifier = modifier)
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * In-app amount keypad. Amounts are never typed through the system IME, so
 * third-party keyboards (and their cloud "learning") never see payment data.
 */
@Composable
fun AmountKeypad(value: String, onValueChange: (String) -> Unit, haptics: FortressHaptics, modifier: Modifier = Modifier) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(2.1f)
                            .pressScale(interaction, 0.92f)
                            .clip(RoundedCornerShape(FortressShape.control))
                            .background(FortressColors.Tile)
                            .clickable(interaction, indication = null, role = Role.Button) {
                                haptics.tick()
                                onValueChange(applyKey(value, key))
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(key, style = MaterialTheme.typography.headlineSmall, color = FortressColors.Ink) }
                }
            }
        }
    }
}

/** Rupees with at most 2 decimals and 7 integer digits. */
internal fun applyKey(current: String, key: String): String = when {
    key == "⌫" -> current.dropLast(1)
    key == "." && (current.contains('.') || current.isEmpty()) -> current
    current.contains('.') && current.substringAfter('.').length >= 2 -> current
    !current.contains('.') && key != "." && current.length >= 7 -> current
    current == "0" && key != "." -> key
    else -> current + key
}

/** Parses keypad text to paise, or null. */
fun keypadToPaise(text: String): Long? {
    if (text.isEmpty() || text == ".") return null
    val rupees = text.substringBefore('.').ifEmpty { "0" }.toLongOrNull() ?: return null
    val fraction = text.substringAfter('.', "").padEnd(2, '0').take(2).toLongOrNull() ?: return null
    return (rupees * 100 + fraction).takeIf { it > 0 }
}
