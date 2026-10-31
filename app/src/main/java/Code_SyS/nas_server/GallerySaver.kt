package Code_SyS.nas_server

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Копия принятых фото и видео в системную Галерею.
 *
 * Сама папка приёма остаётся приватной и неизменной: копия нужна только для
 * того, чтобы снимки с компьютера сразу появлялись в галерее телефона.
 * Копирование выполняется через MediaStore и не требует разрешений на
 * Android 10+.
 */
object GallerySaver {

    private const val TAG = "GallerySaver"
    private const val PREFS = "landrop_settings"
    private const val KEY_ENABLED = "copy_to_gallery"
    private const val IMAGE_FOLDER = "Pictures/LanDrop"
    private const val VIDEO_FOLDER = "Movies/LanDrop"

    /** До Android 10 публичная папка требует WRITE_EXTERNAL_STORAGE, поэтому копируем только на 29+. */
    fun canPublish(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isEnabled(context: Context): Boolean =
        canPublish() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** Копирует фото/видео в Галерею. `null` — тип не медиа, копия не нужна или не удалась. */
    fun publish(context: Context, file: File): Uri? {
        if (!canPublish() || !file.isFile) return null

        val mime = MimeTypes.forFileName(file.name)
        val isImage = MimeTypes.isImage(mime)
        val isVideo = MimeTypes.isVideo(mime)
        if (!isImage && !isVideo) return null

        val resolver = context.contentResolver
        val collection = if (isImage) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = if (isImage) IMAGE_FOLDER else VIDEO_FOLDER

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось создать запись в Галерее", e)
            null
        } ?: return null

        return try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("Нет потока записи")
            output.use { target ->
                file.inputStream().use { source -> source.copyTo(target) }
            }

            // IS_PENDING=0 делает файл видимым для Галереи.
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось скопировать ${file.name} в Галерею", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}

/** Текущее состояние настройки и копирование для фоновых вызовов. */
internal fun publishToGalleryIfEnabled(context: Context, file: File) {
    if (GallerySaver.isEnabled(context)) {
        GallerySaver.publish(context, file)
    }
}

