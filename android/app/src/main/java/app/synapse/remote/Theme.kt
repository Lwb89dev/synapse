package app.synapse.remote

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF8DDB9B), onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF1F5130), onPrimaryContainer = Color(0xFFA9F5B6),
    secondary = Color(0xFFB7CCB8), tertiary = Color(0xFFA2CED9),
    background = Color(0xFF101411), surface = Color(0xFF101411), onSurface = Color(0xFFE0E4DE),
    surfaceContainerLowest = Color(0xFF0B0F0C), surfaceContainerLow = Color(0xFF181C19),
    surfaceContainer = Color(0xFF1C201D), surfaceContainerHigh = Color(0xFF262B27),
)
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF256A3E), primaryContainer = Color(0xFFA9F5B6),
    background = Color(0xFFF7FBF4), surface = Color(0xFFF7FBF4),
)

private fun monoTypography(): Typography {
    val m = FontFamily.Monospace
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.copy(fontFamily = m), displayMedium = t.displayMedium.copy(fontFamily = m),
        displaySmall = t.displaySmall.copy(fontFamily = m),
        headlineLarge = t.headlineLarge.copy(fontFamily = m), headlineMedium = t.headlineMedium.copy(fontFamily = m),
        headlineSmall = t.headlineSmall.copy(fontFamily = m),
        titleLarge = t.titleLarge.copy(fontFamily = m), titleMedium = t.titleMedium.copy(fontFamily = m),
        titleSmall = t.titleSmall.copy(fontFamily = m),
        bodyLarge = t.bodyLarge.copy(fontFamily = m), bodyMedium = t.bodyMedium.copy(fontFamily = m),
        bodySmall = t.bodySmall.copy(fontFamily = m),
        labelLarge = t.labelLarge.copy(fontFamily = m), labelMedium = t.labelMedium.copy(fontFamily = m),
        labelSmall = t.labelSmall.copy(fontFamily = m),
    )
}

/** Material You: wallpaper-derived dynamic colours on Android 12+, a green fallback below. Monospace everywhere. */
@Composable
fun SynapseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, typography = monoTypography(), content = content)
}
