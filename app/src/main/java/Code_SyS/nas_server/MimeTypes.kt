package Code_SyS.nas_server

import java.util.Locale

/**
 * MIME-типы по расширению файла: используются и HTTP-сервером для заголовка
 * `Content-Type`, и приложением при открытии файла через `Intent`.
 */
object MimeTypes {

    fun forFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (extension) {
            "txt", "log", "md", "json", "xml", "csv", "ini", "conf" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "avif" -> "image/avif"
            "pdf" -> "application/pdf"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "ts", "m2ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /** Одна проверка «это медиа» для превью, Галереи и отправки. */
    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/")

    fun isAudio(mimeType: String): Boolean = mimeType.startsWith("audio/")

    /** Текстовые файлы отдаются с кодировкой, чтобы браузер не угадывал её сам. */
    fun forHttp(name: String): String {
        val base = forFileName(name)
        return if (base == "text/plain") "text/plain; charset=utf-8" else base
    }
}
