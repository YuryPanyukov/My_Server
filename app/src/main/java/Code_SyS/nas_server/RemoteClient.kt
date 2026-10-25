package Code_SyS.nas_server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Файл, принятый телефоном-хостом. */
data class RemoteFile(val name: String, val size: Long, val modified: Long)

/** Элемент галереи на телефоне-хосте. */
data class RemoteMediaItem(
    val id: String,
    val name: String,
    val size: Long,
    val date: Long,
    val mime: String,
    val kind: String,
    /** Путь на хосте, например `/media?src=...&id=...`. */
    val url: String,
    val thumb: String,
)

/** Источник галереи на хосте (принятые файлы, медиатека телефона). */
data class RemoteGallery(val key: String, val title: String, val items: List<RemoteMediaItem>)

/**
 * Клиент к HTTP API LanDrop на другом телефоне.
 *
 * Использует те же маршруты, что и браузерная страница: `/api/files`,
 * `/api/media`, `/thumb-file`, `/thumb`, `/media`, `/upload`, `/files/<имя>`.
 * Поэтому второе устройство видит ровно то же, что и браузер, — без отдельного
 * серверного протокола.
 */
class RemoteClient(val host: String, val port: Int) {

    val baseUrl: String = "http://$host:$port"

    /** Ссылка на веб-версию — открывается во внешнем браузере. */
    fun browserUrl(path: String = "/"): String = baseUrl + path

    fun filePath(name: String): String = "/files/" + encodeSegment(name)

    fun fileThumbPath(name: String): String = "/thumb-file?name=" + encodeSegment(name)

    fun files(): List<RemoteFile> {
        val json = getJson("/api/files")
        val array = json.optJSONArray("files") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isEmpty()) continue
                add(
                    RemoteFile(
                        name = name,
                        size = item.optLong("size"),
                        modified = item.optLong("modified"),
                    )
                )
            }
        }
    }

    fun galleries(): List<RemoteGallery> {
        val json = getJson("/api/media")
        val array = json.optJSONArray("sources") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val source = array.optJSONObject(index) ?: continue
                val key = source.optString("key")
                val itemsArray = source.optJSONArray("items") ?: continue
                val items = buildList {
                    for (itemIndex in 0 until itemsArray.length()) {
                        val item = itemsArray.optJSONObject(itemIndex) ?: continue
                        val id = item.optString("id")
                        if (id.isEmpty()) continue
                        add(
                            RemoteMediaItem(
                                id = id,
                                name = item.optString("name", id),
                                size = item.optLong("size"),
                                date = item.optLong("date"),
                                mime = item.optString("mime"),
                                kind = item.optString("kind"),
                                url = item.optString("url"),
                                thumb = item.optString("thumb"),
                            )
                        )
                    }
                }
                add(RemoteGallery(key = key, title = source.optString("title", key), items = items))
            }
        }
    }

    /** Удаляет принятый файл на хосте. */
    fun deleteFile(name: String) {
        request("/files/" + encodeSegment(name), "DELETE").withConnection { connection ->
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Сервер ответил $code")
        }
    }

    /**
     * Отправляет файл на хост: `PUT /upload?name=<имя>`.
     * Сервер требует `Content-Length`, поэтому размер должен быть известен.
     */
    fun upload(
        name: String,
        size: Long,
        openStream: () -> InputStream?,
        onProgress: (sent: Long) -> Unit = {},
    ) {
        require(size >= 0) { "Неизвестный размер файла" }
        val connection = openConnection("/upload?name=" + encodeSegment(name), "PUT")
        try {
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(size)
            connection.doOutput = true

            val input = openStream() ?: throw IOException("Не удалось прочитать файл")
            input.use { source ->
                connection.outputStream.use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                        sent += read
                        if (sent - lastReported >= PROGRESS_STEP || sent == size) {
                            lastReported = sent
                            onProgress(sent)
                        }
                    }
                    if (sent != size) {
                        throw IOException("Файл изменился во время отправки")
                    }
                }
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Сервер ответил $code")
            }
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    /** Превью/оригинал картинки с хоста. `null` — не картинка или сервер недоступен. */
    fun image(path: String, maxSize: Int = 512): Bitmap? {
        val cacheKey = "$baseUrl|$maxSize|$path"
        imageCache.get(cacheKey)?.let { return it }

        val bytes = try {
            request(path, "GET").withConnection { connection ->
                val code = connection.responseCode
                if (code !in 200..299) return null
                connection.inputStream.use { it.readBytes() }
            }
        } catch (_: Exception) {
            return null
        }

        val bitmap = decodeSampled(bytes, maxSize) ?: return null
        imageCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun getJson(path: String): JSONObject {
        val text = request(path, "GET").withConnection { connection ->
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Сервер ответил $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return JSONObject(text)
    }

    private fun request(path: String, method: String): HttpURLConnection =
        openConnection(path, method).apply {
            try {
                connect()
            } catch (e: IOException) {
                disconnect()
                throw e
            }
        }

    /** `HttpURLConnection` не `Closeable`: соединение закрываем через `disconnect`.
     *  Ошибки чтения не должны терять соединение, поэтому закрытие в `finally`. */
    private inline fun <T> HttpURLConnection.withConnection(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("Accept", "*/*")
        return connection
    }

    /** Как `encodeURIComponent`, но пробел — `%20`, а не `+` (сервер читает `+` буквально). */
    private fun encodeSegment(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val safe = (code in 'A'.code..'Z'.code) ||
                (code in 'a'.code..'z'.code) ||
                (code in '0'.code..'9'.code) ||
                code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
            if (safe) {
                builder.append(code.toChar())
            } else {
                builder.append('%').append(String.format(Locale.US, "%02X", code))
            }
        }
        return builder.toString()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 30_000
        const val PROGRESS_STEP = 256 * 1024L

        /** Общий кеш превью между экранами: возврат из просмотра не перекачивает сетку. */
        val imageCache: LruCache<String, Bitmap> =
            object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
            }
    }
}
