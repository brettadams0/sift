package dev.sift.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A deliberately neutral, low-chroma theme.
 *
 * This app's entire job is judging colour. A tinted or high-chroma surface
 * behind a photograph shifts how its colour reads — simultaneous contrast is not
 * a subtle effect at the magnitudes §6.7 works in, and a warm UI would make
 * every graded skin tone look cooler than it is. Near-neutral greys keep the
 * frame the only coloured thing on screen.
 *
 * The same reasoning rules out Material You dynamic colour: it would paint the
 * review screen with whatever hue the user's wallpaper happens to be, which is
 * the one thing this UI must not do.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C6D2),
    onPrimary = Color(0xFF1A1C1E),
    primaryContainer = Color(0xFF33393F),
    onPrimaryContainer = Color(0xFFD6E1EC),
    secondary = Color(0xFFAFB4B9),
    onSecondary = Color(0xFF1A1C1E),
    secondaryContainer = Color(0xFF2A2B2D),
    onSecondaryContainer = Color(0xFFD8DADC),
    background = Color(0xFF121314),
    onBackground = Color(0xFFE3E3E4),
    surface = Color(0xFF1A1B1C),
    onSurface = Color(0xFFE3E3E4),
    surfaceVariant = Color(0xFF2A2B2D),
    onSurfaceVariant = Color(0xFFBFC1C3),
    surfaceContainer = Color(0xFF202123),
    surfaceContainerHigh = Color(0xFF27282A),
    outline = Color(0xFF5A5C5F),
    outlineVariant = Color(0xFF3A3C3E),
    error = Color(0xFFE59A94),
    onError = Color(0xFF3A0F0C),
    errorContainer = Color(0xFF4C1A16),
    onErrorContainer = Color(0xFFF6D5D2),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F4A54),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE3EA),
    onPrimaryContainer = Color(0xFF1B242C),
    secondary = Color(0xFF565A5E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E7E9),
    onSecondaryContainer = Color(0xFF1A1C1E),
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE6E7E9),
    onSurfaceVariant = Color(0xFF44474A),
    surfaceContainer = Color(0xFFF1F1F3),
    surfaceContainerHigh = Color(0xFFEAEAEC),
    outline = Color(0xFF74777A),
    outlineVariant = Color(0xFFC9CBCD),
    error = Color(0xFFA33A33),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * Slightly tighter and heavier than the Material defaults.
 *
 * Almost every string in this app is either a number the user is deciding on
 * ("34 to delete", "b\* 17.2") or a caption under a photograph. Both want to
 * read at a glance without competing with the image, so headings lose a little
 * tracking and gain weight while body text keeps generous line height.
 *
 * `LineHeightStyle.Trim.None` matters for the readouts under the review frame:
 * the default trims the first line's ascent, which makes a single-line label sit
 * visually higher than a two-line one and the strip appear to jump as you move
 * between photos.
 */
private val SiftTypography = Typography().run {
    val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
    fun TextStyle.tuned(weight: FontWeight? = null, tracking: Float? = null) = copy(
        fontWeight = weight ?: fontWeight,
        letterSpacing = tracking?.sp ?: letterSpacing,
        lineHeightStyle = trim,
    )
    copy(
        headlineLarge = headlineLarge.tuned(FontWeight.SemiBold, (-0.5f)),
        headlineMedium = headlineMedium.tuned(FontWeight.SemiBold, (-0.4f)),
        headlineSmall = headlineSmall.tuned(FontWeight.SemiBold, (-0.3f)),
        titleLarge = titleLarge.tuned(FontWeight.SemiBold, (-0.2f)),
        titleMedium = titleMedium.tuned(FontWeight.Medium),
        bodyMedium = bodyMedium.tuned(),
        bodySmall = bodySmall.tuned(),
        labelLarge = labelLarge.tuned(FontWeight.Medium),
        labelMedium = labelMedium.tuned(FontWeight.Medium),
    )
}

/**
 * Rounder than Material's defaults, because nearly every container in this app
 * holds a photograph and a soft corner reads as a mount rather than a chrome
 * edge.
 */
private val SiftShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * One spacing scale, so screens do not each invent their own.
 *
 * Before this the same visual gap was written as 8, 12 and 16dp on three
 * different screens, which is the kind of thing nobody notices individually and
 * everybody notices in aggregate.
 */
object SiftSpacing {
    val hairline = 2.dp
    val tight = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xlarge = 24.dp
    val huge = 32.dp
}

@Composable
fun SiftTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SiftTypography,
        shapes = SiftShapes,
        content = content,
    )
}
