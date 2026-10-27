package Code_SyS.nas_server

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream

/**
 * Фото и видео из медиатеки телефона (DCIM, Pictures, Movies, скриншоты)
 * через [MediaStore].
 *
 * Доступ к чужим приложениям не нужен: медиатека читается через системный
 * индекс, а без выданного разрешения [items] просто возвращает пустой список.
 */
class DeviceMediaSource(private val context: Context) : MediaSource {

    override val key: String = HttpRequestHandler.DEVICE_SOURCE_KEY
    override val title: String = "Фото и видео телефона"

    /**
     * Снимок медиатеки. Страница галереи грузит сотни превью, и каждый запрос
     * ищет элемент в списке — без кеша это была бы сотня запросов к MediaStore.
     */
    @Volatile
    private var cache: List<MediaItem>? = null

    @Volatile
    private var cacheTime: Long = 0L

    override fun items(): List<MediaItem> {
        val now = System.currentTimeMillis()
        cache?.let { cached ->
            if (now - cacheTime < CACHE_TTL_MS) return cached
        }

        val images = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaItem.Kind.IMAGE)
        val videos = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaItem.Kind.VIDEO)
        val fresh = (images + videos)
            .sortedByDescending { it.dateMillis }
            .take(MAX_ITEMS)
        cache = fresh
        cacheTime = now
        return fresh
    }

    private fun query(collection: Uri, kind: MediaItem.Kind): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        return try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                val result = ArrayList<MediaItem>()
                while (result.size < MAX_ITEMS && cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "media-$id"
                    val size = cursor.getLong(sizeColumn)
                    val modified = maxOf(
                        cursor.getLong(dateAddedColumn) * 1000,
                        cursor.getLong(dateModifiedColumn) * 1000,
                    )
                    val mime = cursor.getString(mimeColumn) ?: defaultMime(kind)
                    result.add(
                        MediaItem(
                            id = idPrefix(kind) + id,
                            name = name,
                            size = size,
                            dateMillis = modified,
                            mimeType = mime,
                            kind = kind,
                        )
                    )
                }
                result
            } ?: emptyList()
        } catch (_: Exception) {
            // Нет разрешения или медиатека недоступна — галерея просто пустая.
            emptyList()
        }
    }

    override fun open(item: MediaItem): InputStream? {
        val uri = uriOf(item) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    override fun thumbnail(item: MediaItem): ByteArray? {
        val uri = uriOf(item) ?: return null
        val mediaId = item.id.substringAfter('/').toLongOrNull() ?: return null
        return Thumbnails.fromUri(context, uri, item.mimeType, mediaId)
    }

    /**
     * Удаляет фото или видео телефона. На Android 11+ чужие медиа можно
     * удалить только после подтверждения: тогда запрос откладывается и
     * показывается карточка/уведомление в приложении.
     */
    override fun delete(item: MediaItem): MediaDeleteResult {
        val uri = uriOf(item) ?: return MediaDeleteResult.NOT_FOUND
        return try {
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted > 0) {
                invalidate()
                MediaDeleteResult.DELETED
            } else {
                MediaDeleteResult.NOT_FOUND
            }
        } catch (_: SecurityException) {
            // Системный диалог подтверждения есть только на Android 11+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PendingDeletes.request(context, uri)
                MediaDeleteResult.NEEDS_CONSENT
            } else {
                MediaDeleteResult.FAILED
            }
        } catch (_: Exception) {
            MediaDeleteResult.FAILED
        }
    }

    /** Сбрасывает кэш после удаления — сетка не должна показывать исчезнувший снимок. */
    fun invalidate() {
        cache = null
        cacheTime = 0L
    }

    private fun uriOf(item: MediaItem): Uri? {
        val kind = when {
            item.id.startsWith(IMAGE_PREFIX) -> MediaItem.Kind.IMAGE
            item.id.startsWith(VIDEO_PREFIX) -> MediaItem.Kind.VIDEO
            else -> return null
        }
        val id = item.id.substringAfter('/').toLongOrNull() ?: return null
        val collection = if (kind == MediaItem.Kind.IMAGE) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(collection, id)
    }

    private fun idPrefix(kind: MediaItem.Kind): String =
        if (kind == MediaItem.Kind.IMAGE) IMAGE_PREFIX else VIDEO_PREFIX

    private fun defaultMime(kind: MediaItem.Kind): String =
        if (kind == MediaItem.Kind.IMAGE) "image/jpeg" else "video/mp4"

    private companion object {
        const val IMAGE_PREFIX = "image/"
        const val VIDEO_PREFIX = "video/"

        /** Ограничение на размер ответа MediaStore: страница должна открываться быстро. */
        const val MAX_ITEMS = 2000

        /** Как часто перечитывать медиатеку: новые снимки появятся после перезагрузки страницы. */
        const val CACHE_TTL_MS = 15_000L
    }
}
