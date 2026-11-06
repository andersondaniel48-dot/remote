package dev.daniel.tvhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0A0B10)
val Panel = Color(0xFF14161F)
val PanelHi = Color(0xFF1D2030)
val Line = Color(0xFF2A2E40)
val TextHi = Color(0xFFF4F4F8)
val TextLo = Color(0xFF9A9CB0)
val Violet = Color(0xFF8B6CFF)
val Pink = Color(0xFFFF5FA2)
val Peach = Color(0xFFFFA26B)

val OnlineGreen = Color(0xFF3DDC97)
val DimAmber = Color(0xFFFFC14D)
val OfflineRed = Color(0xFFFF6B7A)
val IdleGrey = Color(0xFF6E7186)

val Glow = Brush.linearGradient(listOf(Violet, Pink, Peach))
val GlowSoft = Brush.linearGradient(listOf(Violet.copy(alpha = 0.35f), Pink.copy(alpha = 0.25f), Peach.copy(alpha = 0.2f)))

private val scheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2350),
    onPrimaryContainer = TextHi,
    secondary = Pink,
    tertiary = Peach,
    background = Ink,
    onBackground = TextHi,
    surface = Panel,
    onSurface = TextHi,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextLo,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHi,
    surfaceContainerLow = Panel,
    outline = Line,
    outlineVariant = Line,
)

private val base = Typography()
private val type = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

@Composable
fun TvHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = type,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
