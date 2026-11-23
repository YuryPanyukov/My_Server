package Code_SyS.nas_server.ui.theme

import Code_SyS.nas_server.FileKind
import androidx.compose.ui.graphics.Color

/**
 * Цвет значка типа файла: один и тот же в сетке сервера и в списке клиента.
 * Сами цвета совпадают с веб-интерфейсом.
 */
internal val FileKind.tint: Color
    get() = when (this) {
        FileKind.FOLDER -> Color(0xFF7B9CFF)
        FileKind.IMAGE -> Color(0xFFF5A623)
        FileKind.VIDEO -> Color(0xFFB98CFF)
        FileKind.AUDIO -> Color(0xFF38D6C4)
        FileKind.DOC -> Color(0xFF6C9CFF)
        FileKind.ARCHIVE -> Color(0xFFFF8A8A)
        FileKind.OTHER -> Color(0xFF98A2B3)
    }
