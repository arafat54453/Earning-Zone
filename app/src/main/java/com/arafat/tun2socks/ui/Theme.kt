package com.arafat.tun2socks.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.arafat.tun2socks.data.ThemeMode

val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val CardBg: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
val ScreenBg: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val UploadBlue = Color(0xFF4D8DF6)
val DownloadGreen = Color(0xFF2EB872)
val WarnAmber = Color(0xFFFFA726)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B2F8),
    onPrimary = Color(0xFF1B1A3C),
    primaryContainer = Color(0xFF2E2A5C),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFF9A93E0),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFEDEDF3),
    surface = Color(0xFF17171E),
    onSurface = Color(0xFFEDEDF3),
    surfaceVariant = Color(0xFF22222B),
    onSurfaceVariant = Color(0xFF9A9AA8),
    surfaceContainer = Color(0xFF17171E),
    outline = Color(0xFF3A3A46),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4FD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E2FF),
    onPrimaryContainer = Color(0xFF1B1366),
    secondary = Color(0xFF6E64C8),
    background = Color(0xFFF4F4F8),
    onBackground = Color(0xFF15151C),
    surface = Color.White,
    onSurface = Color(0xFF15151C),
    surfaceVariant = Color(0xFFECECF3),
    onSurfaceVariant = Color(0xFF5E5E6E),
    surfaceContainer = Color.White,
    outline = Color(0xFFD3D3DE),
    error = Color(0xFFD32F2F),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun Tun2SocksTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDarkTheme(mode)) DarkColors else LightColors, typography = Typography(), content = content)
}
