package dev.daniel.tvhub.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val OnlineGreen = Color(0xFF4CD964)
val DimAmber = Color(0xFFFFB340)
val OfflineRed = Color(0xFFFF6B6B)
val IdleGrey = Color(0xFF8E8E93)

@Composable
fun TvHubTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current)
    else darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFFF6AEDA), tertiary = Color(0xFF7FD8BE))
    MaterialTheme(colorScheme = scheme, content = content)
}
