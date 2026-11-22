package Code_SyS.nas_server

import java.util.Locale

/**
 * Вид записи в списке файлов: папка или файл одного из типов.
 *
 * Таблица расширений здесь одна на всё приложение — сетка сервера, клиент,
 * веб-страница и видеотека. Раньше она копировалась, и `3gp` с `avif`
 * получали разные значки в браузере и в приложении.
 *
 * [FOLDER] выдаётся только для каталога; [ofFileName] его никогда не вернёт.
 */
internal enum class FileKind(
    /** Надпись на значке: 3–4 символа, как в веб-интерфейсе. */
    val badge: String,
    /** CSS-категория `data-kind` на странице. */
    val cssClass: String,
    /** Расширения без точки, в нижнем регистре. */
    val extensions: Set<String>,
) {
    IMAGE("IMG", "image", setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg", "avif")),
    VIDEO("VID", "video", setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "ts", "m2ts")),
    AUDIO("AUD", "audio", setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "oga", "opus")),
    DOC("DOC", "doc", setOf("pdf", "doc", "docx", "txt", "md", "rtf", "xls", "xlsx", "ppt", "pptx")),
    ARCHIVE("ZIP", "archive", setOf("zip", "rar", "7z", "tar", "gz")),
    FOLDER("DIR", "folder", emptySet()),
    OTHER("FILE", "other", emptySet()),
    ;

    /** Массив расширений для очереди загрузки в браузере. */
    val jsArray: String
        get() = extensions.joinToString(",", "[", "]") { "'$it'" }

    companion object {
        /** Тип файла по имени; всё неизвестное — [OTHER]. */
        fun ofFileName(name: String): FileKind {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension.isEmpty()) return OTHER
            return entries.firstOrNull { extension in it.extensions } ?: OTHER
        }
    }
}
