package com.localstickers.app.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7D5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F1D4),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6358),
    secondaryContainer = Color(0xFFCEE9DA),
    tertiary = Color(0xFF3E6374),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7ADBB4),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFFB2F1D4),
    secondary = Color(0xFFB3CCBF),
    secondaryContainer = Color(0xFF354B41),
    tertiary = Color(0xFFA6CCDF),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
)

@Composable
fun LocalStickersTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
