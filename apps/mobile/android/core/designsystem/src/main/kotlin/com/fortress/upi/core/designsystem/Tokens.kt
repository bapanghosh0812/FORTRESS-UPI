package com.fortress.upi.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Appearance chosen in Settings. SYSTEM follows the phone's dark-theme switch. */
enum class ThemeMode { PEARL, MIDNIGHT, SYSTEM }

/**
 * One theme's semantic colours. Pearl is the bright daytime theme; Midnight is a
 * deep navy night theme (never flat black). Both share the logo's navy and gold.
 */
@Immutable
data class FortressPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkDim: Color,
    val gold: Color,
    val goldAccent: Color,
    val tile: Color,
    val emerald: Color,
    val sapphire: Color,
    val danger: Color,
    val heroTop: Color,
    val heroMid: Color,
    val heroBottom: Color,
)

val PearlPalette = FortressPalette(
    isDark = false,
    background = Color(0xFFF3F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF7F8FB),
    line = Color(0xFFE4E7EF),
    ink = Color(0xFF0C1633),
    inkMuted = Color(0xFF4B5570),
    inkDim = Color(0xFF5F6882),
    gold = Color(0xFFA97A26),
    goldAccent = Color(0xFF8A6418),
    tile = Color(0xFFEEF1F8),
    emerald = Color(0xFF0B7F58),
    sapphire = Color(0xFF2F5BD3),
    danger = Color(0xFFC42F43),
    heroTop = Color(0xFF0A1636),
    heroMid = Color(0xFF122656),
    heroBottom = Color(0xFF1C3A7D),
)

val MidnightPalette = FortressPalette(
    isDark = true,
    background = Color(0xFF070E22),
    surface = Color(0xFF0F1A38),
    surfaceRaised = Color(0xFF132148),
    line = Color(0xFF22315C),
    ink = Color(0xFFEEF1F8),
    inkMuted = Color(0xFFAEB7D0),
    inkDim = Color(0xFF8790AC),
    gold = Color(0xFFE2B656),
    goldAccent = Color(0xFFF2D488),
    tile = Color(0xFF172752),
    emerald = Color(0xFF35D49A),
    sapphire = Color(0xFF8EA9FF),
    danger = Color(0xFFFF7182),
    heroTop = Color(0xFF0B1840),
    heroMid = Color(0xFF132A62),
    heroBottom = Color(0xFF1F3F8C),
)

val LocalFortressPalette = staticCompositionLocalOf { PearlPalette }

/**
 * Theme-aware colours, read from the active [FortressPalette]. Inside draw
 * lambdas (not composable), read them into a local first.
 */
object FortressColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.surface
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.surfaceRaised
    val Line: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.line
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.ink
    val InkMuted: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.inkMuted
    val InkDim: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.inkDim
    val Gold: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.gold
    val GoldAccent: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.goldAccent
    val Tile: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.tile
    val Emerald: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.emerald
    val Sapphire: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.sapphire
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.danger

    /** Brand constants, identical in both themes. */
    val GoldLight = Color(0xFFF4D78E)
    val GoldDeep = Color(0xFFA97A26)
    val OnGold = Color(0xFF1A1305)
    val OnHero = Color(0xFFFFFFFF)
    val HeroGold = Color(0xFFF0CF7E)
    val EmeraldDeep = Color(0xFF0B7F58)
    val SapphireDeep = Color(0xFF0A2A66)
    val UpiSaffron = Color(0xFFFF7A1A)
    val UpiGreen = Color(0xFF13A538)
}

object FortressBrushes {
    /** Brand gold, the same in both themes; text on it uses [FortressColors.OnGold]. */
    val gold: Brush = Brush.linearGradient(listOf(Color(0xFFF4D78E), Color(0xFFD7A54A), Color(0xFFA97A26)))
    val offlineAura: Brush = Brush.radialGradient(listOf(Color(0x4035D49A), Color(0x1A2F6BFF), Color.Transparent))

    val edge: Brush
        @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.let {
            Brush.verticalGradient(listOf(it.line, it.line.copy(alpha = 0.4f)))
        }
    val surface: Brush
        @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.let { Brush.verticalGradient(listOf(it.surface, it.surfaceRaised)) }

    /** The navy hero used behind the brand on Home, the lock screen and onboarding. */
    val hero: Brush
        @Composable @ReadOnlyComposable get() = LocalFortressPalette.current.let { Brush.linearGradient(listOf(it.heroTop, it.heroMid, it.heroBottom)) }
}

object FortressShape {
    val card = 24.dp
    val control = 16.dp
    val chip = 12.dp
}

object FortressSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    /** Side gutter on phones. */
    val gutter = 18.dp
}

/**
 * Motion system. Everything interactive is spring-driven (interruptible, no
 * fixed durations to fight); only ambient loops use timed easing.
 */
object FortressMotion {
    /** Press feedback on buttons/cards. */
    val press = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    /** Content settling into place (cards, sheets). */
    val settle = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 380f)

    /** Scanner brackets snapping onto a detected QR. */
    val magnet = spring<Float>(dampingRatio = 0.82f, stiffness = 1_400f)

    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val AMBIENT_BREATH_MS = 3_600
    const val SCREEN_TRANSITION_MS = 420
}
