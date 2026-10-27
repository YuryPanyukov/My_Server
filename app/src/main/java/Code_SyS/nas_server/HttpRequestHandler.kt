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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Минимальный HTTP/1.1-обработчик, работающий на том же порту, что и бинарный
 * протокол [Protocol]. Запросы различаются по первым байтам в
 * [FileReceiverServer], поэтому отдельный порт не нужен.
 *
 * Маршруты:
 *   `GET /`             — страница загрузки и список принятых файлов;
 *   `GET /health`       — проверка доступности, отвечает `OK`;
 *   `GET /open`         — страница-мостик из QR: приложение или веб-интерфейс;
 *   `GET /api/files`    — содержимое папки (файлы и вложенные папки) в JSON;
 *   `GET /api/folders`  — все папки хранилища (для перемещения);
 *   `GET /files/<путь>` — содержимое файла (превью для картинок, видео, PDF и текста);
 *   `DELETE /files/<путь>` — удаление файла или папки (`?recursive=1`) в корзину;
 *   `GET /zip?path=<путь>` — текущая папка одним ZIP-архивом;
 *   `POST /folder?path=<путь>&name=<имя>` — создание папки;
 *   `POST /rename?path=<путь>&name=<имя>` — переименование;
 *   `POST /move?path=<путь>&to=<папка>` — перемещение;
 *   `POST /delete` — удаление списка путей (по одному в строке);
 *   `GET /trash` — корзина: восстановление и окончательное удаление;
 *   `GET /api/trash` — корзина в JSON;
 *   `POST /restore` — восстановление по `id` или списку id в теле;
 *   `POST /trash/empty` — очистить корзину;
 *   `DELETE /trash/<id>` — удалить элемент корзины навсегда;
 *   `GET /library` — видеотека: видео и их описания;
 *   `GET /api/library` — видеотека в JSON;
 *   `POST /library/save?path=<путь>&title=<имя>` — сохранить описание (тело — текст);
 *   `POST /library/reset?path=<путь>` — убрать описание;
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
 * @param saver запись файла в корень; тот же колбэк, что и у бинарного протокола
 * @param files список доступных для просмотра файлов (уже отфильтрованный режимом доступа)
 * @param storageRoot корень папки приёма; без него страница работает по плоскому списку [files]
 * @param folderSaver запись файла в выбранную вложенную папку
 * @param mediaSources источники для галереи (принятые файлы, медиатека телефона)
 * @param fileThumbnailer превью принятого файла для списка (`null` — тип без превью)
 * @param logger приёмник диагностических сообщений
 */
class HttpRequestHandler(
    private val saver: (fileName: String, fileSize: Long, input: InputStream) -> File?,
    private val files: () -> List<File> = { emptyList() },
    private val storageRoot: () -> File? = { null },
    private val folderSaver: ((fileName: String, fileSize: Long, dir: File, input: InputStream) -> File?)? = null,
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
                    request.path == "/" || request.path == "/index.html" -> {
                        val currentPath = StoragePaths.normalize(request.query["path"])
                        if (currentPath == null) {
                            notFound(output)
                        } else {
                            val entries = entriesFor(currentPath)
                            if (entries == null) {
                                notFound(output)
                            } else {
                                writeResponse(
                                    output, 200, "OK", CONTENT_TYPE_HTML,
                                    renderPage(entries, currentPath), includeBody = !headOnly
                                )
                            }
                        }
                    }

                    request.path == "/health" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_TEXT, "OK".toByteArray(), includeBody = !headOnly
                    )

                    request.path == OPEN_PATH -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_HTML,
                        renderOpenPage(request.headers["host"]), includeBody = !headOnly
                    )

                    request.path == "/api/files" -> {
                        val currentPath = StoragePaths.normalize(request.query["path"])
                        if (currentPath == null) {
                            notFound(output)
                        } else {
                            val entries = entriesFor(currentPath)
                            if (entries == null) {
                                notFound(output)
                            } else {
                                writeResponse(
                                    output, 200, "OK", CONTENT_TYPE_JSON,
                                    filesJson(entries, currentPath).toByteArray(), includeBody = !headOnly
                                )
                            }
                        }
                    }

                    request.path == "/api/folders" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_JSON,
                        foldersJson().toByteArray(), includeBody = !headOnly
                    )

                    request.path == "/zip" -> serveZip(
                        request.query["path"], output, headOnly
                    )

                    request.path.startsWith(FILES_PATH_PREFIX) -> serveStoredFile(
                        request.path.removePrefix(FILES_PATH_PREFIX), request.headers["range"], output, headOnly
                    )

                    request.path == "/gallery" -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_HTML,
                        renderGallery(mediaSources(), request.query["src"]), includeBody = !headOnly
                    )

                    request.path == TRASH_PATH -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_HTML,
                        renderTrashPage(), includeBody = !headOnly
                    )

                    request.path == TRASH_API_PATH -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_JSON,
                        trashJson().toByteArray(), includeBody = !headOnly
                    )

                    request.path == LIBRARY_PATH -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_HTML,
                        renderLibraryPage(), includeBody = !headOnly
                    )

                    request.path == LIBRARY_API_PATH -> writeResponse(
                        output, 200, "OK", CONTENT_TYPE_JSON,
                        libraryJson().toByteArray(), includeBody = !headOnly
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

            "POST", "PUT" -> when (request.path) {
                "/upload" -> upload(request, body, output)
                "/folder" -> createFolder(request, output)
                "/rename" -> renameEntry(request, output)
                "/move" -> moveEntry(request, output)
                "/delete" -> deleteMany(request, body, output)
                "/media/delete" -> deleteMedia(request, output)
                "/restore" -> restoreEntries(request, body, output)
                LIBRARY_SAVE_PATH -> saveLibraryItem(request, body, output)
                LIBRARY_RESET_PATH -> resetLibraryItem(request, output)
                TRASH_EMPTY_PATH -> emptyTrash(output)
                else -> notFound(output)
            }

            "DELETE" -> when {
                request.path.startsWith(TRASH_PATH_PREFIX) ->
                    deleteTrashEntry(request.path.removePrefix(TRASH_PATH_PREFIX), output)

                request.path.startsWith(FILES_PATH_PREFIX) -> {
                    val recursive = request.query["recursive"] == "1" || request.query["recursive"] == "true"
                    deleteStoredFile(request.path.removePrefix(FILES_PATH_PREFIX), recursive, output)
                }

                else -> notFound(output)
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

        // Пустая папка — корень хранилища, как было до появления папок.
        val targetPath = StoragePaths.normalize(request.query["path"])
        if (targetPath == null) {
            writeResponse(
                output, 400, "Bad Request", CONTENT_TYPE_JSON,
                errorJson("Недопустимый путь папки").toByteArray()
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
            if (targetPath.isEmpty()) {
                saver(fileName, contentLength, body)
            } else {
                val dir = ReceivedStorage(storageRoot).resolve(targetPath)
                if (dir == null || !dir.isDirectory) null
                else folderSaver?.invoke(fileName, contentLength, dir, body)
            }
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
        val storedPath = storageRoot()?.let { StoragePaths.relativePath(it, saved) } ?: saved.name
        val json = "{\"ok\":true,\"name\":\"${jsonEscape(saved.name)}\"," +
            "\"path\":\"${jsonEscape(storedPath)}\",\"size\":${saved.length()}}"
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
        val file = resolveAllowedFile(name)
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

    /**
     * Удаляет файл, а с `?recursive=1` — папку со всем содержимым. Элемент
     * переезжает в корзину: `trash` в ответе — id для `POST /restore`.
     * Удаляются только пути, видимые в текущем режиме доступа.
     */
    private fun deleteStoredFile(path: String, recursive: Boolean, output: OutputStream) {
        val relative = StoragePaths.normalize(path)
        if (relative == null || relative.isEmpty()) {
            notFound(output)
            return
        }

        val root = storageRoot()
        val legacyFile = if (root == null) files().firstOrNull { it.isFile && it.name == relative } else null
        val storage = if (root != null) ReceivedStorage { root } else null
        val entry = storage?.entry(relative)

        if ((root != null && entry == null) || (root == null && legacyFile == null)) {
            notFound(output)
            return
        }

        if (entry != null && entry.isDirectory && !recursive) {
            writeResponse(
                output, 409, "Conflict", CONTENT_TYPE_JSON,
                errorJson("Это папка: добавьте ?recursive=1").toByteArray()
            )
            return
        }
        if (!isAllowedForDelete(relative, entry?.isDirectory == true)) {
            // Скрытый файл не должен выдавать себя даже кодом ответа — 404,
            // как и все ссылки на него в режиме частичного доступа.
            if (entry != null && !entry.isDirectory) {
                notFound(output)
            } else {
                writeResponse(
                    output, 403, "Forbidden", CONTENT_TYPE_JSON,
                    errorJson("В этом режиме доступа папку с закрытыми файлами удалить нельзя").toByteArray()
                )
            }
            return
        }

        // Плоский список без корня хранилища (старый режим) не умеет корзину:
        // там удаление остаётся окончательным.
        val trashed = if (root == null) {
            val deleted = try {
                legacyFile?.delete() ?: false
            } catch (e: Exception) {
                logger("HTTP: ошибка удаления $relative: ${e.message}")
                false
            }
            if (deleted) null else return writeServerError(output)
        } else {
            try {
                storage?.trash(relative)
            } catch (e: Exception) {
                logger("HTTP: ошибка удаления $relative: ${e.message}")
                null
            }
        }

        if (root != null && trashed == null) {
            writeResponse(
                output, 500, "Internal Server Error", CONTENT_TYPE_JSON,
                errorJson("Не удалось удалить файл").toByteArray()
            )
            return
        }

        logger("HTTP: удалён $relative")
        val trashField = trashed?.let { ",\"trash\":\"${jsonEscape(it.id)}\"" }.orEmpty()
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            "{\"ok\":true,\"path\":\"${jsonEscape(relative)}\"$trashField}".toByteArray()
        )
    }

    private fun writeServerError(output: OutputStream) {
        writeResponse(
            output, 500, "Internal Server Error", CONTENT_TYPE_JSON,
            errorJson("Не удалось удалить файл").toByteArray()
        )
    }

    /** Разрешённый к отдаче файл: путь разбирается и сверяется с режимом доступа. */
    private fun resolveAllowedFile(path: String): File? {
        val root = storageRoot()
        if (root == null) return files().firstOrNull { it.isFile && it.name == path }

        val relative = StoragePaths.normalize(path) ?: return null
        if (relative.isEmpty()) return null
        val file = ReceivedStorage { root }.resolve(relative) ?: return null
        if (!file.isFile || relative !in allowedRelatives(root)) return null
        return file
    }

    private fun allowedRelatives(root: File): Set<String> =
        files().mapNotNull { StoragePaths.relativePath(root, it) }.toSet()

    /**
     * Удаление папки, в которой есть скрытые режимом доступа файлы, запрещено:
     * иначе клиент мог бы унести с телефона то, что ему не показывали.
     */
    private fun isAllowedForDelete(relative: String, directory: Boolean): Boolean {
        val root = storageRoot() ?: return true
        if (!directory) return relative in allowedRelatives(root)

        val allowed = allowedRelatives(root)
        val storage = ReceivedStorage { root }
        val prefix = "$relative/"
        return storage.allFiles().all { file ->
            val path = StoragePaths.relativePath(root, file) ?: return@all false
            !path.startsWith(prefix) || path in allowed
        }
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
        val file = if (name == null) null else resolveAllowedFile(name)
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
            // Оригинал отдаём по тому же относительному пути: файл может лежать
            // во вложенной папке, а `file.name` указывал бы на корень.
            val relative = storageRoot()?.let { StoragePaths.relativePath(it, file) } ?: file.name
            serveStoredFile(relative, null, output, headOnly)
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

    private fun renderPage(entries: List<StorageEntry>, currentPath: String): ByteArray {
        val rows = if (entries.isEmpty()) {
            if (currentPath.isEmpty()) {
                "<li class=\"empty\"><span class=\"empty-icon\">✦</span>" +
                    "<span>Пока нет файлов</span>" +
                    "<small>Перетащите первый файл в область выше</small></li>"
            } else {
                "<li class=\"empty\"><span class=\"empty-icon\">▤</span>" +
                    "<span>Папка пуста</span>" +
                    "<small>Перетащите файл в область выше — он попадёт сюда</small></li>"
            }
        } else {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            buildString {
                for (entry in entries) {
                    if (entry.isDirectory) {
                        append("<li class=\"file-item folder\" data-name=\"")
                            .append(htmlEscape(entry.name.lowercase(Locale.ROOT)))
                            .append("\" data-path=\"").append(htmlEscape(entry.path))
                            .append("\" data-dir=\"1\"><label class=\"pick\" title=\"Выбрать\">")
                            .append("<input type=\"checkbox\" class=\"sel\" data-path=\"")
                            .append(htmlEscape(entry.path)).append("\"></label>")
                            .append("<span class=\"file-thumb folder-thumb\"><span class=\"ftype\" data-kind=\"folder\">DIR</span></span>")
                            .append("<span class=\"file-main\"><a class=\"file\" href=\"/?path=")
                            .append(encodeRelativePath(entry.path)).append("\">")
                            .append(htmlEscape(entry.name)).append("</a>")
                            .append("<span class=\"meta\">Папка</span></span>")
                            .append("<span class=\"file-actions\"><button class=\"ren\" type=\"button\" ")
                            .append("title=\"Переименовать\" aria-label=\"Переименовать\" data-path=\"")
                            .append(htmlEscape(entry.path)).append("\" data-name=\"")
                            .append(htmlEscape(entry.name)).append("\">✎</button>")
                            .append("<button class=\"del\" type=\"button\" title=\"Удалить\" aria-label=\"Удалить\" data-dir=\"1\" data-path=\"")
                            .append(htmlEscape(entry.path)).append("\" data-file=\"")
                            .append(htmlEscape(entry.name)).append("\">×</button></span></li>")
                        continue
                    }

                    val kind = fileKind(entry.name)
                    val encodedPath = encodeRelativePath(entry.path)
                    append("<li class=\"file-item\" data-name=\"")
                        .append(htmlEscape(entry.name.lowercase(Locale.ROOT)))
                        .append("\" data-path=\"").append(htmlEscape(entry.path))
                        .append("\" data-dir=\"0\"><label class=\"pick\" title=\"Выбрать\">")
                        .append("<input type=\"checkbox\" class=\"sel\" data-path=\"")
                        .append(htmlEscape(entry.path)).append("\"></label>")
                        .append("<span class=\"file-thumb\" data-kind=\"").append(kind).append("\">")
                    // Превью запрашивается лениво и только для медиа: остальные
                    // типы показывают значок расширения.
                    if (kind == "image" || kind == "video") {
                        append("<img class=\"fthumb\" src=\"").append(THUMB_FILE_PATH)
                            .append("?name=").append(encodedPath)
                            .append("&amp;v=").append(entry.modified)
                            .append("\" loading=\"lazy\" decoding=\"async\" alt=\"\">")
                        if (kind == "video") append("<span class=\"play\" aria-hidden=\"true\">▶</span>")
                    }
                    append("<span class=\"ftype\" data-kind=\"").append(kind)
                        .append("\">").append(htmlEscape(fileTypeLabel(entry.name)))
                        .append("</span></span><span class=\"file-main\"><a class=\"file\" href=\"").append(FILES_PATH_PREFIX)
                        .append(encodedPath)
                        .append("\" target=\"_blank\" rel=\"noopener\">")
                        .append(htmlEscape(entry.name))
                        .append("</a><span class=\"meta\">")
                        .append(formatSize(entry.size))
                        .append(" · ")
                        .append(dateFormat.format(Date(entry.modified)))
                        .append("</span></span><span class=\"file-actions\"><button class=\"ren\" type=\"button\" ")
                        .append("title=\"Переименовать\" aria-label=\"Переименовать\" data-path=\"")
                        .append(htmlEscape(entry.path)).append("\" data-name=\"")
                        .append(htmlEscape(entry.name)).append("\">✎</button>")
                        .append("<button class=\"del\" type=\"button\" title=\"Удалить\" aria-label=\"Удалить\" data-href=\"")
                        .append(FILES_PATH_PREFIX).append(encodedPath)
                        .append("\" data-path=\"").append(htmlEscape(entry.path))
                        .append("\" data-file=\"").append(htmlEscape(entry.name))
                        .append("\">×</button></span></li>")
                }
            }
        }

        val crumbs = buildString {
            if (currentPath.isEmpty()) {
                append("<span class=\"crumb current\">Все файлы</span>")
            } else {
                append("<a class=\"crumb\" href=\"/\">Все файлы</a>")
                var accumulated = ""
                val segments = currentPath.split('/')
                for ((index, segment) in segments.withIndex()) {
                    accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
                    append("<span class=\"crumb-sep\">/</span>")
                    if (index == segments.lastIndex) {
                        append("<span class=\"crumb current\">").append(htmlEscape(segment)).append("</span>")
                    } else {
                        append("<a class=\"crumb\" href=\"/?path=")
                            .append(encodeRelativePath(accumulated)).append("\">")
                            .append(htmlEscape(segment)).append("</a>")
                    }
                }
            }
        }

        val zipLink = if (currentPath.isEmpty()) "/zip" else "/zip?path=" + encodeRelativePath(currentPath)

        val trashSize = try {
            storageRoot()?.let { ReceivedStorage { it }.trashSize() } ?: 0
        } catch (_: Exception) {
            0
        }

        return WebTemplates.PAGE
            .replace(FILES_MARKER, rows)
            .replace(FILE_COUNT_MARKER, entries.size.toString())
            .replace(BREADCRUMBS_MARKER, crumbs)
            .replace(CURRENT_PATH_MARKER, htmlEscape(currentPath))
            .replace(ZIP_LINK_MARKER, zipLink)
            .replace(TRASH_LINK_MARKER, trashLink(trashSize))
            .toByteArray(StandardCharsets.UTF_8)
    }

    /** Ссылка «Корзина» в панели: счётчик показывает, что там есть что вернуть. */
    private fun trashLink(size: Int): String {
        val suffix = if (size > 0) " ($size)" else ""
        return "<a class=\"ghost-btn btn-small\" id=\"trash-link\" href=\"$TRASH_PATH\">" +
            "Корзина$suffix</a>"
    }

    /** Страница корзины: вернуть элемент или удалить его окончательно. */
    private fun renderTrashPage(): ByteArray {
        val entries = trashEntries()

        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val rows = if (entries.isEmpty()) {
            "<li class=\"empty\"><span class=\"empty-icon\">🗑</span>" +
                "<span>Корзина пуста</span>" +
                "<small>Удалённые файлы и папки будут появляться здесь</small></li>"
        } else {
            buildString {
                for (entry in entries) {
                    val label = if (entry.isDirectory) "DIR" else fileTypeLabel(entry.name)
                    val kind = if (entry.isDirectory) "folder" else fileKind(entry.name)
                    append("<li class=\"file-item\" data-id=\"").append(htmlEscape(entry.id))
                        .append("\"><span class=\"file-thumb\" data-kind=\"").append(kind)
                        .append("\"><span class=\"ftype\" data-kind=\"").append(kind).append("\">")
                        .append(htmlEscape(label)).append("</span></span>")
                        .append("<span class=\"file-main\"><span class=\"file\">")
                        .append(htmlEscape(entry.name))
                        .append("</span><span class=\"meta\">Было: ")
                        .append(htmlEscape(entry.originalPath)).append(" · ")
                        .append(dateFormat.format(Date(entry.deletedAt)))
                        .append("</span></span><span class=\"file-actions\">")
                        .append("<button class=\"restore\" type=\"button\" title=\"Восстановить\" ")
                        .append("aria-label=\"Восстановить\" data-id=\"").append(htmlEscape(entry.id))
                        .append("\">↩</button>")
                        .append("<button class=\"del\" type=\"button\" title=\"Удалить навсегда\" ")
                        .append("aria-label=\"Удалить навсегда\" data-id=\"").append(htmlEscape(entry.id))
                        .append("\">×</button></span></li>")
                }
            }
        }

        val clear = if (entries.isEmpty()) {
            ""
        } else {
            "<button class=\"ghost-btn btn-small danger\" id=\"trash-empty\" type=\"button\">" +
                "Очистить корзину</button>"
        }

        return WebTemplates.TRASH
            .replace(TRASH_ITEMS_MARKER, rows)
            .replace(TRASH_CLEAR_MARKER, clear)
            .replace(TRASH_COUNT_MARKER, entries.size.toString())
            .toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Корзина целиком. Режим частичного доступа фильтрует текущие файлы,
     * а не удалённые: у элемента корзины уже нет времени изменения, по которому
     * считаются режимы «сегодня/дата», и скрывать сам факт удаления бессмысленно.
     */
    private fun trashEntries(): List<TrashEntry> = try {
        storageRoot()?.let { ReceivedStorage { it }.trashEntries() } ?: emptyList()
    } catch (e: Exception) {
        logger("HTTP: не удалось прочитать корзину: ${e.message}")
        emptyList()
    }

    /** Корзина в JSON — для встроенного клиента и скриптов. */
    private fun trashJson(): String = buildString {
        val entries = trashEntries()
        append("{\"items\":[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append("{\"id\":\"").append(jsonEscape(entry.id))
                .append("\",\"name\":\"").append(jsonEscape(entry.name))
                .append("\",\"path\":\"").append(jsonEscape(entry.originalPath))
                .append("\",\"directory\":").append(entry.isDirectory)
                .append(",\"size\":").append(entry.size)
                .append(",\"deleted\":").append(entry.deletedAt)
                .append('}')
        }
        append("]}")
    }

    // --- Видеотека -----------------------------------------------------------

    /** Видеотека поверх хранилища; без корня папок показывать нечего. */
    private fun library(): MediaLibrary? = storageRoot()?.let { root -> MediaLibrary { root } }

    /** Видео, видимые в текущем режиме доступа. */
    private fun libraryItems(): List<LibraryItem> = try {
        library()?.items()?.filter { isAllowedForDelete(it.path, directory = false) }.orEmpty()
    } catch (e: Exception) {
        logger("HTTP: не удалось прочитать видеотеку: ${e.message}")
        emptyList()
    }

    /** Страница видеотеки: карточки видео с заголовком и описанием. */
    private fun renderLibraryPage(): ByteArray {
        val items = libraryItems()
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        val rows = if (items.isEmpty()) {
            "<p class='empty'><span class='empty-icon'>▶</span><span>Пока нет видео.</span>" +
                "<small>Загрузите видео на странице загрузки — оно появится здесь.</small></p>"
        } else {
            buildString {
                for (item in items) {
                    val encoded = encodeRelativePath(item.path)
                    append("<article class='vcard'>")
                        .append("<a class='vthumb' href='").append(FILES_PATH_PREFIX).append(encoded)
                        .append("' target='_blank' rel='noopener'>")
                        .append("<img class='thumb' src='").append(THUMB_FILE_PATH)
                        .append("?name=").append(encoded).append("&amp;v=").append(item.modified)
                        .append("' loading='lazy' decoding='async' alt=''>")
                        .append("<span class='play' aria-hidden='true'>▶</span></a>")
                        .append("<div class='vbody'><h3>").append(htmlEscape(item.title)).append("</h3>")
                    if (item.description.isBlank()) {
                        append("<p class='vdesc muted'>Без описания</p>")
                    } else {
                        append("<p class='vdesc'>").append(htmlEscape(item.description)).append("</p>")
                    }
                    append("<div class='vfoot'><span class='vmeta'>")
                        .append(formatSize(item.size)).append(" · ")
                        .append(dateFormat.format(Date(item.modified)))
                        .append("</span><span class='vactions'>")
                        .append("<button class='edit' type='button' title='Изменить описание' ")
                        .append("aria-label='Изменить описание' data-path='").append(htmlEscape(item.path))
                        .append("'>✎</button>")
                        .append("<button class='del' type='button' title='Удалить' ")
                        .append("aria-label='Удалить' data-path='").append(htmlEscape(item.path))
                        .append("' data-name='").append(htmlEscape(item.name)).append("'>×</button>")
                        .append("</span></div></div></article>")
                }
            }
        }

        val data = buildString {
            append('{')
            items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append(jsString(item.path)).append(":{title:").append(jsString(item.title))
                    .append(",description:").append(jsString(item.description))
                    .append(",described:").append(item.hasDescription)
                    .append('}')
            }
            append('}')
        }

        return WebTemplates.LIBRARY
            .replace(LIBRARY_ITEMS_MARKER, rows)
            .replace(LIBRARY_COUNT_MARKER, items.size.toString())
            .replace(LIBRARY_DATA_MARKER, data)
            .toByteArray(StandardCharsets.UTF_8)
    }

    /** Видеотека в JSON — для встроенного клиента и скриптов. */
    private fun libraryJson(): String = buildString {
        val items = libraryItems()
        append("{\"items\":[")
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append("{\"path\":\"").append(jsonEscape(item.path))
                .append("\",\"name\":\"").append(jsonEscape(item.name))
                .append("\",\"title\":\"").append(jsonEscape(item.title))
                .append("\",\"description\":\"").append(jsonEscape(item.description))
                .append("\",\"size\":").append(item.size)
                .append(",\"modified\":").append(item.modified)
                .append(",\"updated\":").append(item.updatedAt)
                .append(",\"described\":").append(item.hasDescription)
                .append('}')
        }
        append("]}")
    }

    /**
     * Сохраняет описание. Заголовок и путь — в строке запроса, текст описания —
     * в теле: так кириллица не упирается в лимит длины строки запроса.
     */
    private fun saveLibraryItem(request: Request, body: InputStream, output: OutputStream) {
        val target = library() ?: run {
            notFound(output)
            return
        }
        val videoPath = StoragePaths.normalize(request.query["path"])
        if (videoPath.isNullOrEmpty() || !isAllowedForDelete(videoPath, directory = false)) {
            notFound(output)
            return
        }
        val length = request.headers["content-length"]?.toLongOrNull() ?: 0L
        val fromBody = if (length > 0) {
            if (length > MAX_LIBRARY_BODY_BYTES) {
                writeResponse(
                    output, 413, "Payload Too Large", CONTENT_TYPE_JSON,
                    errorJson("Слишком длинное описание").toByteArray()
                )
                return
            }
            try {
                String(readLimitedBytes(body, length.toInt()), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        } else {
            null
        }
        val description = fromBody ?: request.query["description"].orEmpty()

        val saved = try {
            target.save(request.query["path"], request.query["title"].orEmpty(), description)
        } catch (e: Exception) {
            logger("HTTP: не удалось сохранить описание: ${e.message}")
            null
        }
        if (saved == null) {
            writeResponse(
                output, 404, "Not Found", CONTENT_TYPE_JSON,
                errorJson("Видео не найдено").toByteArray()
            )
            return
        }

        logger("HTTP: описание сохранено для ${saved.path}")
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            ("{\"ok\":true,\"path\":\"" + jsonEscape(saved.path) +
                "\",\"title\":\"" + jsonEscape(saved.title) + "\"}").toByteArray()
        )
    }

    /** Убирает описание видео: карточка снова показывает имя файла. */
    private fun resetLibraryItem(request: Request, output: OutputStream) {
        val target = library() ?: run {
            notFound(output)
            return
        }
        val videoPath = StoragePaths.normalize(request.query["path"])
        if (videoPath.isNullOrEmpty() || !isAllowedForDelete(videoPath, directory = false)) {
            notFound(output)
            return
        }
        if (!target.reset(videoPath)) {
            writeResponse(
                output, 404, "Not Found", CONTENT_TYPE_JSON,
                errorJson("Видео не найдено").toByteArray()
            )
            return
        }
        writeResponse(output, 200, "OK", CONTENT_TYPE_JSON, "{\"ok\":true}".toByteArray())
    }

    /** CSS-категория файла: от неё зависит цвет значка в списке. */
    private fun fileKind(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> "image"
        "mp4", "mkv", "mov", "avi", "webm", "m4v" -> "video"
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio"
        "pdf", "doc", "docx", "txt", "md", "rtf", "xls", "xlsx", "ppt", "pptx" -> "doc"
        "zip", "rar", "7z", "tar", "gz" -> "archive"
        else -> "other"
    }

    private fun fileTypeLabel(name: String): String {
        val extension = name.substringAfterLast('.', "")
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
            append("<div class='cell'><a class='card' href='/view?src=").append(encodedSource)
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
            append("<button class='media-del' type='button' title='Удалить' aria-label='Удалить' data-src='")
                .append(encodedSource).append("' data-id='").append(encodedId)
                .append("' data-name='").append(htmlEscape(item.name)).append("'>×</button></div>")
        }
        append("</div>")
        if (items.size > shown.size) {
            append("<p class=\"empty\"><span class=\"empty-icon\">…</span><span>Показаны последние ")
                .append(shown.size).append(" из ").append(items.size).append(".</span></p>")
        }
        append("</section>")
    }

    /**
     * Удаление элемента галереи. Принятые файлы уходят в корзину, а фото
     * и видео телефона — через MediaStore (с подтверждением на Android 11+).
     */
    private fun deleteMedia(request: Request, output: OutputStream) {
        val source = mediaSources().firstOrNull { it.key == request.query["src"] }
        if (source == null) {
            notFound(output)
            return
        }
        val id = request.query["id"]
        val item = try {
            source.items().firstOrNull { it.id == id }
        } catch (e: Exception) {
            logger("HTTP: не удалось прочитать источник ${source.key}: ${e.message}")
            null
        }
        if (item == null) {
            notFound(output)
            return
        }

        when (source.delete(item)) {
            MediaDeleteResult.DELETED -> {
                logger("HTTP: удалён элемент галереи ${item.name}")
                writeResponse(output, 200, "OK", CONTENT_TYPE_JSON, "{\"ok\":true}".toByteArray())
            }

            MediaDeleteResult.NEEDS_CONSENT -> writeResponse(
                output, 403, "Forbidden", CONTENT_TYPE_JSON,
                ("{\"ok\":false,\"needsConsent\":true,\"error\":\"" +
                    jsonEscape("Android просит подтвердить удаление на телефоне") + "\"}").toByteArray()
            )

            MediaDeleteResult.NOT_FOUND -> notFound(output)

            MediaDeleteResult.NOT_SUPPORTED -> writeResponse(
                output, 405, "Method Not Allowed", CONTENT_TYPE_JSON,
                errorJson("Этот источник не поддерживает удаление").toByteArray()
            )

            MediaDeleteResult.FAILED -> writeResponse(
                output, 500, "Internal Server Error", CONTENT_TYPE_JSON,
                errorJson("Не удалось удалить элемент").toByteArray()
            )
        }
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

    private fun filesJson(entries: List<StorageEntry>, currentPath: String): String = buildString {
        append("{\"path\":\"").append(jsonEscape(currentPath)).append("\",\"files\":[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append("{\"name\":\"").append(jsonEscape(entry.name)).append("\",")
            append("\"path\":\"").append(jsonEscape(entry.path)).append("\",")
            append("\"dir\":").append(entry.isDirectory).append(',')
            append("\"size\":").append(entry.size).append(',')
            append("\"modified\":").append(entry.modified).append('}')
        }
        append("]}")
    }

    private fun foldersJson(): String {
        val root = storageRoot()
        val folders = if (root == null) emptyList() else ReceivedStorage { root }.folders()
        return buildString {
            append("{\"folders\":[")
            folders.forEachIndexed { index, folder ->
                if (index > 0) append(',')
                append('\"').append(jsonEscape(folder)).append('\"')
            }
            append("]}")
        }
    }

    /**
     * Содержимое папки для страницы и API. `null` — путь недопустим или это
     * не папка (HTTP ответит 404).
     *
     * Без [storageRoot] остаётся старое плоское поведение: только [files],
     * только корень.
     */
    private fun entriesFor(path: String?): List<StorageEntry>? {
        val normalized = StoragePaths.normalize(path) ?: return null
        val root = storageRoot()
        if (root == null) {
            if (normalized.isNotEmpty()) return null
            return files().map { file ->
                StorageEntry(
                    name = file.name,
                    path = file.name,
                    isDirectory = false,
                    size = file.length(),
                    modified = file.lastModified(),
                )
            }
        }

        val storage = ReceivedStorage { root }
        val directory = storage.resolve(normalized) ?: return null
        if (!directory.isDirectory) return null

        val allowed = allowedRelatives(root)
        return storage.entries(normalized).filter { entry ->
            entry.isDirectory || entry.path in allowed
        }
    }

    // --- Папки: создание, переименование, перемещение, ZIP --------------------

    private fun createFolder(request: Request, output: OutputStream) {
        val parent = request.query["path"] ?: ""
        val name = request.query["name"].orEmpty()
        val created = storageRoot()?.let { ReceivedStorage(storageRoot).createFolder(parent, name) }
        if (created == null) {
            writeResponse(
                output, 400, "Bad Request", CONTENT_TYPE_JSON,
                errorJson("Не удалось создать папку: проверьте имя и путь").toByteArray()
            )
            return
        }
        logger("HTTP: создана папка ${created.path}")
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            ("{\"ok\":true,\"path\":\"${jsonEscape(created.path)}\"," +
                "\"name\":\"${jsonEscape(created.name)}\"}").toByteArray()
        )
    }

    private fun renameEntry(request: Request, output: OutputStream) {
        val path = request.query["path"].orEmpty()
        val name = request.query["name"].orEmpty()
        val renamed = storageRoot()?.let { ReceivedStorage(storageRoot).rename(path, name) }
        if (renamed == null) {
            writeResponse(
                output, 409, "Conflict", CONTENT_TYPE_JSON,
                errorJson("Не удалось переименовать: имя занято или путь неверный").toByteArray()
            )
            return
        }
        logger("HTTP: переименовано в ${renamed.path}")
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            ("{\"ok\":true,\"path\":\"${jsonEscape(renamed.path)}\"," +
                "\"name\":\"${jsonEscape(renamed.name)}\"}").toByteArray()
        )
    }

    private fun moveEntry(request: Request, output: OutputStream) {
        val path = request.query["path"].orEmpty()
        val target = request.query["to"] ?: ""
        val moved = storageRoot()?.let { ReceivedStorage(storageRoot).move(path, target) }
        if (moved == null) {
            writeResponse(
                output, 409, "Conflict", CONTENT_TYPE_JSON,
                errorJson("Не удалось переместить: имя занято, папка недоступна или это она сама").toByteArray()
            )
            return
        }
        logger("HTTP: перемещено в ${moved.path}")
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            "{\"ok\":true,\"path\":\"${jsonEscape(moved.path)}\"}".toByteArray()
        )
    }

    /** Удаляет несколько путей за одно соединение (выделение на странице). */
    private fun deleteMany(request: Request, body: InputStream, output: OutputStream) {
        val storage = storageRoot()?.let { ReceivedStorage(storageRoot) }
        if (storage == null) {
            notFound(output)
            return
        }

        val length = request.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length < 0 || length > MAX_BULK_DELETE_BYTES) {
            writeResponse(
                output, 400, "Bad Request", CONTENT_TYPE_JSON,
                errorJson("Список на удаление пуст или слишком большой").toByteArray()
            )
            return
        }

        val text = try {
            readLimited(body, length.toInt())
        } catch (e: Exception) {
            logger("HTTP: не удалось прочитать список на удаление: ${e.message}")
            ""
        }
        val paths = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        var deleted = 0
        var failed = 0
        val trashIds = ArrayList<String>()
        for (path in paths) {
            val relative = StoragePaths.normalize(path)
            val entry = if (relative == null || relative.isEmpty()) null else storage.entry(relative)
            if (relative == null || relative.isEmpty() || entry == null) {
                failed++
                continue
            }
            val trashed = if (isAllowedForDelete(relative, entry.isDirectory)) {
                storage.trash(relative)
            } else {
                null
            }
            if (trashed == null) {
                failed++
            } else {
                trashIds.add(trashed.id)
                deleted++
            }
        }

        logger("HTTP: удалено $deleted, не удалось $failed")
        val trashJson = trashIds.joinToString(",") { "\"${jsonEscape(it)}\"" }
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            ("{\"ok\":${failed == 0},\"deleted\":$deleted,\"failed\":$failed," +
                "\"trash\":[$trashJson]}").toByteArray()
        )
    }

    /**
     * Возвращает элементы корзины на исходные места. Id приходят параметром
     * `id` (одиночная отмена) или списком в теле (отмена группового удаления).
     */
    private fun restoreEntries(request: Request, body: InputStream, output: OutputStream) {
        val storage = storageRoot()?.let { ReceivedStorage { it } } ?: run {
            notFound(output)
            return
        }

        val ids = ArrayList<String>()
        request.query["id"]?.trim()?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        if (ids.isEmpty()) {
            val length = request.headers["content-length"]?.toLongOrNull() ?: 0L
            if (length > 0) {
                if (length > MAX_BULK_DELETE_BYTES) {
                    writeResponse(
                        output, 400, "Bad Request", CONTENT_TYPE_JSON,
                        errorJson("Список на восстановление слишком большой").toByteArray()
                    )
                    return
                }
                val text = try {
                    readLimited(body, length.toInt())
                } catch (_: Exception) {
                    ""
                }
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { ids.add(it) }
            }
        }

        val visible = trashEntries().associateBy { it.id }
        var restored = 0
        var failed = 0
        for (id in ids.distinct()) {
            val entry = visible[id]
            if (entry != null && storage.restore(id) != null) restored++ else failed++
        }

        logger("HTTP: восстановлено $restored, не удалось $failed")
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            "{\"ok\":${failed == 0},\"restored\":$restored,\"failed\":$failed}".toByteArray()
        )
    }

    /** Очищает корзину: элементы уже удалены пользователем окончательно. */
    private fun emptyTrash(output: OutputStream) {
        val storage = storageRoot()?.let { ReceivedStorage { it } } ?: run {
            notFound(output)
            return
        }
        val deleted = trashEntries().count { entry -> storage.deleteFromTrash(entry.id) }
        logger("HTTP: корзина очищена, удалено $deleted")
        writeResponse(
            output, 200, "OK", CONTENT_TYPE_JSON,
            "{\"ok\":true,\"deleted\":$deleted}".toByteArray()
        )
    }

    /** Удаляет один элемент корзины навсегда. */
    private fun deleteTrashEntry(id: String, output: OutputStream) {
        val storage = storageRoot()?.let { ReceivedStorage { it } } ?: run {
            notFound(output)
            return
        }
        val visible = trashEntries().any { it.id == id }
        if (!visible || !storage.deleteFromTrash(id)) {
            writeResponse(
                output, 404, "Not Found", CONTENT_TYPE_JSON,
                errorJson("Этого элемента нет в корзине").toByteArray()
            )
            return
        }
        writeResponse(output, 200, "OK", CONTENT_TYPE_JSON, "{\"ok\":true}".toByteArray())
    }

    private fun readLimitedBytes(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            remaining -= read
        }
        return buffer.toByteArray()
    }

    private fun readLimited(input: InputStream, limit: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            remaining -= read
        }
        return String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    /**
     * Скачивает папку одним ZIP-архивом (как в File Browser и Nextcloud).
     * Длина ответа неизвестна, поэтому тело ограничивается закрытием соединения;
     * все ответы сервера и так идут с `Connection: close`.
     */
    private fun serveZip(path: String?, output: OutputStream, headOnly: Boolean) {
        val root = storageRoot()
        if (root == null) {
            notFound(output)
            return
        }
        val storage = ReceivedStorage { root }
        val normalized = StoragePaths.normalize(path) ?: run { notFound(output); return }
        val directory = storage.resolve(normalized) ?: run { notFound(output); return }
        if (!directory.isDirectory) {
            notFound(output)
            return
        }

        val allowed = allowedRelatives(root)
        val archiveName = if (normalized.isEmpty()) "landrop.zip" else "${directory.name}.zip"
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Connection: close\r\n")
            append("Content-Type: application/zip\r\n")
            append("Content-Disposition: attachment; filename=\"").append(asciiFileName(archiveName))
                .append("\"; filename*=UTF-8''").append(encodePathSegment(archiveName)).append("\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Cache-Control: no-store\r\n\r\n")
        }

        try {
            output.write(headers.toByteArray(StandardCharsets.UTF_8))
            if (headOnly) {
                output.flush()
                return
            }
            val zip = ZipOutputStream(output)
            addToZip(zip, storage, normalized, allowed, depth = 0)
            zip.finish()
            zip.flush()
            output.flush()
        } catch (e: IOException) {
            logger("HTTP: ZIP для $normalized прерван: ${e.message}")
        }
    }

    private fun addToZip(
        zip: ZipOutputStream,
        storage: ReceivedStorage,
        archiveBase: String,
        allowed: Set<String>,
        depth: Int,
    ) {
        // Содержимое кладём от корня выбранной папки: «photos/2026/a.jpg»
        // внутри photos.zip становится «2026/a.jpg».
        fun prefixFor(folder: String): String = when {
            folder.isEmpty() -> ""
            folder == archiveBase -> ""
            archiveBase.isEmpty() -> "$folder/"
            else -> folder.removePrefix("$archiveBase/") + "/"
        }

        fun walk(folder: String, level: Int) {
            if (level > StoragePaths.MAX_DEPTH) return
            val prefix = prefixFor(folder)
            for (entry in storage.entries(folder)) {
                if (entry.isDirectory) {
                    // Пустая папка тоже должна попасть в архив.
                    zip.putNextEntry(ZipEntry(prefix + entry.name + "/"))
                    zip.closeEntry()
                    walk(entry.path, level + 1)
                    continue
                }
                if (entry.path !in allowed) continue
                val file = storage.resolve(entry.path) ?: continue
                zip.putNextEntry(ZipEntry(prefix + entry.name))
                file.inputStream().use { input -> input.copyTo(zip, Protocol.BUFFER_SIZE) }
                zip.closeEntry()
            }
        }

        walk(archiveBase, depth)
    }

    /** Процентное кодирование относительного пути: `/` остаётся разделителем. */
    private fun encodeRelativePath(path: String): String =
        path.split('/').joinToString("/") { encodePathSegment(it) }

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
        private const val BREADCRUMBS_MARKER = "<!--BREADCRUMBS-->"
        private const val CURRENT_PATH_MARKER = "<!--CURRENT_PATH-->"
        private const val ZIP_LINK_MARKER = "<!--ZIP_LINK-->"
        private const val TRASH_LINK_MARKER = "<!--TRASH_LINK-->"
        private const val TRASH_ITEMS_MARKER = "<!--TRASH_ITEMS-->"
        private const val TRASH_CLEAR_MARKER = "<!--TRASH_CLEAR-->"
        private const val TRASH_COUNT_MARKER = "<!--TRASH_COUNT-->"

        /** Видеотека: страница, JSON и сохранение описания. */
        private const val LIBRARY_PATH = "/library"
        private const val LIBRARY_API_PATH = "/api/library"
        private const val LIBRARY_SAVE_PATH = "/library/save"
        private const val LIBRARY_RESET_PATH = "/library/reset"
        private const val LIBRARY_ITEMS_MARKER = "<!--LIBRARY_ITEMS-->"
        private const val LIBRARY_COUNT_MARKER = "<!--LIBRARY_COUNT-->"
        private const val LIBRARY_DATA_MARKER = "<!--LIBRARY_DATA-->"

        /** Сколько байт описания принимаем: больше лимита MediaLibrary всё равно обрежет. */
        private const val MAX_LIBRARY_BODY_BYTES = 64 * 1024

        /** Корзина: страница, JSON и служебные маршруты. */
        private const val TRASH_PATH = "/trash"
        private const val TRASH_PATH_PREFIX = "/trash/"
        private const val TRASH_API_PATH = "/api/trash"
        private const val TRASH_EMPTY_PATH = "/trash/empty"

        private const val ALLOWED_METHODS = "GET, HEAD, POST, PUT, DELETE, OPTIONS"

        /** Сколько байт списка путей принимаем на массовое удаление. */
        private const val MAX_BULK_DELETE_BYTES = 64 * 1024

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
