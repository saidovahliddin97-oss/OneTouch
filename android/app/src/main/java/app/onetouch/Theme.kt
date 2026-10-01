package app.onetouch

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal val Bg = Color(0xFF0B0D12)
internal val CardBg = Color(0xFF151923)
internal val CardBg2 = Color(0xFF1C2230)
internal val Accent = Color(0xFF4F8CFF)
internal val Ok = Color(0xFF2FBF71)
internal val Warn = Color(0xFFFF9F43)
internal val Muted = Color(0xFF8B95A8)

fun oneTouchColors(): ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A70),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondaryContainer = Color(0xFF233251),
    onSecondaryContainer = Color(0xFFD8E4FF),
    background = Bg,
    surface = CardBg,
    surfaceVariant = CardBg2,
    surfaceContainer = CardBg,
    onSurface = Color(0xFFE8ECF3),
    onSurfaceVariant = Muted,
)
