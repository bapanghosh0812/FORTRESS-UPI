package com.fortress.upi.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Typeface slots. Production ships Sora (display — geometric, echoes the logo
 * wordmark) and Manrope (text), both OFL, bundled under res/font and wired here.
 * `scripts/fetch-fonts.sh` downloads the exact files; until then the system
 * sans keeps the build green.
 */
object FortressFonts {
    val display: FontFamily = FontFamily.SansSerif
    val text: FontFamily = FontFamily.SansSerif
}

private const val TABULAR = "tnum, lnum"

val FortressTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FortressFonts.display, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR,
    ),
    displayMedium = TextStyle(
        fontFamily = FortressFonts.display, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.015).em, fontFeatureSettings = TABULAR,
    ),
    headlineSmall = TextStyle(fontFamily = FortressFonts.display, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = FortressFonts.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FortressFonts.text, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FortressFonts.text, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FortressFonts.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = FortressFonts.text, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(
        fontFamily = FortressFonts.text, fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp, letterSpacing = 0.12.em, fontFeatureSettings = TABULAR,
    ),
)

/** Resolves the effective palette for a [ThemeMode]. */
@Composable
fun paletteFor(mode: ThemeMode): FortressPalette = when (mode) {
    ThemeMode.PEARL -> PearlPalette
    ThemeMode.MIDNIGHT -> MidnightPalette
    ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) MidnightPalette else PearlPalette
}

@Composable
fun FortressTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val p = paletteFor(mode)
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.gold, onPrimary = FortressColors.OnGold, secondary = p.ink, onSecondary = p.background,
            tertiary = p.emerald, background = p.background, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.surfaceRaised, onSurfaceVariant = p.inkMuted, outline = p.line, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.gold, onPrimary = FortressColors.OnGold, secondary = p.ink, onSecondary = p.background,
            tertiary = p.emerald, background = p.background, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.surfaceRaised, onSurfaceVariant = p.inkMuted, outline = p.line, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalFortressPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = FortressTypography, content = content)
    }
}
