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
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Папки в HTTP-интерфейсе: навигация, загрузка внутрь папки, создание,
 * переименование, перемещение, массовое удаление и ZIP. Реальные сокеты
 * на loopback, без Android API.
 */
class HttpFolderTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("http-folders").toFile()
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

    private fun rawBodyOf(response: ByteArray): ByteArray {
        val marker = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        var index = -1
        for (i in 0..response.size - marker.size) {
            var matches = true
            for (j in marker.indices) {
                if (response[i + j] != marker[j]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                index = i + marker.size
                break
            }
        }
        return if (index < 0) ByteArray(0) else response.copyOfRange(index, response.size)
    }

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

    // --- Навигация и загрузка внутрь папки ----------------------------------

    @Test
    fun `creates folder and navigates into it`() {
        startServer()

        val created = post("/folder?path=&name=${enc("Отпуск")}")

        assertEquals(200, statusOf(created))
        assertTrue(bodyOf(created).contains("\"ok\":true"))
        assertTrue(File(dir, "Отпуск").isDirectory)

        val page = bodyOf(get("/"))
        assertTrue("папка должна быть в списке", page.contains("Отпуск"))
        assertTrue("ссылка ведёт внутрь папки", page.contains("/?path=%D0%9E%D1%82%D0%BF%D1%83%D1%81%D0%BA"))

        val inside = bodyOf(get("/?path=" + enc("Отпуск")))
        assertTrue("внутри папки есть хлебные крошки", inside.contains("class=\"crumb current\""))
        assertEquals(200, statusOf(get("/?path=" + enc("Отпуск"))))
    }

    @Test
    fun `uploads file into selected folder`() {
        startServer()
        post("/folder?path=&name=photos")

        val response = upload("one.txt", "inside", path = "photos")

        assertEquals(200, statusOf(response))
        assertTrue("ответ содержит путь", bodyOf(response).contains("\"path\":\"photos/one.txt\""))
        assertEquals("inside", File(dir, "photos/one.txt").readText())

        val rootPage = bodyOf(get("/"))
        assertTrue("в корне видна папка", rootPage.contains("photos"))
        assertFalse("файл не должен попадать в корень", rootPage.contains("one.txt"))

        val folderJson = bodyOf(get("/api/files?path=photos"))
        assertTrue(folderJson.contains("\"name\":\"one.txt\""))
        assertTrue(folderJson.contains("\"path\":\"photos/one.txt\""))
        assertTrue(folderJson.contains("\"dir\":false"))

        val fileResponse = get("/files/photos/one.txt")
        assertEquals(200, statusOf(fileResponse))
        assertEquals("inside", bodyOf(fileResponse))
    }

    @Test
    fun `creates nested folder and refuses traversal`() {
        startServer()
        post("/folder?path=&name=photos")

        val nested = post("/folder?path=photos&name=2026")
        assertEquals(200, statusOf(nested))
        assertTrue(File(dir, "photos/2026").isDirectory)

        assertEquals(400, statusOf(post("/folder?path=..&name=escape")))
        assertFalse(File(dir.parentFile, "escape").exists())

        assertEquals(400, statusOf(upload("evil.txt", "x", path = "../outside")))
        assertFalse(File(dir.parentFile, "outside/evil.txt").exists())
    }

    @Test
    fun `page shows the same kind for media as the app`() {
        startServer()
        upload("clip.3gp", "video")
        upload("cover.avif", "image")

        val page = bodyOf(get("/"))

        assertTrue("3gp — видео в списке", page.contains("class=\"file-thumb\" data-kind=\"video\""))
        assertTrue("avif — картинка в списке", page.contains("class=\"file-thumb\" data-kind=\"image\""))
        assertTrue(
            "очередь в браузере знает те же видео",
            page.contains("var VIDEO = ['mp4','mkv','mov','avi','webm','m4v','3gp','ts','m2ts'];"),
        )
    }

    @Test
    fun `page has grid toggle selection and folder tools`() {
        startServer()
        upload("one.txt", "x")

        val page = bodyOf(get("/"))

        assertTrue("переключатель сетки", page.contains("id=\"view-grid\""))
        assertTrue("переключатель списка", page.contains("id=\"view-list\""))
        assertTrue("выделение файлов", page.contains("class=\"sel\""))
        assertTrue("панель выделения", page.contains("id=\"sel-delete\""))
        assertTrue("кнопка новой папки", page.contains("id=\"new-folder\""))
        assertTrue("скачивание папки архивом", page.contains("id=\"zip-link\""))
        assertTrue("отмена удаления", page.contains("id=\"undo-bar\""))
        assertTrue("ссылка на корзину", page.contains("id=\"trash-link\""))
        assertTrue("стиль сетки", page.contains("ul.files.grid"))
        assertTrue("данные для поиска в нижнем регистре", page.contains("data-name=\"one.txt\""))
    }

    // --- Переименование и перемещение ---------------------------------------

    @Test
    fun `renames and moves entries`() {
        startServer()
        post("/folder?path=&name=photos")
        upload("old.txt", "data")

        assertEquals(200, statusOf(post("/rename?path=old.txt&name=new.txt")))
        assertTrue(File(dir, "new.txt").exists())
        assertFalse(File(dir, "old.txt").exists())

        assertEquals(200, statusOf(post("/move?path=new.txt&to=photos")))
        assertTrue(File(dir, "photos/new.txt").exists())
        assertFalse(File(dir, "new.txt").exists())

        // Занятое имя не перезаписывается.
        upload("other.txt", "other")
        upload("other.txt", "inside copy", path = "photos")
        assertEquals(409, statusOf(post("/move?path=other.txt&to=photos")))
        assertTrue(File(dir, "other.txt").exists())
        assertEquals("inside copy", File(dir, "photos/other.txt").readText())
    }

    @Test
    fun `cannot rename onto existing name`() {
        startServer()
        upload("a.txt", "a")
        upload("b.txt", "b")

        assertEquals(409, statusOf(post("/rename?path=a.txt&name=b.txt")))
        assertEquals("a", File(dir, "a.txt").readText())
        assertEquals("b", File(dir, "b.txt").readText())
    }

    // --- Удаление ------------------------------------------------------------

    @Test
    fun `bulk delete removes files and folders`() {
        startServer()
        upload("keep.txt", "keep")
        post("/folder?path=&name=photos")
        upload("gone.txt", "gone", path = "photos")

        val response = post(
            "/delete",
            "keep.txt\nphotos\n".toByteArray(StandardCharsets.UTF_8),
        )

        assertEquals(200, statusOf(response))
        assertTrue(bodyOf(response).contains("\"deleted\":2"))
        assertFalse(File(dir, "keep.txt").exists())
        assertFalse(File(dir, "photos").exists())
    }

    @Test
    fun `deleting folder requires recursive flag`() {
        startServer()
        post("/folder?path=&name=photos")
        upload("gone.txt", "gone", path = "photos")

        assertEquals(409, statusOf(rawExchange(buildRequest("DELETE", "/files/photos"))))
        assertTrue("без флага папка остаётся", File(dir, "photos/gone.txt").exists())

        val deleted = rawExchange(buildRequest("DELETE", "/files/photos?recursive=1"))
        assertEquals(200, statusOf(deleted))
        assertFalse(File(dir, "photos").exists())
    }

    @Test
    fun `cannot delete path outside storage`() {
        startServer()
        val outside = File(dir.parentFile, "outside-keep.txt").apply { writeText("keep") }

        assertEquals(404, statusOf(rawExchange(buildRequest("DELETE", "/files/..%2Foutside-keep.txt"))))
        assertTrue(outside.exists())
        outside.delete()
    }

    // --- Корзина -------------------------------------------------------------

    /** id элемента корзины из JSON-ответа одиночного удаления. */
    private fun trashIdOf(response: ByteArray): String {
        val text = bodyOf(response)
        return requireNotNull(
            text.substringAfter("\"trash\":\"", "").substringBefore('"').takeIf { it.isNotEmpty() }
        )
    }

    @Test
    fun `delete moves a file to trash and restore brings it back`() {
        startServer()
        upload("note.txt", "hello")

        val response = rawExchange(buildRequest("DELETE", "/files/note.txt"))

        assertEquals(200, statusOf(response))
        assertFalse(File(dir, "note.txt").exists())
        val id = trashIdOf(response)

        val restored = post("/restore?id=$id")
        assertEquals(200, statusOf(restored))
        assertTrue(bodyOf(restored).contains("\"restored\":1"))
        assertEquals("hello", File(dir, "note.txt").readText())
    }

    @Test
    fun `bulk delete returns trash ids and everything can be restored`() {
        startServer()
        upload("a.txt", "a")
        upload("b.txt", "b")

        val response = post("/delete", "a.txt\nb.txt\n".toByteArray(StandardCharsets.UTF_8))
        val ids = bodyOf(response)
            .substringAfter("\"trash\":[", "")
            .substringBefore(']')
            .split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }

        assertEquals(2, ids.size)
        assertFalse(File(dir, "a.txt").exists())

        val restored = post("/restore", ids.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        assertEquals(200, statusOf(restored))
        assertTrue(bodyOf(restored).contains("\"restored\":2"))
        assertEquals("a", File(dir, "a.txt").readText())
        assertEquals("b", File(dir, "b.txt").readText())
    }

    @Test
    fun `restores a folder deleted with recursive flag`() {
        startServer()
        post("/folder?path=&name=photos")
        upload("a.jpg", "jpeg", path = "photos")

        val deleted = rawExchange(buildRequest("DELETE", "/files/photos?recursive=1"))
        assertEquals(200, statusOf(deleted))
        val id = trashIdOf(deleted)
        assertFalse(File(dir, "photos").exists())

        assertEquals(200, statusOf(post("/restore?id=$id")))
        assertEquals("jpeg", File(dir, "photos/a.jpg").readText())
    }

    @Test
    fun `trash page lists deleted files and can be emptied`() {
        startServer()
        upload("gone.txt", "x")
        rawExchange(buildRequest("DELETE", "/files/gone.txt"))

        val page = bodyOf(get("/trash"))
        assertTrue("элемент в корзине", page.contains("gone.txt"))
        assertTrue("кнопка очистки", page.contains("id=\"trash-empty\""))
        assertTrue("файл скрыт из списка", !bodyOf(get("/")).contains("gone.txt"))
        assertTrue("корзина доступна со страницы", bodyOf(get("/")).contains("href=\"/trash\""))

        val emptied = post("/trash/empty")
        assertEquals(200, statusOf(emptied))
        assertTrue(File(dir, ".trash").listFiles().isNullOrEmpty())
    }

    @Test
    fun `deletes one trash entry forever`() {
        startServer()
        upload("gone.txt", "x")
        val id = trashIdOf(rawExchange(buildRequest("DELETE", "/files/gone.txt")))

        val response = rawExchange(buildRequest("DELETE", "/trash/$id"))

        assertEquals(200, statusOf(response))
        assertTrue(File(dir, ".trash").listFiles().isNullOrEmpty())
        assertEquals(404, statusOf(rawExchange(buildRequest("DELETE", "/trash/$id"))))
    }

    @Test
    fun `restore reports an unknown id without failing the request`() {
        startServer()

        val response = post("/restore?id=does-not-exist")

        assertEquals(200, statusOf(response))
        assertTrue(bodyOf(response).contains("\"restored\":0"))
    }

    // --- Видеотека -----------------------------------------------------------

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

    @Test
    fun `library page shows videos with a card and an edit dialog`() {
        startServer()
        upload("Отпуск 2024.mp4", "video")
        upload("photo.jpg", "jpeg")

        val page = bodyOf(get("/library"))

        assertTrue("заголовок по имени файла", page.contains("Отпуск 2024"))
        assertTrue("кнопка редактирования", page.contains("class='edit'"))
        assertTrue("кнопка удаления", page.contains("class='del'"))
        assertTrue("диалог описания", page.contains("id=\"edit-dialog\""))
        assertTrue("форма редактирования", page.contains("id=\"edit-form\""))
        assertFalse("картинки не попадают в видеотеку", page.contains("photo.jpg"))
    }

    @Test
    fun `saves video title and description from the page`() {
        startServer()
        upload("trip.mp4", "video")

        val response = saveDescription("trip.mp4", "Море", "Первый день\nи вечер")
        assertEquals(200, statusOf(response))
        assertTrue(bodyOf(response).contains("\"ok\":true"))

        val page = bodyOf(get("/library"))
        assertTrue("новый заголовок", page.contains("Море"))
        assertTrue("описание", page.contains("Первый день"))
        assertTrue("сайдкар создан", File(dir, "trip.mp4.library.json").isFile)
        assertFalse("сайдкар не показан как видео", page.contains("library.json"))

        val json = bodyOf(get("/api/library"))
        assertTrue(json.contains("\"title\":\"Море\""))
        assertTrue(json.contains("\"described\":true"))
    }

    @Test
    fun `resets the description from the page`() {
        startServer()
        upload("trip.mp4", "video")
        saveDescription("trip.mp4", "Море", "Первый день у моря")

        val response = post("/library/reset?path=${encPath("trip.mp4")}")

        assertEquals(200, statusOf(response))
        assertFalse(File(dir, "trip.mp4.library.json").exists())
        assertFalse(bodyOf(get("/library")).contains("Первый день у моря"))
    }

    @Test
    fun `library deletion moves the video and its description to trash`() {
        startServer()
        upload("trip.mp4", "video")
        saveDescription("trip.mp4", "Море", "Описание")

        val deleted = post("/delete", "trip.mp4".toByteArray(StandardCharsets.UTF_8))
        assertEquals(200, statusOf(deleted))
        assertFalse(File(dir, "trip.mp4").exists())
        assertFalse(File(dir, "trip.mp4.library.json").exists())
        assertFalse("видео исчезло из видеотеки", bodyOf(get("/library")).contains("Море"))

        val id = bodyOf(deleted).substringAfter("\"trash\":[", "").substringBefore(']')
            .trim().trim('"')
        assertEquals(200, statusOf(post("/restore?id=$id")))

        val page = bodyOf(get("/library"))
        assertTrue("описание вернулось", page.contains("Море"))
        assertTrue("сайдкар вернулся", File(dir, "trip.mp4.library.json").isFile)
    }

    @Test
    fun `video library follows the access mode filter`() {
        startServer(fileLister = { emptyList() })
        upload("trip.mp4", "video")

        val page = bodyOf(get("/library"))

        assertFalse("скрытое видео не показываем", page.contains("trip.mp4"))
        assertTrue("пустая видеотека", page.contains("Пока нет видео"))
        assertEquals(404, statusOf(saveDescription("trip.mp4", "Хак", "текст")))
    }

    @Test
    fun `hidden files cannot be deleted through the file api`() {
        startServer(fileLister = { emptyList() })
        upload("trip.mp4", "video")

        val response = post("/delete", "trip.mp4".toByteArray(StandardCharsets.UTF_8))

        assertTrue(bodyOf(response).contains("\"deleted\":0"))
        assertTrue("файл остался на месте", File(dir, "trip.mp4").exists())
    }

    @Test
    fun `library refuses descriptions for non video files`() {
        startServer()
        upload("photo.jpg", "jpeg")

        assertEquals(404, statusOf(saveDescription("photo.jpg", "Фото", "текст")))
        assertEquals(404, statusOf(post("/library/reset?path=photo.jpg")))
    }

    // --- ZIP -----------------------------------------------------------------

    @Test
    fun `downloads folder as zip archive`() {
        startServer()
        post("/folder?path=&name=photos")
        post("/folder?path=photos&name=2026")
        upload("a.txt", "alpha", path = "photos/2026")
        upload("b.txt", "beta", path = "photos")

        val response = get("/zip?path=photos")

        assertEquals(200, statusOf(response))
        assertTrue("это архив", headersOf(response).contains("Content-Type: application/zip"))
        assertTrue("имя архива", headersOf(response).contains("filename*=UTF-8''photos.zip"))

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(rawBodyOf(response))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                entries[entry.name] = String(bytes, StandardCharsets.UTF_8)
            }
        }

        assertEquals("alpha", entries["2026/a.txt"])
        assertEquals("beta", entries["b.txt"])
        assertTrue("пустая папка тоже попадает", entries.containsKey("2026/"))
    }

    @Test
    fun `zip respects access filter`() {
        File(dir, "visible.txt").writeText("visible")
        File(dir, "hidden.txt").writeText("hidden")
        startServer { listOf(File(dir, "visible.txt")) }

        val response = get("/zip")

        assertEquals(200, statusOf(response))
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(rawBodyOf(response))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.readBytes()
            }
        }

        assertTrue(names.contains("visible.txt"))
        assertFalse("скрытый файл не должен попасть в архив", names.contains("hidden.txt"))
        assertEquals(404, statusOf(get("/files/hidden.txt")))
    }

    // --- Совместимость -------------------------------------------------------

    @Test
    fun `root api stays compatible with flat lists`() {
        startServer()
        upload("one.txt", "12345")
        upload("two.txt", "abc")

        val json = bodyOf(get("/api/files"))

        assertTrue(json.contains("\"name\":\"one.txt\""))
        assertTrue(json.contains("\"size\":5"))
        assertTrue(json.contains("\"modified\":"))
        assertTrue("появились признаки папок", json.contains("\"dir\":false"))
    }

    @Test
    fun `serves thumbnails by nested path`() {
        startServer()
        post("/folder?path=&name=photos")
        upload("image.png", "png", path = "photos")

        val response = get("/thumb-file?name=photos/image.png")

        assertEquals(200, statusOf(response))
        assertTrue(
            "без отдельного превью отдаётся оригинал",
            headersOf(response).contains("Content-Type: image/png"),
        )

        val page = bodyOf(get("/?path=photos"))
        val img = page.substringAfter("<img class=\"fthumb\"", "").substringBefore('>')
        assertTrue("превью указывает на файл", img.contains("/thumb-file?name=photos/image.png"))
        assertTrue("ленивая загрузка включена", img.contains("\" loading=\"lazy\""))
        assertFalse("в теге нет лишней кавычки", img.contains("\"\" loading"))
    }

    @Test
    fun `nested file keeps exact bytes`() {
        startServer()
        post("/folder?path=&name=photos")
        val content = ByteArray(Protocol.BUFFER_SIZE * 2 + 17) { (it % 251).toByte() }

        val response = rawExchange(
            buildRequest(
                "POST",
                "/upload?name=large.bin&path=photos",
                content,
            )
        )

        assertEquals(200, statusOf(response))
        assertArrayEquals(content, File(dir, "photos/large.bin").readBytes())
    }
}
