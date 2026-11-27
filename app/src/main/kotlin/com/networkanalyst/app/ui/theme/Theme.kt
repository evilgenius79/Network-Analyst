package com.networkanalyst.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.networkanalyst.core.analysis.Rating
import com.networkanalyst.core.analysis.Severity

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CD6C0),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0F4F45),
    onPrimaryContainer = Color(0xFFA6F2E3),
    secondary = Color(0xFF8FC8F2),
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF16405A),
    onSecondaryContainer = Color(0xFFCBE6FA),
    background = Color(0xFF0E1418),
    onBackground = Color(0xFFE2E8EC),
    surface = Color(0xFF0E1418),
    onSurface = Color(0xFFE2E8EC),
    surfaceVariant = Color(0xFF1B252C),
    onSurfaceVariant = Color(0xFFA9B6BF),
    surfaceContainer = Color(0xFF161F25),
    surfaceContainerHigh = Color(0xFF1D2830),
    surfaceContainerHighest = Color(0xFF25323B),
    outline = Color(0xFF5E6D77),
    outlineVariant = Color(0xFF2C3942),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0A06),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4EEE3),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF1F5F86),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8F8),
    onSecondaryContainer = Color(0xFF051E2E),
    background = Color(0xFFF6F9FA),
    onBackground = Color(0xFF151C20),
    surface = Color(0xFFF6F9FA),
    onSurface = Color(0xFF151C20),
    surfaceVariant = Color(0xFFDEE6EA),
    onSurfaceVariant = Color(0xFF45525A),
    surfaceContainer = Color(0xFFECF1F3),
    surfaceContainerHigh = Color(0xFFE4EBEE),
    surfaceContainerHighest = Color(0xFFDCE4E8),
    outline = Color(0xFF73828B),
    outlineVariant = Color(0xFFC4CED3),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

/** Colours for findings and ratings. Always paired with an icon and a word, never used alone. */
@Immutable
data class StatusColors(val critical: Color, val warning: Color, val info: Color, val good: Color, val neutral: Color) {
    fun of(severity: Severity): Color = when (severity) {
        Severity.CRITICAL -> critical
        Severity.WARNING -> warning
        Severity.INFO -> info
        Severity.GOOD -> good
    }

    fun of(rating: Rating): Color = when (rating) {
        Rating.GREAT -> good
        Rating.OK -> warning
        Rating.POOR -> critical
        Rating.UNKNOWN -> neutral
    }

    /** Colour for a 0..100 health score. */
    fun ofScore(score: Int): Color = when {
        score >= 80 -> good
        score >= 55 -> warning
        else -> critical
    }
}

private val DarkStatus = StatusColors(
    critical = Color(0xFFFF7B72), warning = Color(0xFFF2B84B), info = Color(0xFF79B8FF), good = Color(0xFF56D4A0), neutral = Color(0xFF8E9BA4),
)
private val LightStatus = StatusColors(
    critical = Color(0xFFC62828), warning = Color(0xFF9A6700), info = Color(0xFF1565C0), good = Color(0xFF1B7F5A), neutral = Color(0xFF6B7780),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

@Composable
fun NetworkAnalystTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
