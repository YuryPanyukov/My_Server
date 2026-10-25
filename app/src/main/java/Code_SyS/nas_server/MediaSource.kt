package Code_SyS.nas_server

import java.io.File
import java.io.InputStream

/**
 * Элемент галереи: файл, принятый приложением, или фото/видео из медиатеки
 * телефона.
 */
data class MediaItem(
    val id: String,
    val name: String,
    val size: Long,
    val dateMillis: Long,
    val mimeType: String,
    val kind: Kind,
) {
    enum class Kind { IMAGE, VIDEO, OTHER }

    companion object {
        fun kindOf(mimeType: String): Kind = when {
            mimeType.startsWith("image/") -> Kind.IMAGE
            mimeType.startsWith("video/") -> Kind.VIDEO
            else -> Kind.OTHER
        }
    }
}

/**
 * Источник элементов для галереи в браузере. Реализации не должны бросать
 * исключения при отсутствии доступа — лучше вернуть пустой список.
 */
interface MediaSource {
    /** Короткий ключ источника, попадает в URL (`/media?key=...`). */
    val key: String

    /** Подпись вкладки. */
    val title: String

    /** Элементы, новые сверху. */
    fun items(): List<MediaItem>

    /** Поток содержимого либо `null`, если файл больше недоступен. */
    fun open(item: MediaItem): InputStream?

    /** JPEG-превью либо `null` — тогда браузер покажет оригинал. */
    fun thumbnail(item: MediaItem): ByteArray? = null
}

/** Принятые приложением файлы, показанные как медиа. */
class ReceivedMediaSource(
    private val files: () -> List<File>,
    private val thumbnailer: (File) -> ByteArray? = { null },
) : MediaSource {

    override val key: String = "received"
    override val title: String = "Принятые"

    override fun items(): List<MediaItem> = files().map { file ->
        val mime = MimeTypes.forFileName(file.name)
        MediaItem(
            id = file.name,
            name = file.name,
            size = file.length(),
            dateMillis = file.lastModified(),
            mimeType = mime,
            kind = MediaItem.kindOf(mime),
        )
    }

    override fun open(item: MediaItem): InputStream? =
        files().firstOrNull { it.name == item.id }?.inputStream()

    override fun thumbnail(item: MediaItem): ByteArray? =
        files().firstOrNull { it.name == item.id }?.let(thumbnailer)
}
