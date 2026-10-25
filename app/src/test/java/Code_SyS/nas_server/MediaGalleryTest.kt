package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Галерея фото и видео в браузере: страница, JSON-API, оригиналы с `Range`
 * и JPEG-превью. Источники подменяются фейком, поэтому Android-медиатека
 * (и разрешения) для проверки не нужны — тесты идут на loopback.
 */
class MediaGalleryTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private class FakeEntry(
        val item: MediaItem,
        val data: ByteArray,
        val thumbnail: ByteArray?,
    )

    private class FakeSource(
        override val key: String,
        override val title: String,
        private val entries: List<FakeEntry>,
    ) : MediaSource {
        override fun items(): List<MediaItem> = entries.map { it.item }

        override fun open(item: MediaItem): InputStream? =
            entries.firstOrNull { it.item.id == item.id }?.let { ByteArrayInputStream(it.data) }

        override fun thumbnail(item: MediaItem): ByteArray? =
            entries.firstOrNull { it.item.id == item.id }?.thumbnail
    }

    private val photoBytes = "PHOTO-CONTENT".toByteArray()
    private val videoBytes = "0123456789".toByteArray() // удобно для Range
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    private val deviceSource = FakeSource(
        key = HttpRequestHandler.DEVICE_SOURCE_KEY,
        title = "Фото и видео телефона",
        entries = listOf(
            FakeEntry(
                MediaItem("image/7", "IMG_0007.jpg", photoBytes.size.toLong(), 1_700_000_000_000, "image/jpeg", MediaItem.Kind.IMAGE),
                photoBytes,
                jpegBytes,
            ),
            FakeEntry(
                MediaItem("video/9", "VID_0009.mp4", videoBytes.size.toLong(), 1_700_000_100_000, "video/mp4", MediaItem.Kind.VIDEO),
                videoBytes,
                jpegBytes,
            ),
            FakeEntry(
                MediaItem("image/11", "notes.txt", 5, 1_700_000_200_000, "text/plain", MediaItem.Kind.OTHER),
                "hello".toByteArray(),
                null,
            ),
            // Некоторые провайдеры не знают размер файла (SIZE = 0).
            FakeEntry(
                MediaItem("image/12", "zero.jpg", 0, 1_700_000_300_000, "image/jpeg", MediaItem.Kind.IMAGE),
                "ZERO".toByteArray(),
                null,
            ),
        ),
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("media-gallery").toFile()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        dir.deleteRecursively()
    }

    /** Второй источник — как «Принятые», чтобы проверить фильтр по `src`. */
    private val receivedSource = FakeSource(
        key = "received",
        title = "Принятые",
        entries = listOf(
            FakeEntry(
                MediaItem("photo.png", "photo.png", photoBytes.size.toLong(), 1_700_000_400_000, "image/png", MediaItem.Kind.IMAGE),
                photoBytes,
                jpegBytes,
            ),
        ),
    )

    private fun startServer(sources: List<MediaSource> = listOf(deviceSource, receivedSource)): FileReceiverServer {
        val created = FileReceiverServer(
            port = 0,
            bindAddress = loopback,
            socketTimeoutMs = 5_000,
            saver = { name, size, input -> FileSaver.writeTo(dir, name, size, input) },
            mediaSources = { sources },
        )
        created.start()
        server = created
        return created
    }

    private fun request(
        target: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val srv = server ?: error("server is not started")
        val head = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        Socket(loopback, srv.localPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(head.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            return socket.getInputStream().readBytes()
        }
    }

    private fun headersOf(response: ByteArray): String =
        String(response, StandardCharsets.ISO_8859_1).substringBefore("\r\n\r\n")

    private fun bodyOf(response: ByteArray): ByteArray {
        val separator = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        val index = indexOf(response, separator)
        return if (index < 0) ByteArray(0) else response.copyOfRange(index + separator.size, response.size)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun statusOf(response: ByteArray): Int =
        headersOf(response).lineSequence().first().split(' ')[1].toInt()

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun mediaUrl(id: String, source: String = "device"): String =
        "/media?src=${encode(source)}&id=${encode(id)}"

    private fun thumbUrl(id: String, source: String = "device"): String =
        "/thumb?src=${encode(source)}&id=${encode(id)}"

    private fun viewerUrl(id: String, source: String = "device"): String =
        "/view?src=${encode(source)}&id=${encode(id)}"

    // --- Страница галереи ---------------------------------------------------

    @Test
    fun `gallery page shows photo and video cards`() {
        startServer()

        val response = request("/gallery")
        val body = String(bodyOf(response), StandardCharsets.UTF_8)

        assertEquals(200, statusOf(response))
        assertTrue("должен быть HTML", headersOf(response).contains("Content-Type: text/html"))
        assertTrue("должен быть заголовок источника: $body", body.contains("Фото и видео телефона"))
        assertTrue("фото должно быть картинкой", body.contains(thumbUrl("image/7").replace("&", "&amp;")))
        assertTrue("видео должно быть картинкой-кадром", body.contains(thumbUrl("video/9").replace("&", "&amp;")))
        assertTrue("имя файла должно быть подписано", body.contains("IMG_0007.jpg"))
        assertTrue("у видео должен быть значок воспроизведения", body.contains("badge"))
        assertTrue(
            "карточка открывает страницу просмотра",
            body.contains(viewerUrl("video/9").replace("&", "&amp;")),
        )
    }

    @Test
    fun `gallery page marks non media files as placeholder`() {
        startServer()

        val body = String(bodyOf(request("/gallery")), StandardCharsets.UTF_8)

        // Для txt нет превью: карточка без <img>, но ссылка на файл есть.
        val cardStart = body.indexOf("notes.txt")
        assertTrue("карточка txt должна быть", cardStart > 0)
        assertFalse("у txt не должно быть превью", body.contains(thumbUrl("image/11").replace("&", "&amp;")))
        assertTrue(
            "txt всё равно открывается в просмотрщике",
            body.contains(viewerUrl("image/11").replace("&", "&amp;")),
        )
    }

    @Test
    fun `gallery page has link back to upload page`() {
        startServer()

        val body = String(bodyOf(request("/gallery")), StandardCharsets.UTF_8)

        assertTrue("нужна ссылка на загрузку", body.contains("href=\"/\""))
    }

    @Test
    fun `upload page links to gallery`() {
        startServer()

        val body = String(bodyOf(request("/")), StandardCharsets.UTF_8)

        assertTrue("со страницы загрузки нужен переход в галерею", body.contains("href=\"/gallery\""))
    }

    @Test
    fun `gallery and viewer share the light theme toggle`() {
        startServer()

        val gallery = String(bodyOf(request("/gallery")), StandardCharsets.UTF_8)
        val viewer = String(bodyOf(request(viewerUrl("image/7"))), StandardCharsets.UTF_8)

        assertTrue("переключатель в галерее", gallery.contains("id=\"theme-toggle\""))
        assertTrue("светлые токены в галерее", gallery.contains("[data-theme=\"light\"]"))
        assertTrue("переключатель в просмотрщике", viewer.contains("id=\"theme-toggle\""))
        assertTrue("светлые токены в просмотрщике", viewer.contains("[data-theme=\"light\"]"))
    }

    // --- Просмотр и листание ------------------------------------------------

    @Test
    fun `viewer page renders navigation controls and swipe handling`() {
        startServer()

        val response = request(viewerUrl("video/9"))
        val body = String(bodyOf(response), StandardCharsets.UTF_8)

        assertEquals(200, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Type: text/html"))
        assertTrue("возврат в галерею", body.contains("href=\"/gallery\""))
        assertTrue("кнопка «предыдущий»", body.contains("id=\"prev\""))
        assertTrue("кнопка «следующий»", body.contains("id=\"next\""))
        assertTrue("стрелки клавиатуры", body.contains("ArrowLeft") && body.contains("ArrowRight"))
        assertTrue("свайпы", body.contains("touchstart") && body.contains("touchend"))
        assertTrue("имя файла должно быть в заголовке", body.contains("VID_0009.mp4"))
        assertTrue("текущий элемент передаётся в скрипт", body.contains("\"video/9\""))
        assertTrue("список берётся у источника", body.contains("/api/media?src="))
    }

    @Test
    fun `viewer page shows photo thumb first and full size after`() {
        startServer()

        val body = String(bodyOf(request(viewerUrl("image/7"))), StandardCharsets.UTF_8)

        assertTrue("миниатюра для мгновенного показа", body.contains("/thumb?src="))
        assertTrue("оригинал подгружается следом", body.contains("/media?src="))
        assertTrue("имя фото", body.contains("IMG_0007.jpg"))
        // Миниатюра маленькая (сотни пикселей): без object-fit она висела бы
        // в центре экрана размером с почтовую марку до загрузки оригинала.
        assertTrue(
            "кадр занимает всю сцену без смены размера",
            body.contains("object-fit: contain") && body.contains("#stage img, #stage video {"),
        )
    }

    @Test
    fun `viewer page rejects unknown item`() {
        startServer()

        assertEquals(404, statusOf(request(viewerUrl("image/999"))))
    }

    @Test
    fun `viewer page rejects unknown source`() {
        startServer()

        assertEquals(404, statusOf(request(viewerUrl("image/7", source = "nope"))))
    }

    @Test
    fun `viewer page escapes file name injected into script`() {
        val evil = "</script><script>alert(1)</script>.jpg"
        val evilSource = FakeSource(
            key = "received",
            title = "Принятые",
            entries = listOf(
                FakeEntry(
                    MediaItem(evil, evil, 3, 1, "image/jpeg", MediaItem.Kind.IMAGE),
                    photoBytes,
                    null,
                ),
            ),
        )
        startServer(listOf(evilSource))

        val response = request(viewerUrl(evil, source = "received"))
        val body = String(bodyOf(response), StandardCharsets.UTF_8)

        assertEquals(200, statusOf(response))
        assertFalse("имя не должно разрывать тег script", body.contains("</script><script>"))
        assertTrue("имя экранировано в разметке", body.contains("&lt;/script&gt;"))
    }

    // --- JSON-API -----------------------------------------------------------

    @Test
    fun `media api lists items with urls and kinds`() {
        startServer()

        val response = request("/api/media")
        val json = String(bodyOf(response), StandardCharsets.UTF_8)

        assertEquals(200, statusOf(response))
        assertTrue("источник должен быть в ответе", json.contains("\"key\":\"device\""))
        assertTrue(json.contains("\"id\":\"image/7\""))
        assertTrue(json.contains("\"kind\":\"image\""))
        assertTrue(json.contains("\"kind\":\"video\""))
        assertTrue(json.contains("\"mime\":\"video/mp4\""))
        assertTrue("ссылка на оригинал", json.contains("/media?src=device&id=image"))
    }

    // --- Оригиналы и Range --------------------------------------------------

    @Test
    fun `media api filters items by source`() {
        startServer()

        val body = String(bodyOf(request("/api/media?src=device")), StandardCharsets.UTF_8)

        assertTrue(body.contains("\"key\":\"device\""))
        assertFalse("чужой источник не должен попадать в список", body.contains("\"key\":\"received\""))
        assertFalse("файлы другого источника", body.contains("photo.png"))
    }

    @Test
    fun `media api returns all sources without filter`() {
        startServer()

        val body = String(bodyOf(request("/api/media")), StandardCharsets.UTF_8)

        assertTrue(body.contains("\"key\":\"device\""))
        assertTrue(body.contains("\"key\":\"received\""))
    }

    @Test
    fun `serves full media content inline`() {
        startServer()

        val response = request(mediaUrl("image/7"))

        assertEquals(200, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Type: image/jpeg"))
        assertTrue("фото должно открываться в браузере", headersOf(response).contains("Content-Disposition: inline"))
        assertTrue("размер должен быть известен", headersOf(response).contains("Content-Length: ${photoBytes.size}"))
        assertArrayEquals(photoBytes, bodyOf(response))
    }

    @Test
    fun `serves media range for video seeking`() {
        startServer()

        val response = request(mediaUrl("video/9"), headers = mapOf("Range" to "bytes=2-5"))

        assertEquals(206, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Range: bytes 2-5/10"))
        assertTrue(headersOf(response).contains("Content-Length: 4"))
        assertArrayEquals("2345".toByteArray(), bodyOf(response))
    }

    @Test
    fun `serves suffix range for media`() {
        startServer()

        val response = request(mediaUrl("video/9"), headers = mapOf("Range" to "bytes=-3"))

        assertEquals(206, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Range: bytes 7-9/10"))
        assertArrayEquals("789".toByteArray(), bodyOf(response))
    }

    @Test
    fun `head request for media has no body`() {
        startServer()

        val response = request(mediaUrl("video/9"), method = "HEAD")

        assertEquals(200, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Length: 10"))
        assertEquals("у HEAD не должно быть тела", 0, bodyOf(response).size)
    }

    @Test
    fun `serves media with unknown size without content length`() {
        startServer()

        val response = request(mediaUrl("image/12"))

        assertEquals(200, statusOf(response))
        assertFalse("для неизвестного размера Content-Length не шлём", headersOf(response).contains("Content-Length"))
        assertArrayEquals("ZERO".toByteArray(), bodyOf(response))
    }

    @Test
    fun `unknown media id returns 404`() {
        startServer()

        assertEquals(404, statusOf(request(mediaUrl("image/999"))))
    }

    @Test
    fun `unknown media source returns 404`() {
        startServer()

        assertEquals(404, statusOf(request(mediaUrl("image/7", source = "other"))))
    }

    // --- Превью -------------------------------------------------------------

    @Test
    fun `serves jpeg thumbnail`() {
        startServer()

        val response = request(thumbUrl("video/9"))

        assertEquals(200, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Type: image/jpeg"))
        assertArrayEquals(jpegBytes, bodyOf(response))
    }

    @Test
    fun `thumbnail falls back to original when source has no preview`() {
        startServer()

        val response = request(thumbUrl("image/11"))

        assertEquals(200, statusOf(response))
        assertTrue(headersOf(response).contains("Content-Type: text/plain"))
        assertArrayEquals("hello".toByteArray(), bodyOf(response))
    }

    // --- Источник принятых файлов ------------------------------------------

    @Test
    fun `received media source exposes saved files with types`() {
        File(dir, "clip.mp4").writeBytes(videoBytes)
        File(dir, "shot.png").writeBytes(photoBytes)
        File(dir, "doc.pdf").writeBytes(photoBytes)
        val source = ReceivedMediaSource(
            files = { dir.listFiles()?.sortedBy { it.name } ?: emptyList() },
            thumbnailer = { file -> if (file.name.endsWith(".png")) jpegBytes else null },
        )

        val items = source.items().associateBy { it.name }

        assertEquals(MediaItem.Kind.VIDEO, items.getValue("clip.mp4").kind)
        assertEquals(MediaItem.Kind.IMAGE, items.getValue("shot.png").kind)
        assertEquals(MediaItem.Kind.OTHER, items.getValue("doc.pdf").kind)
        assertEquals("video/mp4", items.getValue("clip.mp4").mimeType)
        assertEquals(videoBytes.size.toLong(), items.getValue("clip.mp4").size)

        val shot = items.getValue("shot.png")
        assertArrayEquals(photoBytes, source.open(shot)!!.use { it.readBytes() })
        assertArrayEquals(jpegBytes, source.thumbnail(shot))
        assertEquals(null, source.thumbnail(items.getValue("doc.pdf")))
    }
}
