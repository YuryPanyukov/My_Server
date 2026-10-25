package Code_SyS.nas_server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * Файл, пришедший из системного «Поделиться».
 *
 * Приложение не получает путь на диске — только `content://`-ссылку и
 * метаданные, поэтому чтение и размер идут через `ContentResolver`.
 */
data class SharedFile(
    val uri: Uri,
    val name: String,
    /** Размер из метаданных; `-1`, если провайдер его не сообщает. */
    val size: Long,
) {

    fun openStream(context: Context): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (_: Exception) {
        null
    }

    /**
     * Копирует файл в папку приёма. Если размер неизвестен, сначала
     * материализует поток во временный файл: [FileSaver] принимает точный размер.
     */
    fun saveTo(context: Context, saver: FileSaver): File? {
        val input = openStream(context) ?: return null
        return try {
            if (size >= 0) {
                input.use { saver.save(name, size, it) }
            } else {
                val temp = File.createTempFile("shared-", ".tmp", context.cacheDir)
                try {
                    input.use { source ->
                        temp.outputStream().use { target -> source.copyTo(target) }
                    }
                    temp.inputStream().use { saver.save(name, temp.length(), it) }
                } finally {
                    temp.delete()
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { input.close() }
        }
    }

    companion object {
        /** Все файлы из `ACTION_SEND` / `ACTION_SEND_MULTIPLE`; дубликаты убираются. */
        fun fromIntent(context: Context, intent: Intent?): List<SharedFile> {
            if (intent == null) return emptyList()
            val action = intent.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()

            val uris = LinkedHashSet<Uri>()
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index)?.uri?.let { uris.add(it) }
                }
            }

            @Suppress("DEPRECATION")
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (stream != null) uris.add(stream)

            if (action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.filterNotNull()
                    ?.forEach { uris.add(it) }
            }

            return uris.mapNotNull { read(context, it) }
        }

        /** Читает имя и размер; `null` — у URI нет доступного имени. */
        fun read(context: Context, uri: Uri): SharedFile? {
            var name: String? = null
            var size = -1L

            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                            name = cursor.getString(nameColumn)
                        }
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            size = cursor.getLong(sizeColumn)
                        }
                    }
                }
            } catch (_: Exception) {
                // Имя ниже попробуем достать из пути.
            }

            if (size < 0) {
                size = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) {
                    -1L
                }
            }

            val resolvedName = name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: return null
            return SharedFile(uri = uri, name = resolvedName, size = size)
        }
    }
}
