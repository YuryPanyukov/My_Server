package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Просмотр принятых файлов через HTTP: список на странице, JSON-API и
 * отдача содержимого по `/files/<имя>`. Реальные сокеты на loopback.
 */
class HttpFileBrowsingTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("http-files").toFile()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        dir.deleteRecursively()
    }

    private fun startServer(
        saver: (String, Long, InputStream) -> File? = { name, size, input ->
            FileSaver.writeTo(dir, name, size, input)
        },
        thumbnailer: (File) -> ByteArray? = { null },
    ): FileReceiverServer {
        val created = FileReceiverServer(
            port = 0,
            bindAddress = loopback,
            socketTimeoutMs = 5_000,
            saver = saver,
            fileLister = { dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList() },
            fileThumbnailer = thumbnailer,
        )
        created.start()
        server = created
        return created
    }

    private fun buildRequest(
        method: String,
        target: String,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val head = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        return head.toByteArray(StandardCharsets.UTF_8) + body
    }

    private fun rawExchange(request: ByteArray): ByteArray {
        val srv = server ?: error("server is not started")
        Socket(loopback, srv.localPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            return socket.getInputStream().readBytes()
        }
    }

    private fun headersOf(response: ByteArray): String =
        String(response, StandardCharsets.ISO_8859_1).substringBefore("\r\n\r\n")

    private fun bodyOf(response: ByteArray): String =
        String(response, StandardCharsets.UTF_8).substringAfter("\r\n\r\n", "")

    private fun statusOf(response: ByteArray): Int =
        headersOf(response).lineSequence().first().split(' ')[1].toInt()

    private fun upload(name: String, content: ByteArray): ByteArray {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return rawExchange(buildRequest("POST", "/upload?name=$encoded", content))
    }

    private fun get(
        target: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
    ): ByteArray = rawExchange(buildRequest(method, target, headers = headers))

    private fun delete(target: String): ByteArray = rawExchange(buildRequest("DELETE", target))

    private fun encodedPath(name: String): String =
        "/files/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    // --- Список файлов ------------------------------------------------------

    @Test
    fun `page shows empty state without files`() {
        startServer()

        val page = bodyOf(get("/"))

        assertTrue("должно быть сообщение о пустом списке", page.contains("Пока нет файлов"))
    }

    @Test
    fun `page lists received files with links`() {
        startServer()
        upload("photo.txt", "hello".toByteArray())

        val page = bodyOf(get("/"))

        assertTrue("имя файла должно быть в списке", page.contains("photo.txt"))
        assertTrue("должна быть ссылка на просмотр", page.contains("/files/photo.txt"))
        assertTrue("размер должен отображаться", page.contains("5 Б"))
    }

    @Test
    fun `page escapes html in file names`() {
        startServer()
        upload("a&b.txt", "x".toByteArray())

        val page = bodyOf(get("/"))

        assertTrue("символ & должен быть экранирован", page.contains("a&amp;b.txt"))
    }

    @Test
    fun `json api lists files`() {
        startServer()
        upload("one.txt", "12345".toByteArray())
        upload("two.txt", "abc".toByteArray())

        val response = get("/api/files")
        val json = bodyOf(response)

        assertEquals(200, statusOf(response))
        assertTrue("Content-Type должен быть JSON", headersOf(response).contains("application/json"))
        assertTrue(json.contains("\"name\":\"one.txt\""))
        assertTrue(json.contains("\"name\":\"two.txt\""))
        assertTrue(json.contains("\"size\":5"))
        assertTrue(json.contains("\"modified\":"))
    }

    // --- Отдача файлов ------------------------------------------------------

    @Test
    fun `serves text file content`() {
        startServer()
        val content = "Текст для просмотра".toByteArray(StandardCharsets.UTF_8)
        upload("note.txt", content)

        val response = get("/files/note.txt")
        val headers = headersOf(response)

        assertEquals(200, statusOf(response))
        assertTrue("должен быть text/plain", headers.contains("Content-Type: text/plain"))
        assertEquals(content.size.toLong(), bodyOf(response).toByteArray(StandardCharsets.UTF_8).size.toLong())
        assertArrayEquals(content, bodyOf(response).toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun `serves cyrillic name via encoded url`() {
        startServer()
        val content = "привет".toByteArray(StandardCharsets.UTF_8)
        upload("Отчёт 2026.txt", content)

        val response = get(encodedPath("Отчёт 2026.txt"))

        assertEquals(200, statusOf(response))
        assertArrayEquals(content, bodyOf(response).toByteArray(StandardCharsets.UTF_8))
        assertTrue(
            "имя должно передаваться в Content-Disposition",
            headersOf(response).contains("filename*=UTF-8''")
        )
    }

    @Test
    fun `serves png inline with image content type`() {
        startServer()
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )
        upload("image.png", png)

        val response = get("/files/image.png")
        val headers = headersOf(response)

        assertEquals(200, statusOf(response))
        assertTrue(headers.contains("Content-Type: image/png"))
        assertTrue("картинка должна открываться в браузере", headers.contains("Content-Disposition: inline"))
        assertTrue("должен быть запрет на подмену типа", headers.contains("X-Content-Type-Options: nosniff"))
    }

    @Test
    fun `serves html as attachment to avoid script execution`() {
        startServer()
        upload("page.html", "<script>alert(1)</script>".toByteArray())

        val response = get("/files/page.html")

        assertEquals(200, statusOf(response))
        assertTrue(
            "html должен скачиваться, а не выполняться",
            headersOf(response).contains("Content-Disposition: attachment")
        )
    }

    @Test
    fun `unknown file returns 404`() {
        startServer()

        assertEquals(404, statusOf(get("/files/missing.txt")))
    }

    @Test
    fun `cannot read files outside storage via traversal`() {
        startServer()
        val outside = File(dir.parentFile, "outside-secret.txt")
        outside.writeText("secret")

        val response = get("/files/..%2Foutside-secret.txt")

        assertEquals(404, statusOf(response))
        assertTrue("содержимое не должно утечь", !bodyOf(response).contains("secret"))
    }

    @Test
    fun `head request for file returns headers only`() {
        startServer()
        upload("head.txt", "0123456789".toByteArray())

        val response = get("/files/head.txt", method = "HEAD")

        assertEquals(200, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Length: 10"))
        assertEquals("у HEAD не должно быть тела", "", bodyOf(response))
    }

    // --- Удаление -----------------------------------------------------------

    @Test
    fun `deletes file via DELETE`() {
        startServer()
        upload("remove-me.txt", "data".toByteArray())
        assertTrue(File(dir, "remove-me.txt").exists())

        val response = delete("/files/remove-me.txt")

        assertEquals(200, statusOf(response))
        assertTrue("ответ должен подтверждать удаление", bodyOf(response).contains("\"ok\":true"))
        assertTrue("файл должен исчезнуть", !File(dir, "remove-me.txt").exists())
    }

    @Test
    fun `deleted file disappears from page and json`() {
        startServer()
        upload("gone.txt", "data".toByteArray())

        delete("/files/gone.txt")

        assertTrue("в списке не должно быть удалённого файла", !bodyOf(get("/")).contains("gone.txt"))
        assertTrue("в JSON не должно быть удалённого файла", !bodyOf(get("/api/files")).contains("gone.txt"))
    }

    @Test
    fun `delete unknown file returns 404`() {
        startServer()

        assertEquals(404, statusOf(delete("/files/absent.txt")))
    }

    @Test
    fun `delete cannot remove file outside storage`() {
        startServer()
        val outside = File(dir.parentFile, "outside-keep.txt")
        outside.writeText("keep")

        val response = delete("/files/..%2Foutside-keep.txt")

        assertEquals(404, statusOf(response))
        assertTrue("файл вне хранилища должен остаться", outside.exists())
    }

    @Test
    fun `page has delete button`() {
        startServer()
        upload("with-button.txt", "x".toByteArray())

        val page = bodyOf(get("/"))

        assertTrue("должна быть кнопка удаления", page.contains("class=\"del\""))
        assertTrue("кнопка должна знать URL файла", page.contains("data-href=\"/files/with-button.txt\""))
    }

    @Test
    fun `page has search field and searchable names`() {
        startServer()
        upload("Searchable.TXT", "x".toByteArray())

        val page = bodyOf(get("/"))

        assertTrue("должно быть поле поиска", page.contains("id=\"q\""))
        assertTrue("имя должно быть в data-name для фильтра", page.contains("data-name=\"searchable.txt\""))
    }

    // --- Превью и тема ------------------------------------------------------

    @Test
    fun `page shows thumbnail for image files`() {
        startServer()
        upload("photo.png", "png".toByteArray())

        val page = bodyOf(get("/"))

        assertTrue("должно быть превью", page.contains("class=\"fthumb\""))
        assertTrue("превью берётся из /thumb-file", page.contains("/thumb-file?name=photo.png"))
        assertTrue("превью должно грузиться лениво", page.contains("loading=\"lazy\""))
    }

    @Test
    fun `page has no thumbnail for plain text`() {
        startServer()
        upload("note.txt", "x".toByteArray())

        val page = bodyOf(get("/"))

        assertTrue("значок типа должен остаться", page.contains("class=\"ftype\""))
        assertTrue("для текста превью не нужно", !page.contains("class=\"fthumb\""))
    }

    @Test
    fun `serves generated file thumbnail`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        startServer(thumbnailer = { jpeg })
        upload("photo.png", "png".toByteArray())

        val response = get("/thumb-file?name=photo.png")

        assertEquals(200, statusOf(response))
        assertTrue("превью — JPEG", headersOf(response).contains("Content-Type: image/jpeg"))
        // Байты JPEG не декодируются как UTF-8, поэтому тело читаем как ISO-8859-1.
        val rawBody = String(response, StandardCharsets.ISO_8859_1).substringAfter("\r\n\r\n", "")
        assertArrayEquals(jpeg, rawBody.toByteArray(StandardCharsets.ISO_8859_1))
        assertTrue("превью можно кешировать", headersOf(response).contains("Cache-Control: private, max-age=300"))
    }

    @Test
    fun `file thumbnail falls back to original without thumbnailer`() {
        startServer()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        upload("fallback.png", png)

        val response = get("/thumb-file?name=fallback.png")

        assertEquals(200, statusOf(response))
        assertTrue("тип оригинала сохраняется", headersOf(response).contains("Content-Type: image/png"))
    }

    @Test
    fun `unknown file thumbnail returns 404`() {
        startServer()

        assertEquals(404, statusOf(get("/thumb-file?name=missing.png")))
    }

    @Test
    fun `upload queue shows speed and cancel controls`() {
        startServer()

        val page = bodyOf(get("/"))

        assertTrue("сводка по очереди", page.contains("id=\"queue-sum\""))
        assertTrue("отмена всей очереди", page.contains("id=\"cancel-all\""))
        assertTrue("отмена одного файла", page.contains("Отменить загрузку"))
        assertTrue("скорость считается", page.contains("formatSpeed"))
        assertTrue("передачи идут параллельно", page.contains("MAX_PARALLEL = 3"))
    }

    @Test
    fun `aborted upload leaves no partial file`() {
        startServer()
        val srv = server ?: error("server is not started")

        Socket(loopback, srv.localPort).use { socket ->
            socket.soTimeout = 5_000
            val head = "POST /upload?name=aborted.bin HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Length: 1048576\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(StandardCharsets.UTF_8))
                write(ByteArray(1024))
                flush()
                // Клиент передумал: отправка прекращена на середине файла.
                socket.shutdownOutput()
            }
            socket.getInputStream().readBytes()
        }

        assertTrue("недописанный файл не должен остаться", !File(dir, "aborted.bin").exists())
        assertTrue("его не должно быть и в списке", !bodyOf(get("/")).contains("aborted.bin"))
    }

    @Test
    fun `page has light theme toggle`() {
        startServer()

        val page = bodyOf(get("/"))

        assertTrue("кнопка темы", page.contains("id=\"theme-toggle\""))
        assertTrue("светлые токены", page.contains("[data-theme=\"light\"]"))
        assertTrue("тема применяется до отрисовки", page.contains("nas-theme"))
    }

    // --- Range --------------------------------------------------------------

    @Test
    fun `advertises byte ranges`() {
        startServer()
        upload("range.bin", "0123456789".toByteArray())

        val response = get("/files/range.bin")

        assertEquals(200, statusOf(response))
        assertTrue("браузеру нужно знать про Range", headersOf(response).contains("Accept-Ranges: bytes"))
    }

    @Test
    fun `serves requested byte range`() {
        startServer()
        upload("range.bin", "0123456789".toByteArray())

        val response = get("/files/range.bin", headers = mapOf("Range" to "bytes=2-5"))
        val headers = headersOf(response)

        assertEquals(206, statusOf(response))
        assertTrue(headers.contains("Content-Range: bytes 2-5/10"))
        assertTrue(headers.contains("Content-Length: 4"))
        assertEquals("2345", bodyOf(response))
    }

    @Test
    fun `serves open ended byte range`() {
        startServer()
        upload("range.bin", "0123456789".toByteArray())

        val response = get("/files/range.bin", headers = mapOf("Range" to "bytes=5-"))

        assertEquals(206, statusOf(response))
        assertEquals("56789", bodyOf(response))
        assertTrue(headersOf(response).contains("Content-Range: bytes 5-9/10"))
    }

    @Test
    fun `serves suffix byte range`() {
        startServer()
        upload("range.bin", "0123456789".toByteArray())

        val response = get("/files/range.bin", headers = mapOf("Range" to "bytes=-3"))

        assertEquals(206, statusOf(response))
        assertEquals("789", bodyOf(response))
        assertTrue(headersOf(response).contains("Content-Range: bytes 7-9/10"))
    }

    @Test
    fun `range past the end returns 416`() {
        startServer()
        upload("range.bin", "0123456789".toByteArray())

        val response = get("/files/range.bin", headers = mapOf("Range" to "bytes=100-200"))

        assertEquals(416, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Range: bytes */10"))
    }

    @Test
    fun `serves file larger than one buffer`() {
        startServer()
        val content = ByteArray(Protocol.BUFFER_SIZE * 2 + 17) { (it % 251).toByte() }
        upload("large.bin", content)

        val response = get("/files/large.bin")

        assertEquals(200, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Length: ${content.size}"))
    }
}
