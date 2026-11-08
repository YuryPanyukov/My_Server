package Code_SyS.nas_server

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Настройка долгого хранения: WebDAV-папка в локальной сети (компьютер, NAS,
 * роутер с USB-диском). Пароль лежит в приватных `SharedPreferences`
 * приложения и не покидает телефон, кроме запросов к самому хранилищу.
 */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    /** URL папки на сервере, всегда с завершающим `/`. */
    fun normalizedUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    /** Заполнен ли адрес: без него выгрузка невозможна. */
    fun isComplete(): Boolean {
        val url = normalizedUrl()
        return url.startsWith("http://") || url.startsWith("https://")
    }

    companion object {
        val EMPTY = WebDavConfig(baseUrl = "", username = "", password = "")
    }
}

/** Ошибка HTTP-ответа хранилища: код нужен UI для подсказки (401, 404…). */
class WebDavHttpException(val code: Int, val url: String) :
    IOException("WebDAV $code: $url")

/**
 * Минимальный WebDAV-клиент: `PROPFIND` для проверки папки и `PUT` для
 * выгрузки. Класс не зависит от Android API и проверяется JVM-тестами.
 */
class WebDavClient(private val config: WebDavConfig) {

    /**
     * Проверка подключения: запрос `PROPFIND Depth: 0` к папке.
     * Успех — любой 2xx-ответ; 404 значит, что папки на сервере нет.
     */
    fun testConnection(): Result<Unit> {
        val connection = try {
            open("PROPFIND", path = "")
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            connection.connect()
            val status = connection.responseCode
            if (status in 200..299) {
                Result.success(Unit)
            } else {
                readAndClose(connection.errorStream)
                Result.failure(WebDavHttpException(status, connection.url.toString()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Выгружает файл под именем [fileName] через `PUT`.
     *
     * Поток открывается лениво через [openStream]: очередь выгрузки может
     * сутки подождать свободной Wi-Fi сети, и держать открытый `InputStream`
     * всё это время нельзя. [progress] вызывается из потока выгрузки после
     * каждой записанной порции.
     */
    fun upload(
        fileName: String,
        size: Long,
        openStream: () -> InputStream?,
        progress: (Long) -> Unit = {},
    ): Result<Unit> {
        val input = try {
            openStream() ?: return Result.failure(IOException("Источник файла недоступен"))
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return input.use { stream ->
            val connection = try {
                open("PUT", path = encodeSegment(fileName))
            } catch (e: Exception) {
                return Result.failure(e)
            }
            var result: Result<Unit> = Result.success(Unit)
            try {
                connection.doOutput = true
                if (size >= 0) connection.setFixedLengthStreamingMode(size)
                connection.connect()
                copyWithProgress(stream, connection.outputStream, progress)
                connection.outputStream.flush()

                val status = connection.responseCode
                if (status !in 200..299) {
                    readAndClose(connection.errorStream)
                    result = Result.failure(WebDavHttpException(status, connection.url.toString()))
                }
            } catch (e: Exception) {
                result = Result.failure(e)
            } finally {
                connection.disconnect()
            }
            result
        }
    }

    fun uploadFile(file: File, progress: (Long) -> Unit = {}): Result<Unit> =
        upload(file.name, file.length(), { FileOpeners.readFile(file) }, progress)

    private fun open(method: String, path: String): HttpURLConnection {
        val url = URL(buildTarget(path))
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "LanDrop/1.0")
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            connection.setRequestProperty("Authorization", basicAuth(config.username, config.password))
        }
        return connection
    }

    /**
     * Заголовок Basic-авторизации. `java.util.Base64` появился только в API 26,
     * а приложение поддерживает API 24, поэтому кодируем сами (RFC 4648).
     */
    private fun basicAuth(user: String, password: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = "$user:$password".toByteArray(StandardCharsets.UTF_8)
        val encoded = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            encoded.append(alphabet[triple ushr 18 and 0x3F])
            encoded.append(alphabet[triple ushr 12 and 0x3F])
            encoded.append(if (i + 1 < bytes.size) alphabet[triple ushr 6 and 0x3F] else '=')
            encoded.append(if (i + 2 < bytes.size) alphabet[triple and 0x3F] else '=')
            i += 3
        }
        return "Basic $encoded"
    }

    internal fun buildTarget(path: String): String {
        val base = config.normalizedUrl()
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Адрес хранилища должен начинаться с http:// или https://"
        }
        return base + path
    }

    /** Побайтовый счётчик для прогресса выгрузки. */
    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        progress: (Long) -> Unit,
    ) {
        var sent = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            sent += read
            progress(sent)
        }
    }

    private fun readAndClose(stream: InputStream?) {
        stream?.use { it.readBytes() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024

        /** Процентное кодирование одного сегмента имени файла для URL. */
        fun encodeSegment(value: String): String {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val builder = StringBuilder(bytes.size * 3)
            for (byte in bytes) {
                val code = byte.toInt() and 0xFF
                val safe = code in 'A'.code..'Z'.code ||
                    code in 'a'.code..'z'.code ||
                    code in '0'.code..'9'.code ||
                    code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
                if (safe) builder.append(code.toChar()) else builder.append(
                    String.format(Locale.US, "%%%02X", code)
                )
            }
            return builder.toString()
        }
    }
}

/** Отделено от клиента, чтобы тесты могли заменить чтение файла. */
internal object FileOpeners {
    fun readFile(file: File): InputStream? = try {
        file.inputStream()
    } catch (_: Exception) {
        null
    }
}
