package com.cruze.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Red on black. One accent colour, near-black surfaces, nothing decorative — a rider reads
 * this through a visor in direct sun or at night, often with gloves on, so contrast and
 * target size beat styling every time.
 */
object CruzeColors {
    val Red = Color(0xFFFF3B30)
    val RedBright = Color(0xFFFF5C52)
    val RedDeep = Color(0xFF8E1109)
    val RedDim = Color(0xFF4A0A06)

    val Black = Color(0xFF0A0A0C)
    val Raised = Color(0xFF141417)
    val Card = Color(0xFF1D1E22)
    val Line = Color(0xFF33353C)

    val Text = Color(0xFFF2F3F5)
    val TextDim = Color(0xFFA8ACB4)

    /** Route line drawn on the map, and its dark casing so it reads over any basemap. */
    val RouteLine = Red
    val RouteCasing = Color(0xFF12070A)
    val TrackLine = Color(0xFF4FC3F7)
}

private val DarkScheme = darkColorScheme(
    primary = CruzeColors.Red,
    onPrimary = Color.White,
    primaryContainer = CruzeColors.RedDeep,
    onPrimaryContainer = Color(0xFFFFDAD6),
    inversePrimary = CruzeColors.RedBright,

    secondary = CruzeColors.RedBright,
    onSecondary = Color.White,
    secondaryContainer = CruzeColors.RedDim,
    onSecondaryContainer = Color(0xFFFFD9D5),

    tertiary = Color(0xFFB8BCC4),
    onTertiary = CruzeColors.Black,

    background = CruzeColors.Black,
    onBackground = CruzeColors.Text,
    surface = CruzeColors.Black,
    onSurface = CruzeColors.Text,
    surfaceVariant = CruzeColors.Card,
    onSurfaceVariant = CruzeColors.TextDim,
    surfaceContainerLowest = Color(0xFF060607),
    surfaceContainerLow = CruzeColors.Raised,
    surfaceContainer = CruzeColors.Raised,
    surfaceContainerHigh = CruzeColors.Card,
    surfaceContainerHighest = Color(0xFF26272C),

    outline = CruzeColors.Line,
    outlineVariant = Color(0xFF26272C),

    error = Color(0xFFFF8A80),
    onError = Color(0xFF2A0000),
    errorContainer = CruzeColors.RedDeep,
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Kept for the rare rider who forces light mode; the app defaults to dark. */
private val LightScheme = lightColorScheme(
    primary = Color(0xFFC62828),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF121316),
    surface = Color.White,
    onSurface = Color(0xFF121316),
    surfaceVariant = Color(0xFFEDEEF1),
    onSurfaceVariant = Color(0xFF4A4E56),
    error = Color(0xFFB3261E),
)

/** Slightly larger and heavier than stock Material — legibility at a glance. */
private val RiderType = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

/** Minimum comfortable touch target with gloves on. */
val GloveTarget = 56.dp

/** Dark is the default; [dark] is wired to the "Light theme" switch in settings. */
@Composable
fun CruzeTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = RiderType,
        content = content,
    )
}
