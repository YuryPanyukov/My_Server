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

/** Итог удаления элемента галереи. */
enum class MediaDeleteResult {
    /** Элемент удалён (или перемещён в корзину). */
    DELETED,

    /** Элемента уже нет. */
    NOT_FOUND,

    /** Источник только для чтения. */
    NOT_SUPPORTED,

    /** Android требует подтверждения удаления на телефоне. */
    NEEDS_CONSENT,

    /** Удалить не удалось. */
    FAILED,
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

    /** Удаление элемента. По умолчанию источник только для чтения. */
    fun delete(item: MediaItem): MediaDeleteResult = MediaDeleteResult.NOT_SUPPORTED
}

/** Принятые приложением файлы, показанные как медиа. */
class ReceivedMediaSource(
    private val files: () -> List<File>,
    private val thumbnailer: (File) -> ByteArray? = { null },
    private val root: () -> File? = { null },
) : MediaSource {

    override val key: String = "received"
    override val title: String = "Принятые"

    override fun items(): List<MediaItem> = files().map { file ->
        val mime = MimeTypes.forFileName(file.name)
        MediaItem(
            // В разных папках бывают одинаковые имена: id — путь от корня.
            id = idOf(file),
            name = file.name,
            size = file.length(),
            dateMillis = file.lastModified(),
            mimeType = mime,
            kind = MediaItem.kindOf(mime),
        )
    }

    override fun open(item: MediaItem): InputStream? =
        files().firstOrNull { idOf(it) == item.id }?.inputStream()

    override fun thumbnail(item: MediaItem): ByteArray? =
        files().firstOrNull { idOf(it) == item.id }?.let(thumbnailer)

    /** Принятые файлы удаляются в корзину — их можно вернуть со страницы /trash. */
    override fun delete(item: MediaItem): MediaDeleteResult {
        val file = files().firstOrNull { idOf(it) == item.id } ?: return MediaDeleteResult.NOT_FOUND
        val storage = root()
        if (storage != null) {
            val relative = StoragePaths.relativePath(storage, file) ?: return MediaDeleteResult.NOT_SUPPORTED
            return if (ReceivedStorage { storage }.trash(relative) != null) {
                MediaDeleteResult.DELETED
            } else {
                MediaDeleteResult.FAILED
            }
        }
        return if (file.deleteRecursively()) MediaDeleteResult.DELETED else MediaDeleteResult.FAILED
    }

    private fun idOf(file: File): String {
        val storage = root() ?: return file.name
        return StoragePaths.relativePath(storage, file) ?: file.name
    }
}
