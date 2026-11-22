package app.fntv.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF060910)
val BgSoft = Color(0xFF0B1120)
val Surface1 = Color(0xFF131B2C)
val Surface2 = Color(0xFF1C2740)
val Accent = Color(0xFF4F9CFF)
val AccentSoft = Color(0xFF1D3A66)
val Gold = Color(0xFFE8B95C)
val TextPrimary = Color(0xFFF3F6FC)
val TextSecondary = Color(0xFF97A3BA)
val TextDim = Color(0xFF69758C)
val Danger = Color(0xFFFF6B6B)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04101F),
    secondary = Gold,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    error = Danger,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    bodyLarge = TextStyle(fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 13.sp, color = TextSecondary),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    labelMedium = TextStyle(fontSize = 12.sp, color = TextSecondary),
)

@Composable
fun FnTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}