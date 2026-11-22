package Code_SyS.nas_server

import java.io.File
import java.util.Locale

/**
 * Имя служебного файла с описанием видео. Лежит рядом с самим видео
 * (`Отпуск.mp4.library.json`) и переезжает вместе с ним при переименовании,
 * перемещении и удалении в корзину.
 */
internal object MediaSidecar {
    const val SUFFIX = ".library.json"

    fun isSidecar(name: String): Boolean = name.endsWith(SUFFIX)

    fun forVideo(file: File): File = File(file.parentFile, file.name + SUFFIX)
}

/**
 * Карточка видеотеки: видеофайл из папки приёма и его описание.
 * [hasDescription] — у видео есть сохранённые заголовок/описание.
 */
data class LibraryItem(
    val path: String,
    val name: String,
    val title: String,
    val description: String,
    val size: Long,
    val modified: Long,
    val updatedAt: Long,
    val hasDescription: Boolean,
)

/**
 * Видеотека поверх папки приёма: список видео и их описание.
 *
 * Описание хранится в JSON-сайдкаре рядом с файлом, поэтому переезжает
 * вместе с ним и не требует базы данных. Сайдкары скрыты из списков файлов,
 * галереи и ZIP: это внутренний формат приложения.
 *
 * Класс не использует Android API и проверяется JVM-тестами.
 */
class MediaLibrary(private val rootProvider: () -> File?) {

    private val storage = ReceivedStorage(rootProvider)

    /** Все видео хранилища, новые сверху. */
    fun items(): List<LibraryItem> {
        val root = rootProvider() ?: return emptyList()
        return storage.allFiles()
            .filter { it.isFile && isVideo(it.name) }
            .mapNotNull { file -> itemOf(root, file) }
            .sortedByDescending { it.modified }
    }

    /** Одна карточка по относительному пути; `null` — это не видео. */
    fun item(path: String?): LibraryItem? {
        val root = rootProvider() ?: return null
        val relative = StoragePaths.normalize(path) ?: return null
        if (relative.isEmpty()) return null
        val file = storage.resolve(relative) ?: return null
        if (!file.isFile || !isVideo(file.name)) return null
        return itemOf(root, file)
    }

    /**
     * Сохраняет заголовок и описание. Пустой заголовок заменяется именем файла.
     * `null` — путь недопустим или это не видео.
     */
    fun save(path: String?, title: String, description: String): LibraryItem? {
        val root = rootProvider() ?: return null
        val relative = StoragePaths.normalize(path) ?: return null
        if (relative.isEmpty()) return null
        val file = storage.resolve(relative) ?: return null
        if (!file.isFile || !isVideo(file.name)) return null

        val safeTitle = clean(title).take(MAX_TITLE)
        val safeDescription = clean(description).take(MAX_DESCRIPTION)
        val updatedAt = System.currentTimeMillis()
        val payload = buildString {
            append("{\"title\":").append(jsonString(safeTitle))
            append(",\"description\":").append(jsonString(safeDescription))
            append(",\"updatedAt\":").append(updatedAt)
            append('}')
        }

        // Пишем через временный файл: оборванная запись не испортит описание.
        val sidecar = MediaSidecar.forVideo(file)
        val temp = File(file.parentFile, sidecar.name + ".tmp")
        val written = try {
            temp.writeText(payload, Charsets.UTF_8)
            temp.renameTo(sidecar)
        } catch (_: Exception) {
            false
        }
        if (!written) {
            temp.delete()
            return null
        }

        return LibraryItem(
            path = relative,
            name = file.name,
            title = safeTitle.ifBlank { titleFromName(file.name) },
            description = safeDescription,
            size = file.length(),
            modified = file.lastModified(),
            updatedAt = updatedAt,
            hasDescription = true,
        )
    }

    /** Убирает описание: карточка снова показывает имя файла. */
    fun reset(path: String?): Boolean {
        val file = storage.resolve(StoragePaths.normalize(path) ?: return false) ?: return false
        if (!file.isFile || !isVideo(file.name)) return false
        val sidecar = MediaSidecar.forVideo(file)
        return !sidecar.exists() || sidecar.delete()
    }

    private fun itemOf(root: File, file: File): LibraryItem? {
        val path = StoragePaths.relativePath(root, file) ?: return null
        val meta = readMeta(file)
        return LibraryItem(
            path = path,
            name = file.name,
            title = meta?.first?.takeIf { it.isNotBlank() } ?: titleFromName(file.name),
            description = meta?.second.orEmpty(),
            size = file.length(),
            modified = file.lastModified(),
            updatedAt = meta?.third ?: 0L,
            hasDescription = meta != null,
        )
    }

    /** Заголовок, описание и время правки из сайдкара. */
    private fun readMeta(file: File): Triple<String, String, Long>? {
        val sidecar = MediaSidecar.forVideo(file)
        if (!sidecar.isFile) return null
        val text = try {
            sidecar.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val titleField = jsonField(text, "title")
        val descriptionField = jsonField(text, "description")
        // Посторонний или битый файл не должен превращаться в пустую карточку.
        if (titleField == null && descriptionField == null) return null
        val updatedAt = UPDATED_AT.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Triple(titleField.orEmpty(), descriptionField.orEmpty(), updatedAt)
    }

    private fun jsonString(value: String): String = buildString(value.length + 8) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format(Locale.US, "\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    /**
     * Читает строковое поле своего же JSON. Экранирование поддерживается
     * полностью — описание может содержать кавычки, слэши и переводы строк.
     */
    private fun jsonField(text: String, key: String): String? {
        val marker = "\"$key\""
        var index = text.indexOf(marker)
        if (index < 0) return null
        index = text.indexOf(':', index + marker.length)
        if (index < 0) return null
        index++
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length || text[index] != '"') return null
        index++

        val result = StringBuilder()
        while (index < text.length) {
            when (val c = text[index++]) {
                '"' -> return result.toString()
                '\\' -> {
                    if (index >= text.length) return null
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'u' -> {
                            if (index + 4 > text.length) return null
                            val code = text.substring(index, index + 4).toIntOrNull(16) ?: return null
                            result.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                }
                else -> result.append(c)
            }
        }
        return null
    }

    private fun clean(value: String): String = value.replace("\u0000", "").trim()

    companion object {
        const val MAX_TITLE = 200
        const val MAX_DESCRIPTION = 5000

        private val UPDATED_AT = Regex("\"updatedAt\"\\s*:\\s*(\\d+)")

        /** Видеотека показывает те же файлы, что и списки: одна таблица [FileKind]. */
        fun isVideo(name: String): Boolean = FileKind.ofFileName(name) == FileKind.VIDEO

        /** «Отпуск 2024» из `Отпуск 2024.mp4`. */
        fun titleFromName(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }
    }
}
