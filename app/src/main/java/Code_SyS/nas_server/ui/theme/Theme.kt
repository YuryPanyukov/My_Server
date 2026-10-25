package Code_SyS.nas_server.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Палитра LanDrop: тёмная по умолчанию, светлая — по системной теме.
 * Цвета совпадают с веб-интерфейсом, чтобы приложение и страница в браузере
 * выглядели как один продукт.
 */
val LanDropDarkScheme = darkColorScheme(
    primary = Color(0xFF7B9CFF),
    onPrimary = Color(0xFF081018),
    primaryContainer = Color(0xFF1B2A4A),
    onPrimaryContainer = Color(0xFFD8E3FF),
    secondary = Color(0xFFB98CFF),
    onSecondary = Color(0xFF140B24),
    background = Color(0xFF0A0C11),
    onBackground = Color(0xFFEEF2F7),
    surface = Color(0xFF11151D),
    onSurface = Color(0xFFEEF2F7),
    surfaceVariant = Color(0xFF1A2030),
    onSurfaceVariant = Color(0xFF98A2B3),
    outline = Color(0xFF2A3244),
    outlineVariant = Color(0xFF202635),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF2B0A0A),
)

val LanDropLightScheme = lightColorScheme(
    primary = Color(0xFF3B6EF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4EBFF),
    onPrimaryContainer = Color(0xFF12224F),
    secondary = Color(0xFF8A5CF6),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFEEF1F7),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFFD5DBE7),
    outlineVariant = Color(0xFFE4E8F0),
    error = Color(0xFFD92D20),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun LanDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LanDropDarkScheme else LanDropLightScheme,
        content = content,
    )
}
