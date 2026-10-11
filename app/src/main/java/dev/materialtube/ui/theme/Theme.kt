package dev.materialtube.ui.theme

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

private val Red = Color(0xFFE53935)

private val Dark = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF0F0F0F),
    surfaceContainer = Color(0xFF1C1C1C),
)

private val Light = lightColorScheme(primary = Red, onPrimary = Color.White)

@Composable
fun MaterialTubeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context).copy(primary = Red, onPrimary = Color.White)
            else dynamicLightColorScheme(context).copy(primary = Red, onPrimary = Color.White)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
