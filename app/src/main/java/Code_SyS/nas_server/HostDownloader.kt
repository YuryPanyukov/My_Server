package Code_SyS.nas_server

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает файл с телефона-хоста в системную папку «Загрузки».
 *
 * На Android 10+ запись идёт через [MediaStore], разрешений не нужно.
 * На более старых версиях файл кладётся прямо в `Downloads`, и экран
 * спрашивает `WRITE_EXTERNAL_STORAGE` ([needsPermission], [hasPermission]).
 */
object HostDownloader {

    internal const val BUFFER_SIZE = 64 * 1024

    /** Нужно ли разрешение на запись: только до Android 10. */
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasPermission(context: Context): Boolean = !needsPermission() ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Сохраняет [url] под именем [name]. Возвращает ссылку на сохранённый файл
     * или `null`, если сервер не ответил, связь оборвалась или файл короткий.
     * [onProgress] получает долю от 0 до 1 (или -1 для ответа без `Content-Length`).
     */
    suspend fun save(
        context: Context,
        url: String,
        name: String,
        onProgress: (Float) -> Unit = {},
    ): Uri? = withContext(Dispatchers.IO) {
        val fileName = DownloadNames.sanitize(name)
        if (fileName.isEmpty()) return@withContext null

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val total = connection.contentLengthLong
            val mime = connection.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: MimeTypes.forFileName(fileName)
            connection.inputStream.use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStore(context, fileName, mime, input, total, onProgress)
                } else {
                    saveToDownloadsFolder(context, fileName, input, total, onProgress)
                }
            }
        } catch (_: Exception) {
            // Сеть, права, битый ответ — для экрана это просто «не сохранилось».
            null
        } finally {
            connection.disconnect()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(
        context: Context,
        fileName: String,
        mime: String,
        input: InputStream,
        total: Long,
        onProgress: (Float) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Пока файл не докачан, его не видно в «Загрузках» и в других приложениях.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        var complete = false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                complete = copyStream(input, out, total, onProgress)
            }
            if (!complete) return null
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } finally {
            // Недокачанный файл не оставляем мусором в «Загрузках».
            if (!complete) resolver.delete(uri, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToDownloadsFolder(
        context: Context,
        fileName: String,
        input: InputStream,
        total: Long,
        onProgress: (Float) -> Unit,
    ): Uri? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return null

        val existing = dir.list()?.toSet().orEmpty()
        val file = File(dir, DownloadNames.unique(fileName, existing))
        var complete = false
        try {
            file.outputStream().use { out ->
                complete = copyStream(input, out, total, onProgress)
            }
            if (!complete) return null
        } finally {
            if (!complete) file.delete()
        }

        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return Uri.fromFile(file)
    }

}

/**
 * Переливает ответ сервера в приёмник, сообщая долю готовности.
 *
 * Возвращает `false`, если сервер обещал больше байт, чем прислал: обрезанный
 * файл сохранять нельзя. Проверяется отдельно от Android, чтобы покрыть тестами.
 */
internal fun copyStream(
    input: InputStream,
    out: OutputStream,
    total: Long,
    onProgress: (Float) -> Unit,
): Boolean {
    val buffer = ByteArray(HostDownloader.BUFFER_SIZE)
    var copied = 0L
    var reportedPercent = -1

    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        copied += read

        val fraction = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f
        val percent = (fraction * 100).toInt()
        if (percent != reportedPercent) {
            reportedPercent = percent
            onProgress(fraction)
        }
    }
    out.flush()

    return total <= 0 || copied == total
}

/**
 * Имена для «Загрузок»: без путей и символов, которые запрещены файловой
 * системой, с нумерацией при совпадении — как у принятых файлов в LanDrop.
 */
internal object DownloadNames {

    private val FORBIDDEN = setOf(':', '*', '?', '"', '<', '>', '|')

    fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = buildString(capacity = base.length) {
            for (char in base) {
                if (char.code < 32 || char in FORBIDDEN) continue
                append(char)
            }
        }.trim()

        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "" else cleaned
    }

    /** `name`, `name (1)`, `name (2)`… — первое имя, которого нет в [taken]. */
    fun unique(name: String, taken: Set<String>): String =
        FileNames.numbered(name).first { it !in taken }
}
