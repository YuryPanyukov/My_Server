package Code_SyS.nas_server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Минимальный HTTP/1.1-обработчик, работающий на том же порту, что и бинарный
 * протокол [Protocol]. Запросы различаются по первым байтам в
 * [FileReceiverServer], поэтому отдельный порт не нужен.
 *
 * Маршруты:
 *   `GET /`             — страница загрузки и список принятых файлов;
 *   `GET /health`       — проверка доступности, отвечает `OK`;
 *   `GET /open`         — страница-мостик из QR: приложение или веб-интерфейс;
 *   `GET /api/files`    — список принятых файлов в JSON;
 *   `GET /files/<имя>`  — содержимое файла (превью для картинок, видео, PDF и текста);
 *   `DELETE /files/<имя>` — удаление файла;
 *   `GET /gallery`      — галерея фото и видео (принятые файлы + медиатека телефона);
 *   `GET /view?src=<источник>&id=<элемент>` — просмотр с листанием стрелками и свайпами;
 *   `GET /api/media`    — элементы галереи в JSON;
 *   `GET /media?src=<источник>&id=<элемент>` — оригинал фото/видео с поддержкой `Range`;
 *   `GET /thumb?src=<источник>&id=<элемент>` — JPEG-превью для сетки галереи;
 *   `GET /thumb-file?name=<имя>` — маленькое превью принятого файла для списка;
 *   `POST /upload?name=<имя>` — тело запроса целиком сохраняется в файл;
 *   `PUT /upload?name=<имя>`  — то же самое, совместимо с `curl --upload-file`.
 *
 * Класс не зависит от Android API и проверяется JVM-тестами.
 *
 * @param saver запись файла; тот же колбэк, что и у бинарного протокола
 * @param files список доступных для просмотра файлов (обычно папка приёма)
 * @param mediaSources источники для галереи (принятые файлы, медиатека телефона)
 * @param fileThumbnailer превью принятого файла для списка (`null` — тип без превью)
 * @param logger приёмник диагностических сообщений
 */
class HttpRequestHandler(
    private val saver: (fileName: String, fileSize: Long, input: InputStream) -> File?,
    private val files: () -> List<File> = { emptyList() },
    private val mediaSources: () -> List<MediaSource> = { emptyList() },
    private val fileThumbnailer: (File) -> ByteArray? = { null },
    private val logger: (String) -> Unit = {},
) {

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    fun handle(input: InputStream, output: OutputStream) {
        val buffered = BufferedInputStream(input, Protocol.BUFFER_SIZE)

        val request = try {
            readRequest(buffered)
        } catch (e: Exception) {
            logger("HTTP: ошибка чтения запроса: ${e.message}")
            writeResponse(output, 400, "Bad Request", CONTENT_TYPE_TEXT, "Bad request".toByteArray())
            return
        }
        if (request == null) return

        try {
            route(request, buffered, output)
        } catch (e: Exception) {
            logger("HTTP: ошибка обработки ${request.method} ${request.path}: ${e.message}")
            writeResponse(output, 500, "Internal Server Error", CONTENT_TYPE_TEXT, "Internal error".toByteArray())
        }
    }

    private fun route(request: Request, body: InputStream, output: OutputStream) {
        when (request.method) {
            "GET", "HEAD" -> {
                val headOnly = request.method == "HEAD"
                when {
                    request.path == "/" || request.path == "/index.html" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_HTML, renderPage(files()), includeBody = !headOnly
                    )

                    request.path == "/health" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_TEXT, "OK".toByteArray(), includeBody = !headOnly
                    )

                    request.path == OPEN_PATH -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_HTML,
                        renderOpenPage(request.headers["host"]), includeBody = !headOnly
                    )

                    request.path == "/api/files" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_JSON,
                        filesJson(files()).toByteArray(), includeBody = !headOnly
                    )

                    request.path.startsWith(FILES_PATH_PREFIX) -> serveStoredFile(
                        request.path.removePrefix(FILES_PATH_PREFIX), request.headers["range"], output, headOnly
                    )

                    request.path == "/gallery" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_HTML,
                        renderGallery(mediaSources(), request.query["src"]), includeBody = !headOnly
                    )

                    request.path == "/api/media" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_JSON,
                        mediaJson(mediaSources(), request.query["src"]).toByteArray(), includeBody = !headOnly
                    )

                    request.path == "/view" -> {
                        val page = renderViewer(mediaSources(), request.query["src"], request.query["id"])
                        if (page == null) {
                            notFound(output)
                        } else {
                            writeResponse(output, 200, "OK", CONTENT_TYPE_HTML, page, includeBody = !headOnly)
                        }
                    }

                    request.path == "/media" -> serveMedia(
                        request.query, request.headers["range"], output, headOnly
                    )

                    request.path == "/thumb" -> serveThumb(
                        request.query, output, headOnly
                    )

                    request.path == "/thumb-file" -> serveFileThumb(
                        request.query, output, headOnly
                    )

                    else -> notFound(output)
                }
            }

            "POST", "PUT" -> if (request.path == "/upload") {
                upload(request, body, output)
            } else {
                notFound(output)
            }

            "DELETE" -> if (request.path.startsWith(FILES_PATH_PREFIX)) {
                deleteStoredFile(request.path.removePrefix(FILES_PATH_PREFIX), output)
            } else {
                notFound(output)
            }

            "OPTIONS" -> writeResponse(
                output, 204, "No Content", null, ByteArray(0),
                extraHeaders = listOf("Allow" to ALLOWED_METHODS)
            )

            else -> writeResponse(
                output, 405, "Method Not Allowed", CONTENT_TYPE_TEXT, "Method not allowed".toByteArray(),
                extraHeaders = listOf("Allow" to ALLOWED_METHODS)
            )
        }
    }

    private fun notFound(output: OutputStream) {
        writeResponse(output, 404, "Not Found", CONTENT_TYPE_TEXT, "Not found".toByteArray())
    }

    private fun upload(request: Request, body: InputStream, output: OutputStream) {
        val fileName = request.query["name"]?.trim().orEmpty()
        if (fileName.isEmpty()) {
            writeResponse(
                output, 400, "Bad Request", CONTENT_TYPE_JSON,
                errorJson("Не указано имя файла: добавьте параметр name в URL").toByteArray()
            )
            return
        }

        val contentLength = request.headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            writeResponse(
                output, 411, "Length Required", CONTENT_TYPE_JSON,
                errorJson("Требуется заголовок Content-Length").toByteArray()
            )
            return
        }

        val saved = try {
            saver(fileName, contentLength, body)
        } catch (e: Exception) {
            logger("HTTP: ошибка сохранения $fileName: ${e.message}")
            null
        }

        if (saved == null) {
            writeResponse(
                output, 500, "Internal Server Error", CONTENT_TYPE_JSON,
                errorJson("Не удалось сохранить файл").toByteArray()
            )
            return
        }

        logger("HTTP: сохранён ${saved.name} (${saved.length()} байт)")
        val json = "{\"ok\":true,\"name\":\"${jsonEscape(saved.name)}\",\"size\":${saved.length()}}"
        writeResponse(output, 200, "OK", CONTENT_TYPE_JSON, json.toByteArray())
    }

    /**
     * Отдаёт файл из списка [files]. Имена сравниваются с реальными файлами
     * хранилища, поэтому обойти путь (`../`) через URL нельзя.
     *
     * Поддерживает частичные ответы (`Range`): без них браузер не может
     * перематывать видео и хуже проигрывает большие файлы.
     */
    private fun serveStoredFile(
        name: String,
        rangeHeader: String?,
        output: OutputStream,
        headOnly: Boolean,
    ) {
        val file = files().firstOrNull { it.isFile && it.name == name }
        if (file == null) {
            notFound(output)
            return
        }

        val total = file.length()
        val contentType = MimeTypes.forHttp(file.name)
        val disposition = if (isInlinePreview(contentType)) "inline" else "attachment"

        when (val range = parseRange(rangeHeader, total)) {
            is RangeResult.Invalid -> writeRangeNotSatisfiable(output, total)

            is RangeResult.Slice -> writeFile(
                output = output,
                file = file,
                start = range.start,
                end = range.end,
                headers = contentHeaders(
                    statusLine = "206 Partial Content",
                    fileName = file.name,
                    contentType = contentType,
                    disposition = disposition,
                    contentLength = range.end - range.start + 1,
                    contentRange = "bytes ${range.start}-${range.end}/$total",
                ),
                headOnly = headOnly,
            )

            null -> writeFile(
                output = output,
                file = file,
                start = 0,
                end = total - 1,
                headers = contentHeaders(
                    statusLine = "200 OK",
                    fileName = file.name,
                    contentType = contentType,
                    disposition = disposition,
                    contentLength = total,
                    contentRange = null,
                ),
                headOnly = headOnly,
            )
        }
    }

    private fun contentHeaders(
        statusLine: String,
        fileName: String,
        contentType: String,
        disposition: String,
        contentLength: Long,
        contentRange: String?,
        cacheControl: String = "no-store",
    ): String = buildString {
        append("HTTP/1.1 ").append(statusLine).append("\r\n")
        append("Connection: close\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("Content-Length: ").append(contentLength).append("\r\n")
        if (contentRange != null) append("Content-Range: ").append(contentRange).append("\r\n")
        append("Content-Disposition: ").append(disposition)
            .append("; filename=\"").append(asciiFileName(fileName)).append('"')
            .append("; filename*=UTF-8''").append(encodePathSegment(fileName)).append("\r\n")
        append("X-Content-Type-Options: nosniff\r\n")
        append("Accept-Ranges: bytes\r\n")
        append("Cache-Control: ").append(cacheControl).append("\r\n")
        append("\r\n")
    }

    /** Удаляет файл из хранилища; удаляются только файлы из списка [files]. */
    private fun deleteStoredFile(name: String, output: OutputStream) {
        val file = files().firstOrNull { it.isFile && it.name == name }
        if (file == null) {
            notFound(output)
            return
        }

        val deleted = try {
            file.delete()
        } catch (e: Exception) {
            logger("HTTP: ошибка удаления ${file.name}: ${e.message}")
            false
        }

        if (!deleted) {
            writeResponse(
                output, 500, "Internal Server Error", CONTENT_TYPE_JSON,
                errorJson("Не удалось удалить файл").toByteArray()
            )
            return
        }

        logger("HTTP: удалён ${file.name}")
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            "{\"ok\":true,\"name\":\"${jsonEscape(file.name)}\"}".toByteArray()
        )
    }

    // --- Галерея фото и видео ------------------------------------------------

    /**
     * Отдаёт элемент галереи (фото, видео или любой принятый файл).
     * Размер известен из [MediaItem.size], поэтому работают частичные ответы
     * (`Range`) — без них браузер не может перематывать видео.
     */
    private fun serveMedia(
        query: Map<String, String>,
        rangeHeader: String?,
        output: OutputStream,
        headOnly: Boolean,
    ) {
        val source = mediaSources().firstOrNull { it.key == query["src"] }
        val id = query["id"]
        if (source == null || id == null) {
            notFound(output)
            return
        }
        val item = source.items().firstOrNull { it.id == id }
        if (item == null) {
            notFound(output)
            return
        }

        val stream = try {
            source.open(item)
        } catch (e: Exception) {
            logger("HTTP: не удалось открыть ${item.name}: ${e.message}")
            null
        }
        if (stream == null) {
            notFound(output)
            return
        }

        stream.use { serveStream(it, item, rangeHeader, output, headOnly) }
    }

    private fun serveStream(
        input: InputStream,
        item: MediaItem,
        rangeHeader: String?,
        output: OutputStream,
        headOnly: Boolean,
    ) {
        val contentType = item.mimeType.ifEmpty { "application/octet-stream" }
        val disposition = if (isInlinePreview(contentType)) "inline" else "attachment"
        val total = item.size

        // Размер неизвестен — отдаём поток до закрытия соединения.
        if (total <= 0) {
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Connection: close\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Disposition: ").append(disposition)
                    .append("; filename=\"").append(asciiFileName(item.name)).append('"')
                    .append("; filename*=UTF-8''").append(encodePathSegment(item.name)).append("\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Cache-Control: no-store\r\n")
                append("\r\n")
            }
            try {
                output.write(headers.toByteArray(StandardCharsets.UTF_8))
                // Размер неизвестен, но видео нельзя читать целиком в память.
                if (!headOnly) copyExactly(input, output, Long.MAX_VALUE)
                output.flush()
            } catch (e: IOException) {
                logger("HTTP: не удалось отдать ${item.name}: ${e.message}")
            }
            return
        }

        when (val range = parseRange(rangeHeader, total)) {
            is RangeResult.Invalid -> writeRangeNotSatisfiable(output, total)

            is RangeResult.Slice -> writeStreamRange(
                output = output,
                input = input,
                start = range.start,
                end = range.end,
                headers = contentHeaders(
                    statusLine = "206 Partial Content",
                    fileName = item.name,
                    contentType = contentType,
                    disposition = disposition,
                    contentLength = range.end - range.start + 1,
                    contentRange = "bytes ${range.start}-${range.end}/$total",
                ),
                headOnly = headOnly,
            )

            null -> writeStreamRange(
                output = output,
                input = input,
                start = 0,
                end = total - 1,
                headers = contentHeaders(
                    statusLine = "200 OK",
                    fileName = item.name,
                    contentType = contentType,
                    disposition = disposition,
                    contentLength = total,
                    contentRange = null,
                ),
                headOnly = headOnly,
            )
        }
    }

    /**
     * JPEG-превью для сетки галереи. Если источник не умеет строить превью
     * (например, PDF или текстовый файл), отдаётся сам файл, а на странице
     * вместо картинки рисуется заглушка.
     */
    private fun serveThumb(query: Map<String, String>, output: OutputStream, headOnly: Boolean) {
        val source = mediaSources().firstOrNull { it.key == query["src"] }
        val id = query["id"]
        if (source == null || id == null) {
            notFound(output)
            return
        }
        val item = source.items().firstOrNull { it.id == id }
        if (item == null) {
            notFound(output)
            return
        }

        val thumbnail = try {
            source.thumbnail(item)
        } catch (e: Exception) {
            logger("HTTP: не удалось построить превью ${item.name}: ${e.message}")
            null
        }

        if (thumbnail == null) {
            serveMedia(query, null, output, headOnly)
            return
        }

        writeResponse(
            output, 200, "OK", "image/jpeg", thumbnail, includeBody = !headOnly
        )
    }

    /**
     * Превью принятого файла для списка на главной странице. Без него браузер
     * тянул бы оригинал на несколько мегабайт ради квадратика 42×42.
     *
     * Если превью построить не удалось, отдаётся сам файл: страница скроет
     * такую картинку и покажет значок типа.
     */
    private fun serveFileThumb(
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
    ) {
        val name = query["name"]
        val file = if (name == null) null else files().firstOrNull { it.isFile && it.name == name }
        if (file == null) {
            notFound(output)
            return
        }

        val thumbnail = try {
            fileThumbnailer(file)
        } catch (e: Exception) {
            logger("HTTP: не удалось построить превью ${file.name}: ${e.message}")
            null
        }

        if (thumbnail == null) {
            serveStoredFile(file.name, null, output, headOnly)
            return
        }

        // Ссылка содержит версию (`v` = время изменения), поэтому кеш безопасен.
        writeResponse(
            output, 200, "OK", "image/jpeg", thumbnail, includeBody = !headOnly,
            cacheControl = "private, max-age=300",
        )
    }

    private fun writeStreamRange(
        output: OutputStream,
        input: InputStream,
        start: Long,
        end: Long,
        headers: String,
        headOnly: Boolean,
    ) {
        try {
            output.write(headers.toByteArray(StandardCharsets.UTF_8))
            if (!headOnly) {
                skipFully(input, start)
                copyExactly(input, output, end - start + 1)
            }
            output.flush()
        } catch (e: IOException) {
            logger("HTTP: поток прерван: ${e.message}")
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(Protocol.BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeRangeNotSatisfiable(output: OutputStream, total: Long) {
        try {
            output.write(
                ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Connection: close\r\n" +
                    "Content-Range: bytes */$total\r\n" +
                    "Content-Length: 0\r\n" +
                    "Cache-Control: no-store\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            )
            output.flush()
        } catch (_: IOException) {
            // Клиент отключился.
        }
    }

    /** Пишет заголовки и байты файла в диапазоне `start..end` включительно. */
    private fun writeFile(
        output: OutputStream,
        file: File,
        start: Long,
        end: Long,
        headers: String,
        headOnly: Boolean,
    ) {
        try {
            output.write(headers.toByteArray(StandardCharsets.UTF_8))
            if (!headOnly) {
                file.inputStream().use { input ->
                    skipFully(input, start)
                    var remaining = end - start + 1
                    val buffer = ByteArray(Protocol.BUFFER_SIZE)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            output.flush()
        } catch (e: IOException) {
            logger("HTTP: не удалось отдать ${file.name}: ${e.message}")
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    /** Разбирает заголовок `Range`. `null` — диапазон не запрошен. */
    private fun parseRange(header: String?, total: Long): RangeResult? {
        if (header == null || header.isBlank()) return null
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return RangeResult.Invalid
        val spec = value.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Invalid

        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        if (startText.isEmpty()) {
            // Суффиксный диапазон: последние N байт.
            val suffix = endText.toLongOrNull() ?: return RangeResult.Invalid
            if (suffix <= 0 || total == 0L) return RangeResult.Invalid
            val start = if (suffix >= total) 0 else total - suffix
            return RangeResult.Slice(start, total - 1)
        }

        val start = startText.toLongOrNull() ?: return RangeResult.Invalid
        if (start < 0 || start >= total) return RangeResult.Invalid
        val end = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: return RangeResult.Invalid)
        if (end < start) return RangeResult.Invalid
        return RangeResult.Slice(start, minOf(end, total - 1))
    }

    private sealed interface RangeResult {
        data class Slice(val start: Long, val end: Long) : RangeResult
        data object Invalid : RangeResult
    }

    /**
     * Страница-мостик для QR. Адрес берётся из заголовка `Host`: сервер может
     * быть доступен по разным адресам, а клиенту нужен именно тот, по которому
     * он пришёл. Без заголовка ссылки ведут на корень сайта.
     */
    private fun renderOpenPage(authority: String?): ByteArray {
        val target = ShareLinks.parseAuthority(authority)
        val address = htmlEscape(authority?.takeIf { it.isNotBlank() } ?: target?.browserUrl ?: "LanDrop")
        val browser = htmlEscape(target?.browserUrl ?: "/")
        val deepLink = target?.deepLink.orEmpty()

        return WebTemplates.OPEN
            .replace("__ADDRESS__", address)
            .replace("__BROWSER__", browser)
            .replace("__DEEP_LINK_ATTR__", htmlEscape(deepLink))
            .replace("__DEEP_LINK_JS__", if (deepLink.isEmpty()) "null" else jsString(deepLink))
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun renderPage(entries: List<File>): ByteArray {
        val rows = if (entries.isEmpty()) {
            "<li class=\"empty\"><span class=\"empty-icon\">✦</span>" +
                "<span>Пока нет файлов</span>" +
                "<small>Перетащите первый файл в область выше</small></li>"
        } else {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            buildString {
                for (file in entries) {
                    val kind = fileKind(file)
                    append("<li class=\"file-item\" data-name=\"")
                        .append(htmlEscape(file.name.lowercase(Locale.ROOT)))
                        .append("\"><span class=\"file-thumb\" data-kind=\"").append(kind).append("\">")
                    // Превью запрашивается лениво и только для медиа: остальные
                    // типы показывают значок расширения.
                    if (kind == "image" || kind == "video") {
                        append("<img class=\"fthumb\" src=\"").append(THUMB_FILE_PATH)
                            .append("?name=").append(encodePathSegment(file.name))
                            .append("&amp;v=").append(file.lastModified())
                            .append("\" loading=\"lazy\" decoding=\"async\" alt=\"\">")
                        if (kind == "video") append("<span class=\"play\" aria-hidden=\"true\">▶</span>")
                    }
                    append("<span class=\"ftype\" data-kind=\"").append(kind)
                        .append("\">").append(htmlEscape(fileTypeLabel(file)))
                        .append("</span></span><span class=\"file-main\"><a class=\"file\" href=\"").append(FILES_PATH_PREFIX)
                        .append(encodePathSegment(file.name))
                        .append("\" target=\"_blank\" rel=\"noopener\">")
                        .append(htmlEscape(file.name))
                        .append("</a><span class=\"meta\">")
                        .append(formatSize(file.length()))
                        .append(" · ")
                        .append(dateFormat.format(Date(file.lastModified())))
                        .append("</span></span><button class=\"del\" type=\"button\" title=\"Удалить\" aria-label=\"Удалить\" data-href=\"")
                        .append(FILES_PATH_PREFIX).append(encodePathSegment(file.name))
                        .append("\" data-file=\"").append(htmlEscape(file.name))
                        .append("\">×</button></li>")
                }
            }
        }
        return WebTemplates.PAGE
            .replace(FILES_MARKER, rows)
            .replace(FILE_COUNT_MARKER, entries.size.toString())
            .toByteArray(StandardCharsets.UTF_8)
    }

    /** CSS-категория файла: от неё зависит цвет значка в списке. */
    private fun fileKind(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> "image"
        "mp4", "mkv", "mov", "avi", "webm", "m4v" -> "video"
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio"
        "pdf", "doc", "docx", "txt", "md", "rtf", "xls", "xlsx", "ppt", "pptx" -> "doc"
        "zip", "rar", "7z", "tar", "gz" -> "archive"
        else -> "other"
    }

    private fun fileTypeLabel(file: File): String {
        val extension = file.extension
        return if (extension.isEmpty()) "FILE" else extension.take(4).uppercase(Locale.ROOT)
    }

    private fun renderGallery(sources: List<MediaSource>, selectedKey: String?): ByteArray {
        val visible = if (selectedKey.isNullOrBlank()) sources else sources.filter { it.key == selectedKey }
        val sections = if (visible.isEmpty()) {
            "<p class=\"empty\"><span class=\"empty-icon\">▤</span><span>Источник не найден.</span></p>"
        } else {
            buildString { for (source in visible) appendGallerySection(source) }
        }
        val tabs = if (sources.size > 1) galleryTabs(sources, selectedKey) else ""
        return WebTemplates.GALLERY
            .replace(GALLERY_TABS_MARKER, tabs)
            .replace(GALLERY_MARKER, sections)
            .toByteArray(StandardCharsets.UTF_8)
    }

    /** Вкладки «Все / источник»: помогают переключаться, когда источников несколько. */
    private fun galleryTabs(sources: List<MediaSource>, selectedKey: String?): String = buildString {
        fun appendTab(key: String?, title: String, active: Boolean) {
            append("<a class=\"tab")
            if (active) append(" active")
            append("\" href=\"").append(GALLERY_PATH)
            if (key != null) append("?src=").append(encodePathSegment(key))
            append("\">").append(htmlEscape(title)).append("</a>")
        }

        appendTab(null, "Все", selectedKey.isNullOrBlank())
        for (source in sources) {
            appendTab(source.key, source.title, source.key == selectedKey)
        }
    }

    /**
     * Страница просмотра одного элемента с листанием по списку источника.
     * Список забирается с `/api/media?src=...`, поэтому страница маленькая,
     * а навигация работает без перезагрузки.
     *
     * `null` — источника или элемента нет (отвечаем 404).
     */
    private fun renderViewer(
        sources: List<MediaSource>,
        selectedKey: String?,
        itemId: String?,
    ): ByteArray? {
        val source = sources.firstOrNull { it.key == selectedKey } ?: return null
        val item = try {
            source.items().firstOrNull { it.id == itemId }
        } catch (e: Exception) {
            logger("HTTP: не удалось прочитать источник ${source.key}: ${e.message}")
            null
        } ?: return null

        val mediaUrl = "/media?src=" + encodePathSegment(source.key) +
            "&amp;id=" + encodePathSegment(item.id)

        return WebTemplates.VIEWER
            .replace("__TITLE__", htmlEscape(item.name))
            .replace("__GALLERY__", GALLERY_PATH)
            .replace("__NAME__", htmlEscape(item.name))
            .replace("__MEDIA__", mediaUrl)
            .replace("__SRC__", jsString(source.key))
            .replace("__ID__", jsString(item.id))
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun StringBuilder.appendGallerySection(source: MediaSource) {
        val items = try {
            source.items()
        } catch (e: Exception) {
            logger("HTTP: не удалось прочитать источник ${source.key}: ${e.message}")
            emptyList()
        }

        append("<section class=\"source\"><div class=\"source-head\"><h2>")
            .append(htmlEscape(source.title))
            .append("</h2><span class=\"count\">").append(items.size)
            .append("</span></div>")
        if (items.isEmpty()) {
            append("<p class=\"empty\"><span class=\"empty-icon\">▤</span><span>Пусто.")
            if (source.key == DEVICE_SOURCE_KEY) {
                append(" Разрешите приложению доступ к фото и видео.")
            }
            append("</span></p></section>")
            return
        }

        val shown = items.take(MAX_GALLERY_ITEMS)
        append("<div class=\"grid\">")
        for (item in shown) {
            val encodedSource = encodePathSegment(source.key)
            val encodedId = encodePathSegment(item.id)
            append("<a class='card' href='/view?src=").append(encodedSource)
                .append("&amp;id=").append(encodedId)
                .append("' target='_blank' rel='noopener'>")
            if (item.kind == MediaItem.Kind.OTHER) {
                append("<div class='none'>▤</div>")
            } else {
                append("<img class='thumb' src='/thumb?src=").append(encodedSource)
                    .append("&amp;id=").append(encodedId)
                    .append("' loading='lazy' decoding='async' alt=''>")
                if (item.kind == MediaItem.Kind.VIDEO) append("<span class='badge'>▶</span>")
            }
            append("<span class='cap' title='").append(htmlEscape(item.name)).append("'>")
                .append(htmlEscape(item.name)).append("</span></a>")
        }
        append("</div>")
        if (items.size > shown.size) {
            append("<p class=\"empty\"><span class=\"empty-icon\">…</span><span>Показаны последние ")
                .append(shown.size).append(" из ").append(items.size).append(".</span></p>")
        }
        append("</section>")
    }

    private fun mediaJson(sources: List<MediaSource>, selectedKey: String?): String = buildString {
        val visible = if (selectedKey.isNullOrBlank()) sources else sources.filter { it.key == selectedKey }
        append("{\"sources\":[")
        visible.forEachIndexed { index, source ->
            if (index > 0) append(',')
            val items = try {
                source.items()
            } catch (e: Exception) {
                logger("HTTP: не удалось прочитать источник ${source.key}: ${e.message}")
                emptyList()
            }
            append("{\"key\":\"").append(jsonEscape(source.key)).append("\",")
            append("\"title\":\"").append(jsonEscape(source.title)).append("\",")
            append("\"items\":[")
            items.forEachIndexed { itemIndex, item ->
                if (itemIndex > 0) append(',')
                val encodedSource = encodePathSegment(source.key)
                val encodedId = encodePathSegment(item.id)
                append("{\"id\":\"").append(jsonEscape(item.id)).append("\",")
                append("\"name\":\"").append(jsonEscape(item.name)).append("\",")
                append("\"size\":").append(item.size).append(',')
                append("\"date\":").append(item.dateMillis).append(',')
                append("\"mime\":\"").append(jsonEscape(item.mimeType)).append("\",")
                append("\"kind\":\"").append(item.kind.name.lowercase(Locale.US)).append("\",")
                append("\"url\":\"/media?src=").append(encodedSource).append("&id=").append(encodedId).append("\",")
                append("\"thumb\":\"/thumb?src=").append(encodedSource).append("&id=").append(encodedId).append("\"}")
            }
            append("]}")
        }
        append("]}")
    }

    private fun filesJson(entries: List<File>): String = buildString {
        append("{\"files\":[")
        entries.forEachIndexed { index, file ->
            if (index > 0) append(',')
            append("{\"name\":\"").append(jsonEscape(file.name)).append("\",")
            append("\"size\":").append(file.length()).append(',')
            append("\"modified\":").append(file.lastModified()).append('}')
        }
        append("]}")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, value, units[unit])
    }

    /** Превью в браузере только для безопасных типов; остальное — скачивание. */
    private fun isInlinePreview(contentType: String): Boolean =
        contentType.startsWith("image/") ||
            contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType == "application/pdf" ||
            contentType.startsWith("text/plain")

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input, MAX_LINE_BYTES) ?: return null
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) throw IOException("некорректная строка запроса: $requestLine")

        val headers = HashMap<String, String>()
        var totalBytes = requestLine.length
        while (true) {
            val line = readLine(input, MAX_LINE_BYTES) ?: break
            if (line.isEmpty()) break
            totalBytes += line.length
            if (totalBytes > MAX_HEADER_BYTES) throw IOException("заголовки слишком большие")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val (path, query) = splitTarget(parts[1])
        return Request(parts[0].uppercase(), path, query, headers)
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buffer.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (buffer.size() >= limit) throw IOException("строка запроса слишком длинная")
            buffer.write(b)
        }
        val text = String(buffer.toByteArray(), StandardCharsets.UTF_8)
        return if (text.endsWith("\r")) text.dropLast(1) else text
    }

    private fun splitTarget(target: String): Pair<String, Map<String, String>> {
        var rest = target
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd > 0) {
            val slash = rest.indexOf('/', schemeEnd + 3)
            rest = if (slash >= 0) rest.substring(slash) else "/"
        }

        val queryIndex = rest.indexOf('?')
        val rawPath = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
        val rawQuery = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""

        val path = percentDecode(rawPath).ifEmpty { "/" }
        val query = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            query[percentDecode(key)] = percentDecode(value)
        }
        return path to query
    }

    /**
     * Декодирует %XX (UTF-8), не превращая `+` в пробел:
     * в именах файлов `+` — допустимый символ.
     */
    private fun percentDecode(value: String): String {
        val bytes = ByteArrayOutputStream()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                bytes.write(literal.toString().toByteArray(StandardCharsets.UTF_8))
                literal.clear()
            }
        }

        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val decoded = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (decoded != null) {
                    flushLiteral()
                    bytes.write(decoded)
                    i += 3
                    continue
                }
            }
            literal.append(c)
            i++
        }
        flushLiteral()
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    /** Процентное кодирование одного сегмента пути (как encodeURIComponent). */
    private fun encodePathSegment(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
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

    /** ASCII-вариант имени для `filename="..."` в Content-Disposition. */
    private fun asciiFileName(name: String): String {
        val builder = StringBuilder(name.length)
        for (c in name) {
            builder.append(if (c.code in 0x20..0x7E && c != '"' && c != '\\') c else '_')
        }
        return builder.toString()
    }

    private fun htmlEscape(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(c)
            }
        }
        return builder.toString()
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String?,
        body: ByteArray,
        includeBody: Boolean = true,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        cacheControl: String = "no-store",
    ) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Connection: close\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: ").append(cacheControl).append("\r\n")
            for ((name, value) in extraHeaders) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        try {
            output.write(headers.toByteArray(StandardCharsets.UTF_8))
            if (includeBody) output.write(body)
            output.flush()
        } catch (_: IOException) {
            // Клиент отключился — отвечать уже некому.
        }
    }

    private fun errorJson(message: String): String =
        "{\"ok\":false,\"error\":\"${jsonEscape(message)}\"}"

    /** Строковый литерал для вставки в `<script>`: `<` экранируется, чтобы имя файла не сломало тег. */
    private fun jsString(value: String): String =
        "\"" + jsonEscape(value).replace("<", "\\u003c") + "\""

    private fun jsonEscape(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (c < ' ') builder.append(String.format("\\u%04x", c.code)) else builder.append(c)
            }
        }
        return builder.toString()
    }

    companion object {
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val CONTENT_TYPE_HTML = "text/html; charset=utf-8"
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        private const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"

        private const val FILES_PATH_PREFIX = "/files/"
        private const val THUMB_FILE_PATH = "/thumb-file"
        private const val FILES_MARKER = "<!--FILES-->"
        private const val FILE_COUNT_MARKER = "<!--FILE_COUNT-->"
        private const val ALLOWED_METHODS = "GET, HEAD, POST, PUT, DELETE, OPTIONS"

        /** Ключ источника медиатеки телефона; должен совпадать с `DeviceMediaSource.key`. */
        const val DEVICE_SOURCE_KEY = "device"

        private const val GALLERY_PATH = "/gallery"
        private const val OPEN_PATH = "/open"

        /** Сколько карточек показывать на источник: страница должна открываться быстро. */
        private const val MAX_GALLERY_ITEMS = 300
        private const val GALLERY_MARKER = "<!--SECTIONS-->"
        private const val GALLERY_TABS_MARKER = "<!--TABS-->"
    }
}
