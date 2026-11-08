package com.example.silentwhileusing.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F5B92),
    secondary = Color(0xFF5B5D72),
    tertiary = Color(0xFF77536D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C4FF),
    secondary = Color(0xFFC4C5DD),
    tertiary = Color(0xFFE6BAD7)
)

/** Material 3 theme; uses the wallpaper-based dynamic colours on Android 12+. */
@Composable
fun SilentWhileUsingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
