@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.pegoku.androiduse.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pegoku.androiduse.R
import dev.pegoku.androiduse.Settings

/**
 * App theme. The palette borrows from Android's own developer overlays: ultramarine for the
 * interface, like layout bounds, and magenta for anything the agent touches, like a tap marker.
 * Corners are tight; only touch points (the mark, buttons, the avatar) are round.
 */
@Composable
fun AndroidUseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    Appearance.load(context)
    val colors = when {
        Appearance.wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> InkDark
        else -> InkLight
    }
    // Material 3 Expressive: its components, with spring motion that overshoots a little and settles.
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Look-and-feel choices the theme reads live, so changing one in Settings repaints at once. */
object Appearance {
    /** Use the wallpaper's dynamic colors (Android 12+) instead of the app's own palette. */
    var wallpaperColors by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        wallpaperColors = Settings(context).wallpaperColors
        loaded = true
    }

    fun setWallpaperColors(context: Context, enabled: Boolean) {
        Settings(context).wallpaperColors = enabled
        wallpaperColors = enabled
    }
}

val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f

/** Green used for "done" states, readable on both the light and the dark scheme. */
val ColorScheme.success: Color get() = if (isDark) Color(0xFF7DD6A0) else Color(0xFF1B7F3B)

/** Fill of things that sit on top of the page: cards, the composer. */
val ColorScheme.card: Color get() = if (isDark) surfaceContainer else surfaceContainerLowest

/** The hairline around cards. */
val ColorScheme.hairline: Color get() = outlineVariant.copy(alpha = if (isDark) 0.7f else 0.8f)

private val InkLight = lightColorScheme(
    primary = Color(0xFF3A36E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF0B0768),
    secondary = Color(0xFF5A5C7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E1F4),
    onSecondaryContainer = Color(0xFF171932),
    tertiary = Color(0xFFC2187A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8EA),
    onTertiaryContainer = Color(0xFF3D0024),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF15172B),
    surface = Color(0xFFF5F6FB),
    onSurface = Color(0xFF15172B),
    surfaceVariant = Color(0xFFE1E2EE),
    onSurfaceVariant = Color(0xFF50536B),
    outline = Color(0xFF7A7D95),
    outlineVariant = Color(0xFFC9CBDB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF0F7),
    surfaceContainer = Color(0xFFE9EAF3),
    surfaceContainerHigh = Color(0xFFE3E4EE),
    surfaceContainerHighest = Color(0xFFDCDDE9),
    inverseSurface = Color(0xFF2A2C40),
    inverseOnSurface = Color(0xFFF0F0FA),
    inversePrimary = Color(0xFFB4B6FF),
    surfaceTint = Color(0xFF3A36E0),
    scrim = Color(0xFF05060F),
)

private val InkDark = darkColorScheme(
    primary = Color(0xFFB4B6FF),
    onPrimary = Color(0xFF14127A),
    primaryContainer = Color(0xFF2C2AA8),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFFC1C2E0),
    onSecondary = Color(0xFF2A2C45),
    secondaryContainer = Color(0xFF34364F),
    onSecondaryContainer = Color(0xFFE1E1F4),
    tertiary = Color(0xFFFF86C0),
    onTertiary = Color(0xFF5C0036),
    tertiaryContainer = Color(0xFF7D1050),
    onTertiaryContainer = Color(0xFFFFD8EA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0F1C),
    onBackground = Color(0xFFE4E4F2),
    surface = Color(0xFF0E0F1C),
    onSurface = Color(0xFFE4E4F2),
    surfaceVariant = Color(0xFF3A3C55),
    onSurfaceVariant = Color(0xFFB4B6CC),
    outline = Color(0xFF8587A0),
    outlineVariant = Color(0xFF3A3C55),
    surfaceContainerLowest = Color(0xFF090A14),
    surfaceContainerLow = Color(0xFF151627),
    surfaceContainer = Color(0xFF1A1B2E),
    surfaceContainerHigh = Color(0xFF232439),
    surfaceContainerHighest = Color(0xFF2D2E45),
    inverseSurface = Color(0xFFE4E4F2),
    inverseOnSurface = Color(0xFF2A2C40),
    inversePrimary = Color(0xFF3A36E0),
    surfaceTint = Color(0xFFB4B6FF),
    scrim = Color.Black,
)

private fun variable(resource: Int, weight: FontWeight, style: FontStyle = FontStyle.Normal, vararg more: FontVariation.Setting) =
    Font(resource, weight, style, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), *more))

/** Bricolage's optical size for headline use: tighter and more contrasted than its text cut. */
private val Headline = FontVariation.Setting("opsz", 32f)

/** Bricolage Grotesque: the voice of the app, for the greeting and titles only. */
val Display = FontFamily(
    variable(R.font.bricolage_grotesque, FontWeight.Medium, more = arrayOf(Headline)),
    variable(R.font.bricolage_grotesque, FontWeight.SemiBold, more = arrayOf(Headline)),
    variable(R.font.bricolage_grotesque, FontWeight.Bold, more = arrayOf(Headline)),
)

/** Instrument Sans: everything that is read. */
val Body = FontFamily(
    variable(R.font.instrument_sans, FontWeight.Normal),
    variable(R.font.instrument_sans, FontWeight.Medium),
    variable(R.font.instrument_sans, FontWeight.SemiBold),
    variable(R.font.instrument_sans_italic, FontWeight.Normal, FontStyle.Italic),
    variable(R.font.instrument_sans_italic, FontWeight.SemiBold, FontStyle.Italic),
)

/** JetBrains Mono: what the agent did, model names and other machine facts. */
val Mono = FontFamily(
    variable(R.font.jetbrains_mono, FontWeight.Normal),
    variable(R.font.jetbrains_mono, FontWeight.Medium),
)

private fun display(size: Int, line: Int, spacing: Double, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = Display, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
    TextStyle(fontFamily = Body, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private val AppTypography = Typography(
    displayLarge = display(48, 52, -1.2),
    displayMedium = display(40, 44, -1.0),
    displaySmall = display(34, 38, -0.8),
    headlineLarge = display(30, 36, -0.6),
    headlineMedium = display(26, 32, -0.4),
    headlineSmall = display(22, 28, -0.3),
    titleLarge = display(21, 28, -0.2),
    titleMedium = body(16, 22, FontWeight.SemiBold),
    titleSmall = body(14, 20, FontWeight.SemiBold),
    bodyLarge = body(16, 24),
    bodyMedium = body(14, 20),
    bodySmall = body(12, 17),
    labelLarge = body(14, 20, FontWeight.Medium),
    labelMedium = body(12, 16, FontWeight.Medium),
    // The eyebrow: small capitals in mono above cards and sections.
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)
