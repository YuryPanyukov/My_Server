package Code_SyS.nas_server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Превью для галереи: маленькие JPEG-кадры фото и видео.
 *
 * Полноразмерные снимки с телефона весят мегабайты, поэтому сетка галереи
 * грузит только такие превью, а оригинал открывается по нажатию.
 */
object Thumbnails {

    private const val MAX_SIZE = 320
    private const val JPEG_QUALITY = 80

    /** Превью принятого файла; `null` — тип не медиа или файл битый. */
    fun fromFile(file: File, mimeType: String): ByteArray? = runCatching {
        when {
            MimeTypes.isImage(mimeType) -> imageFromFile(file)
            MimeTypes.isVideo(mimeType) -> videoFromFile(file)
            else -> null
        }.let { bitmap -> compress(bitmap) }
    }.getOrNull()

    /** Превью фото/видео из медиатеки по `content://` адресу. */
    fun fromUri(context: Context, uri: Uri, mimeType: String, mediaId: Long): ByteArray? = runCatching {
        when {
            MimeTypes.isImage(mimeType) -> imageThumbnail(context, uri, mediaId)
            MimeTypes.isVideo(mimeType) -> videoThumbnail(context, uri, mediaId)
            else -> null
        }.let { bitmap -> compress(bitmap) }
    }.getOrNull()

    private fun imageThumbnail(context: Context, uri: Uri, mediaId: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(MAX_SIZE, MAX_SIZE), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                mediaId,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }

    private fun videoThumbnail(context: Context, uri: Uri, mediaId: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(MAX_SIZE, MAX_SIZE), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                mediaId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null,
            )
        }

    private fun imageFromFile(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ThumbnailUtils.createImageThumbnail(file, Size(MAX_SIZE, MAX_SIZE), null)
        }
        return decodeDownsampled(file)
    }

    private fun videoFromFile(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ThumbnailUtils.createVideoThumbnail(file, Size(MAX_SIZE, MAX_SIZE), null)
        }
        @Suppress("DEPRECATION")
        return ThumbnailUtils.createVideoThumbnail(
            file.absolutePath,
            MediaStore.Video.Thumbnails.MINI_KIND,
        )
    }

    /** Декодирует картинку с уменьшением в 2^n раз, чтобы не держать полный кадр в памяти. */
    private fun decodeDownsampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE && bounds.outHeight / (sample * 2) >= MAX_SIZE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun compress(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        return try {
            val out = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (ok && out.size() > 0) out.toByteArray() else null
        } finally {
            bitmap.recycle()
        }
    }
}
