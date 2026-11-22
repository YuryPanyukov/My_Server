package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * HTTP-эндпоинты видеотеки и корзины: JSON-API (`/api/library`, `/api/trash`),
 * страницы `/library` и `/trash` и действия `/library/save|reset`, `/restore`,
 * `/trash/empty`, `DELETE /trash/<id>`. Реальные сокеты на loopback, без Android API.
 */
class HttpLibraryTrashTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("http-library-trash").toFile()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        dir.deleteRecursively()
    }

    private fun startServer(fileLister: (() -> List<File>)? = null): FileReceiverServer {
        val storage = ReceivedStorage { dir }
        val created = FileReceiverServer(
            port = 0,
            bindAddress = loopback,
            socketTimeoutMs = 5_000,
            saver = { name, size, input -> FileSaver.writeTo(dir, name, size, input) },
            fileLister = fileLister ?: { storage.allFiles() },
            storageRoot = { dir },
            folderSaver = { name, size, target, input -> FileSaver.writeTo(target, name, size, input) },
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

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encPath(path: String): String = path.split('/').joinToString("/") { enc(it) }

    private fun get(target: String): ByteArray = rawExchange(buildRequest("GET", target))

    private fun post(target: String, body: ByteArray = ByteArray(0)): ByteArray =
        rawExchange(buildRequest("POST", target, body))

    private fun upload(name: String, content: String, path: String = ""): ByteArray =
        rawExchange(
            buildRequest(
                "POST",
                "/upload?name=${enc(name)}&path=${encPath(path)}",
                content.toByteArray(StandardCharsets.UTF_8),
            )
        )

    private fun delete(target: String): ByteArray = rawExchange(buildRequest("DELETE", target))

    /** id элемента корзины из JSON-ответа одиночного удаления. */
    private fun trashIdOf(response: ByteArray): String {
        val text = bodyOf(response)
        return requireNotNull(
            text.substringAfter("\"trash\":\"", "").substringBefore('"').takeIf { it.isNotEmpty() }
        )
    }

    /** Все id из ответа `/api/trash`. */
    private fun trashIdsOfApi(): List<String> =
        Regex("\"id\":\"([^\"]+)\"").findAll(bodyOf(get("/api/trash"))).map { it.groupValues[1] }.toList()

    /** Сохраняет описание: путь и заголовок в URL, текст — в теле. */
    private fun saveDescription(path: String, title: String, description: String): ByteArray =
        rawExchange(
            buildRequest(
                "POST",
                "/library/save?path=${encPath(path)}&title=${enc(title)}",
                description.toByteArray(StandardCharsets.UTF_8),
                mapOf("Content-Type" to "text/plain; charset=utf-8"),
            )
        )

    // --- Корзина: JSON-API ---------------------------------------------------

    @Test
    fun `api trash is empty when nothing was deleted`() {
        startServer()

        assertEquals("{\"items\":[]}", bodyOf(get("/api/trash")).trim())
    }

    @Test
    fun `api trash lists a deleted file with its original path`() {
        startServer()
        post("/folder?path=&name=photos")
        upload("note.txt", "hello", path = "photos")

        val deleted = delete("/files/photos/note.txt")
        assertEquals(200, statusOf(deleted))
        val id = trashIdOf(deleted)

        val json = bodyOf(get("/api/trash"))
        assertTrue("id из ответа удаления", json.contains("\"id\":\"$id\""))
        assertTrue("имя файла", json.contains("\"name\":\"note.txt\""))
        assertTrue("исходный путь", json.contains("\"path\":\"photos/note.txt\""))
        assertTrue("это файл", json.contains("\"directory\":false"))
        assertTrue("размер", json.contains("\"size\":5"))
        assertTrue("время удаления", json.contains("\"deleted\":"))
    }

    @Test
    fun `api trash marks a deleted folder as a directory`() {
        startServer()
        post("/folder?path=&name=photos")
        upload("a.jpg", "jpeg", path = "photos")

        assertEquals(200, statusOf(delete("/files/photos?recursive=1")))

        val json = bodyOf(get("/api/trash"))
        assertTrue("имя папки", json.contains("\"name\":\"photos\""))
        assertTrue("путь папки", json.contains("\"path\":\"photos\""))
        assertTrue("это папка", json.contains("\"directory\":true"))
    }

    @Test
    fun `trash page shows the empty state without a clear button`() {
        startServer()

        val page = bodyOf(get("/trash"))

        assertTrue("пустая корзина", page.contains("Корзина пуста"))
        assertFalse("кнопка очистки не нужна", page.contains("id=\"trash-empty\""))
    }

    // --- Корзина: восстановление --------------------------------------------

    @Test
    fun `restores everything listed by the trash api`() {
        startServer()
        upload("a.txt", "a")
        upload("b.txt", "b")
        post("/delete", "a.txt\nb.txt\n".toByteArray(StandardCharsets.UTF_8))

        val ids = trashIdsOfApi()
        assertEquals(2, ids.size)
        assertFalse(File(dir, "a.txt").exists())

        val restored = post("/restore", ids.joinToString("\n").toByteArray(StandardCharsets.UTF_8))

        assertEquals(200, statusOf(restored))
        assertTrue(bodyOf(restored).contains("\"restored\":2"))
        assertEquals("a", File(dir, "a.txt").readText())
        assertEquals("b", File(dir, "b.txt").readText())
        assertEquals("{\"items\":[]}", bodyOf(get("/api/trash")).trim())
    }

    @Test
    fun `restore puts a deleted file next to the new one with a unique name`() {
        startServer()
        upload("note.txt", "first")
        val id = trashIdOf(delete("/files/note.txt"))
        upload("note.txt", "second")

        val restored = post("/restore?id=$id")

        assertEquals(200, statusOf(restored))
        assertEquals("новый файл не перезаписан", "second", File(dir, "note.txt").readText())
        assertEquals("удалённый вернулся рядом", "first", File(dir, "note (1).txt").readText())
    }

    @Test
    fun `restore without ids reports nothing to do`() {
        startServer()

        val response = post("/restore")

        assertEquals(200, statusOf(response))
        assertTrue(bodyOf(response).contains("\"restored\":0"))
        assertTrue("нечего было восстанавливать", bodyOf(response).contains("\"ok\":true"))
    }

    @Test
    fun `restore counts unknown ids as failures`() {
        startServer()

        val response = post("/restore", "nope\n".toByteArray(StandardCharsets.UTF_8))

        assertEquals(200, statusOf(response))
        assertTrue(bodyOf(response).contains("\"restored\":0"))
        assertTrue(bodyOf(response).contains("\"failed\":1"))
        assertTrue("часть id не найдена", bodyOf(response).contains("\"ok\":false"))
    }

    // --- Корзина: окончательное удаление ------------------------------------

    @Test
    fun `empty trash reports how many entries were removed`() {
        startServer()
        upload("a.txt", "a")
        upload("b.txt", "b")
        post("/delete", "a.txt\nb.txt\n".toByteArray(StandardCharsets.UTF_8))

        val emptied = post("/trash/empty")

        assertEquals(200, statusOf(emptied))
        assertTrue(bodyOf(emptied).contains("\"deleted\":2"))
        assertEquals("корзина пуста", "{\"items\":[]}", bodyOf(get("/api/trash")).trim())
    }

    @Test
    fun `deleting the same trash entry twice answers 404`() {
        startServer()
        upload("gone.txt", "x")
        val id = trashIdOf(delete("/files/gone.txt"))

        assertEquals(200, statusOf(delete("/trash/$id")))
        val again = delete("/trash/$id")
        assertEquals(404, statusOf(again))
        assertFalse("элемента уже нет", bodyOf(again).contains("\"ok\":true"))
    }

    // --- Видеотека: JSON-API -------------------------------------------------

    @Test
    fun `api library is empty when there are no videos`() {
        startServer()
        upload("photo.jpg", "jpeg")

        assertEquals("{\"items\":[]}", bodyOf(get("/api/library")).trim())
    }

    @Test
    fun `api library returns a card with all fields`() {
        startServer()
        upload("trip.mp4", "video")
        saveDescription("trip.mp4", "Море", "Первый день\nи вечер")

        val json = bodyOf(get("/api/library"))

        assertTrue("имя файла", json.contains("\"name\":\"trip.mp4\""))
        assertTrue("путь", json.contains("\"path\":\"trip.mp4\""))
        assertTrue("заголовок", json.contains("\"title\":\"Море\""))
        assertTrue("описание с переводом строки", json.contains("\"description\":\"Первый день\\nи вечер\""))
        assertTrue("размер", json.contains("\"size\":5"))
        assertTrue("время изменения", json.contains("\"modified\":"))
        assertTrue("время правки описания", json.contains("\"updated\":"))
        assertTrue("описание сохранено", json.contains("\"described\":true"))
    }

    @Test
    fun `video without a description is listed as undescribed`() {
        startServer()
        upload("Отпуск 2024.mp4", "video")

        val json = bodyOf(get("/api/library"))

        assertTrue("заголовок из имени файла", json.contains("\"title\":\"Отпуск 2024\""))
        assertTrue(json.contains("\"described\":false"))
    }

    @Test
    fun `api library follows the access filter`() {
        startServer(fileLister = { emptyList() })
        upload("trip.mp4", "video")

        assertEquals("скрытое видео не показываем", "{\"items\":[]}", bodyOf(get("/api/library")).trim())
    }

    @Test
    fun `library escapes quotes and backslashes in title and description`() {
        startServer()
        upload("trip.mp4", "video")
        val title = "Он сказал \"привет\""
        val description = "C:\\Видео\\2024\nвторая строка"
        saveDescription("trip.mp4", title, description)

        val json = bodyOf(get("/api/library"))
        assertTrue("кавычки в заголовке", json.contains("\\\"привет\\\""))
        assertTrue("обратные слэши", json.contains("C:\\\\Видео\\\\2024"))
        assertTrue("перевод строки", json.contains("\\nвторая строка"))

        val page = bodyOf(get("/library"))
        assertTrue("кавычки не ломают разметку", page.contains("&quot;привет&quot;"))
        assertTrue("текст описания на странице", page.contains("C:\\Видео\\2024"))
    }

    // --- Видеотека: сохранение и сброс --------------------------------------

    @Test
    fun `library saves a description for a video inside a folder`() {
        startServer()
        post("/folder?path=&name=photos")
        upload("trip.mp4", "video", path = "photos")

        assertEquals(200, statusOf(saveDescription("photos/trip.mp4", "Море", "Первый день")))

        assertTrue("сайдкар рядом с видео", File(dir, "photos/trip.mp4.library.json").isFile)
        assertTrue(bodyOf(get("/api/library")).contains("\"path\":\"photos/trip.mp4\""))
        assertTrue(bodyOf(get("/library")).contains("Первый день"))
    }

    @Test
    fun `library description follows rename and move`() {
        startServer()
        post("/folder?path=&name=archive")
        upload("trip.mp4", "video")
        saveDescription("trip.mp4", "Море", "Первый день")

        assertEquals(200, statusOf(post("/rename?path=trip.mp4&name=${enc("лето.mp4")}")))
        assertEquals(200, statusOf(post("/move?path=${enc("лето.mp4")}&to=archive")))

        assertTrue("сайдкар переехал", File(dir, "archive/лето.mp4.library.json").isFile)
        assertFalse("старый сайдкар убран", File(dir, "trip.mp4.library.json").exists())
        val json = bodyOf(get("/api/library"))
        assertTrue("описание сохранилось", json.contains("\"title\":\"Море\""))
        assertTrue("новый путь", json.contains("\"path\":\"archive/лето.mp4\""))
    }

    @Test
    fun `library accepts a description in the query when the body is empty`() {
        startServer()
        upload("trip.mp4", "video")

        val response = post(
            "/library/save?path=trip.mp4&title=${enc("Море")}&description=${enc("Тёплый вечер")}"
        )

        assertEquals(200, statusOf(response))
        assertTrue(bodyOf(get("/api/library")).contains("\"description\":\"Тёплый вечер\""))
    }

    @Test
    fun `library rejects a description longer than the limit`() {
        startServer()
        upload("trip.mp4", "video")

        // Длина объявлена заголовком: сервер обязан отказать до чтения тела.
        val request = (
            "POST /library/save?path=trip.mp4&title=${enc("Море")} HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\nContent-Length: 70000\r\nConnection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)

        val response = rawExchange(request)

        assertEquals(413, statusOf(response))
        assertFalse("описание не сохранено", File(dir, "trip.mp4.library.json").exists())
    }

    @Test
    fun `library reset without a saved description stays successful`() {
        startServer()
        upload("trip.mp4", "video")

        val response = post("/library/reset?path=trip.mp4")

        assertEquals(200, statusOf(response))
        assertTrue(bodyOf(response).contains("\"ok\":true"))
    }

    @Test
    fun `library refuses a video outside the storage`() {
        startServer()
        val outside = File(dir.parentFile, "outside.mp4").apply { writeText("video") }

        val response = saveDescription("../outside.mp4", "Хак", "текст")

        assertEquals(404, statusOf(response))
        assertFalse("сайдкар вне хранилища не создаём", File(dir.parentFile, "outside.mp4.library.json").exists())
        outside.delete()
    }
}
