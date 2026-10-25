package nl.mteij.healthbridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF0B6E8A),
    secondary = Color(0xFF2F7F79),
    tertiary = Color(0xFF8A5A00),
    background = Color(0xFFF7FAFB),
    surface = Color(0xFFF7FAFB),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    secondary = Color(0xFF8FD3CC),
    tertiary = Color(0xFFFFB84D),
    background = Color(0xFF041824),
    surface = Color(0xFF041824),
)

@Composable
fun HealthBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
