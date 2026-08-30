package app.redlib.now.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-only, warm-neutral surfaces with the classic orange accent.
private val UpvoteOrange = Color(0xFFFF4500)
private val AccentBlue = Color(0xFF69A1FF)
private val DarkBg = Color(0xFF101010)
private val DarkSurface = Color(0xFF1A1715)
private val DarkSurfaceVariant = Color(0xFF26211D)

private val DarkColors = darkColorScheme(
    primary = UpvoteOrange,
    onPrimary = Color.White,
    secondary = AccentBlue,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    background = DarkBg,
    onSurface = Color(0xFFEDE7E2),
    onSurfaceVariant = Color(0xFFB8AEA6),
)

@Composable
fun NowRedlibTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

object NowColors {
    val Nsfw = Color(0xFFE23B3B)
    val Spoiler = Color(0xFFB8860B)
}

/** Deterministic per-subreddit accent color (classic "subreddit theme"). */
fun subredditColor(name: String): Color {
    var h = 0
    for (c in name.lowercase()) h = (h * 31 + c.code) and 0x7fffffff
    return Color.hsv((h % 360).toFloat(), 0.55f, 0.95f)
}
