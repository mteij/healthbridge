package nl.mteij.healthbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The spacing scale. Screens use these instead of one-off dp values so the rhythm stays predictable. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Fixed brand palette (navy and teal), identical on every phone. */
private val Light = lightColorScheme(
    primary = Color(0xFF0B6E8A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEFF8), onPrimaryContainer = Color(0xFF001F28),
    secondary = Color(0xFF2F7F79), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB7ECE6), onSecondaryContainer = Color(0xFF00201E),
    tertiary = Color(0xFF8A5A00), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB0), onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7FAFB), onBackground = Color(0xFF0F2530),
    surface = Color(0xFFF7FAFB), onSurface = Color(0xFF0F2530),
    surfaceVariant = Color(0xFFDCE8EC), onSurfaceVariant = Color(0xFF40525B),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F6F8),
    surfaceContainer = Color(0xFFEAF1F4), surfaceContainerHigh = Color(0xFFE3ECEF),
    surfaceContainerHighest = Color(0xFFDCE6EA),
    outline = Color(0xFF70838D), outlineVariant = Color(0xFFC0D0D6),
    inverseSurface = Color(0xFF24363F), inverseOnSurface = Color(0xFFE9F1F4), inversePrimary = Color(0xFF4FD1C5),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4FD1C5), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFF9EF2E8),
    secondary = Color(0xFF8FD3CC), onSecondary = Color(0xFF003732),
    secondaryContainer = Color(0xFF154E4A), onSecondaryContainer = Color(0xFFB7ECE6),
    tertiary = Color(0xFFFFB84D), onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF663E00), onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF041824), onBackground = Color(0xFFE1EBEF),
    surface = Color(0xFF041824), onSurface = Color(0xFFE1EBEF),
    surfaceVariant = Color(0xFF213742), onSurfaceVariant = Color(0xFFB4C7CF),
    surfaceContainerLowest = Color(0xFF02101A), surfaceContainerLow = Color(0xFF08202E),
    surfaceContainer = Color(0xFF0C2736), surfaceContainerHigh = Color(0xFF123140),
    surfaceContainerHighest = Color(0xFF1A3B4A),
    outline = Color(0xFF7F939C), outlineVariant = Color(0xFF2E4551),
    inverseSurface = Color(0xFFE1EBEF), inverseOnSurface = Color(0xFF1B2B34), inversePrimary = Color(0xFF0B6E8A),
)

/** Corner scale: components pick a role (small, medium, large, extraLarge) instead of a radius. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HealthBridgeTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        content = content,
    )
}
