package com.s3vault.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s3vault.app.settings.ThemeMode

/*
 * Design direction: a quiet "darkroom" — warm neutral surfaces (near-black in dark mode so photographs carry the
 * colour), one deep teal accent reserved for actions and live state, and semantic status colours that are always
 * paired with an icon and a word, never used alone. Typography is the system font with a tight, restrained scale.
 * The brand palette is fixed on purpose (no wallpaper-derived dynamic colour) so the product looks the same everywhere.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5F66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE9EA),
    onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFF4B5F60),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8E6),
    onSecondaryContainer = Color(0xFF1B2B2C),
    tertiary = Color(0xFF1E6B3A),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F6F3),
    onBackground = Color(0xFF1A1B1A),
    surface = Color(0xFFF7F6F3),
    onSurface = Color(0xFF1A1B1A),
    surfaceVariant = Color(0xFFE9E6DF),
    onSurfaceVariant = Color(0xFF55574F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1EFEA),
    surfaceContainer = Color(0xFFECE9E3),
    surfaceContainerHigh = Color(0xFFE5E2DB),
    surfaceContainerHighest = Color(0xFFDEDBD3),
    outline = Color(0xFF7C7E77),
    outlineVariant = Color(0xFFD9D6CE),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD0D6),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF0F4A50),
    onPrimaryContainer = Color(0xFFBFEFF2),
    secondary = Color(0xFFB3C8C9),
    onSecondary = Color(0xFF1D3234),
    secondaryContainer = Color(0xFF28393A),
    onSecondaryContainer = Color(0xFFD0E5E6),
    tertiary = Color(0xFF7BD79A),
    onTertiary = Color(0xFF003919),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1511),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0F0E),
    onBackground = Color(0xFFE6E6E2),
    surface = Color(0xFF0E0F0E),
    onSurface = Color(0xFFE6E6E2),
    surfaceVariant = Color(0xFF2A2D2B),
    onSurfaceVariant = Color(0xFFA9ABA5),
    surfaceContainerLowest = Color(0xFF080908),
    surfaceContainerLow = Color(0xFF151716),
    surfaceContainer = Color(0xFF1B1D1C),
    surfaceContainerHigh = Color(0xFF242726),
    surfaceContainerHighest = Color(0xFF2E3130),
    outline = Color(0xFF8A8C86),
    outlineVariant = Color(0xFF2F312F),
    scrim = Color(0xFF000000),
)

/** Semantic status colours that Material 3 does not provide (success, warning). Error/info come from the scheme. */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightStatus = StatusColors(
    success = Color(0xFF1E6B3A), onSuccess = Color.White,
    successContainer = Color(0xFFD3EEDB), onSuccessContainer = Color(0xFF0B3A1D),
    warning = Color(0xFF8A5100), onWarning = Color.White,
    warningContainer = Color(0xFFFBE7C6), onWarningContainer = Color(0xFF4A2B00),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF7BD79A), onSuccess = Color(0xFF003919),
    successContainer = Color(0xFF173B25), onSuccessContainer = Color(0xFFC6EDD2),
    warning = Color(0xFFF0B866), onWarning = Color(0xFF462A00),
    warningContainer = Color(0xFF3E2D0C), onWarningContainer = Color(0xFFFBE0B3),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

val MaterialTheme.status: StatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current

/**
 * Tokens for marks drawn directly on top of photographs. A photo can be any colour, so these always sit on a dark
 * scrim and use light foregrounds regardless of the app theme.
 */
object PhotoOverlay {
    val scrim = Color(0x8C000000)
    val strongScrim = Color(0xB3000000)
    val chip = Color(0x2EFFFFFF)
    val onScrim = Color(0xFFFFFFFF)
    val successOnScrim = Color(0xFF7BD79A)
    val errorOnScrim = Color(0xFFFFB4AB)
}

/** Spacing scale (dp). Everything in the UI snaps to this scale. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Horizontal margin of full-width content. */
    val screen = 16.dp

    /** Widest a column of text/controls grows on large screens, to keep line lengths readable. */
    val maxContent = 640.dp

    /** Minimum comfortable touch target. */
    val touch = 48.dp
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val Base = TextStyle(fontFamily = FontFamily.Default)

private val AppTypography = Typography(
    displaySmall = Base.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineMedium = Base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = Base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = Base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = Base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall = Base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    bodyLarge = Base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp),
    bodyMedium = Base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp),
    bodySmall = Base.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
    labelLarge = Base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = Base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = Base.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

/** Technical details (keys, hints, hashes): monospace so they scan as identifiers rather than prose. */
val MaterialTheme.technical: TextStyle
    @Composable @ReadOnlyComposable get() = typography.bodySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun S3VaultTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val scheme: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
