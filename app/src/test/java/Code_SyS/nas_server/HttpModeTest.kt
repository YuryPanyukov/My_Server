package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * HTTP-режим поверх того же порта, что и бинарный протокол: реальные
 * TCP-сокеты на loopback, без Android-устройства и эмулятора.
 */
class HttpModeTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("http-mode").toFile()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        dir.deleteRecursively()
    }

    private fun startServer(
        timeoutMs: Int = 5_000,
        saver: (String, Long, InputStream) -> File? = { name, size, input ->
            FileSaver.writeTo(dir, name, size, input)
        }
    ): FileReceiverServer {
        val created = FileReceiverServer(
            port = 0,
            bindAddress = loopback,
            socketTimeoutMs = timeoutMs,
            saver = saver
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
        includeContentLength: Boolean = true,
        host: String = "127.0.0.1",
    ): ByteArray {
        val head = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            if (includeContentLength) append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        return head.toByteArray(StandardCharsets.UTF_8) + body
    }

    /** Отправляет запрос и читает ответ целиком (сервер закрывает соединение). */
    private fun exchange(request: ByteArray): String {
        val srv = server ?: error("server is not started")
        Socket(loopback, srv.localPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            return socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun statusCode(response: String): Int =
        response.lineSequence().first().split(' ')[1].toInt()

    private fun bodyOf(response: String): String {
        val index = response.indexOf("\r\n\r\n")
        return if (index < 0) "" else response.substring(index + 4)
    }

    private fun readSaved(name: String): ByteArray =
        FileInputStream(File(dir, name)).use { it.readBytes() }

    private fun upload(target: String, content: ByteArray, method: String = "POST"): String =
        exchange(buildRequest(method, target, content))

    // --- Страница и служебные маршруты -------------------------------------

    @Test
    fun `serves upload page`() {
        startServer()

        val response = exchange(buildRequest("GET", "/"))

        assertEquals(200, statusCode(response))
        assertTrue("должен быть HTML", response.contains("Content-Type: text/html"))
        val body = bodyOf(response)
        assertTrue("страница должна содержать выбор файлов", body.contains("type=\"file\""))
        assertTrue("страница должна уметь отправлять на /upload", body.contains("/upload?name="))
    }

    @Test
    fun `serves health check`() {
        startServer()

        val response = exchange(buildRequest("GET", "/health"))

        assertEquals(200, statusCode(response))
        assertEquals("OK", bodyOf(response))
    }

    @Test
    fun `open bridge page links to app and browser`() {
        startServer()

        val response = exchange(
            buildRequest("GET", "/open", host = "192.168.1.42:8080")
        )

        assertEquals(200, statusCode(response))
        val body = bodyOf(response)
        assertTrue("адрес из QR", body.contains("192.168.1.42:8080"))
        assertTrue("deep link для приложения", body.contains("landrop://connect?host=192.168.1.42&amp;port=8080"))
        assertTrue("запасная ссылка в браузер", body.contains("href=\"http://192.168.1.42:8080\""))
        assertTrue("адрес попадает в JS без HTML-сущностей", body.contains("landrop://connect?host=192.168.1.42&port=8080"))
    }

    @Test
    fun `open bridge page survives missing host header`() {
        startServer()

        val request = "GET /open HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray()
        val response = exchange(request)

        assertEquals(200, statusCode(response))
        val body = bodyOf(response)
        assertTrue("ссылка на веб-версию остаётся", body.contains("href=\"/\""))
        assertFalse("некуда вести приложение", body.contains("landrop://"))
    }

    @Test
    fun `head request returns headers without body`() {
        startServer()

        val response = exchange(buildRequest("HEAD", "/"))

        assertEquals(200, statusCode(response))
        assertTrue("Content-Length должен быть", response.contains("Content-Length:"))
        assertEquals("у HEAD не должно быть тела", "", bodyOf(response))
    }

    // --- Загрузка файлов ----------------------------------------------------

    @Test
    fun `uploads file via POST upload`() {
        startServer()
        val content = "Hello from HTTP!".toByteArray()

        val response = upload("/upload?name=test.txt", content)

        assertEquals(200, statusCode(response))
        assertTrue("ответ должен подтверждать успех: ${bodyOf(response)}", bodyOf(response).contains("\"ok\":true"))
        assertTrue(File(dir, "test.txt").exists())
        assertArrayEquals(content, readSaved("test.txt"))
    }

    @Test
    fun `does not overwrite file with same name`() {
        startServer()

        assertEquals(200, statusCode(upload("/upload?name=same.txt", "first".toByteArray())))
        assertEquals(200, statusCode(upload("/upload?name=same.txt", "second".toByteArray())))

        val names = dir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("same (1).txt", "same.txt"), names)
        assertEquals("first", readSaved("same.txt").toString(StandardCharsets.UTF_8))
        assertEquals("second", readSaved("same (1).txt").toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `uploads file via PUT upload like curl`() {
        startServer()
        val content = "curl style upload".toByteArray()

        val response = upload("/upload?name=curl.txt", content, method = "PUT")

        assertEquals(200, statusCode(response))
        assertArrayEquals(content, readSaved("curl.txt"))
    }

    @Test
    fun `decodes url encoded cyrillic name`() {
        startServer()
        val content = "привет".toByteArray(StandardCharsets.UTF_8)
        // encodeURIComponent (как в странице загрузки): пробел -> %20, а не '+'
        val encoded = "/upload?name=" +
            java.net.URLEncoder.encode("Отчёт 2026.txt", "UTF-8").replace("+", "%20")

        val response = upload(encoded, content)

        assertEquals(200, statusCode(response))
        assertArrayEquals(content, readSaved("Отчёт 2026.txt"))
    }

    @Test
    fun `keeps plus sign in file name`() {
        startServer()

        upload("/upload?name=a+b.txt", "plus".toByteArray())

        assertTrue(File(dir, "a+b.txt").exists())
    }

    @Test
    fun `sanitizes path traversal in name`() {
        startServer()
        val content = "secret".toByteArray()

        val response = upload("/upload?name=..%2F..%2Fetc%2Fpasswd", content)

        assertEquals(200, statusCode(response))
        assertEquals("passwd", File(dir, "passwd").name)
        assertFalse(File(dir.parentFile, "etc/passwd").exists())
        assertArrayEquals(content, readSaved("passwd"))
    }

    @Test
    fun `uploads file larger than one buffer`() {
        startServer()
        val content = ByteArray(Protocol.BUFFER_SIZE * 2 + 257) { (it % 251).toByte() }

        val response = upload("/upload?name=large.bin", content)

        assertEquals(200, statusCode(response))
        assertEquals(content.size.toLong(), File(dir, "large.bin").length())
        assertArrayEquals(content, readSaved("large.bin"))
    }

    @Test
    fun `uploads empty file`() {
        startServer()

        val response = upload("/upload?name=empty.bin", ByteArray(0))

        assertEquals(200, statusCode(response))
        assertEquals(0L, File(dir, "empty.bin").length())
    }

    // --- Ошибки -------------------------------------------------------------

    @Test
    fun `upload without name returns 400`() {
        startServer()

        val response = upload("/upload", "data".toByteArray())

        assertEquals(400, statusCode(response))
        assertTrue(bodyOf(response).contains("\"ok\":false"))
        assertTrue("файл не должен создаваться", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `upload without content length returns 411`() {
        startServer()

        val request = buildRequest(
            "POST",
            "/upload?name=x.txt",
            "data".toByteArray(),
            includeContentLength = false
        )
        val response = exchange(request)

        assertEquals(411, statusCode(response))
    }

    @Test
    fun `reports 500 when saver rejects file`() {
        startServer(saver = { _, _, _ -> null })

        val response = upload("/upload?name=rejected.bin", "data".toByteArray())

        assertEquals(500, statusCode(response))
        assertTrue(bodyOf(response).contains("\"ok\":false"))
    }

    @Test
    fun `unknown path returns 404`() {
        startServer()

        assertEquals(404, statusCode(exchange(buildRequest("GET", "/nope"))))
        assertEquals(404, statusCode(upload("/other", "x".toByteArray())))
    }

    // --- Совместимость с бинарным протоколом --------------------------------

    @Test
    fun `binary protocol keeps working on same port`() {
        startServer()
        val httpContent = "over http".toByteArray()
        assertEquals(200, statusCode(upload("/upload?name=http.txt", httpContent)))

        val content = "over binary protocol".toByteArray(StandardCharsets.UTF_8)
        val srv = server ?: error("server is not started")
        val ok = Socket(loopback, srv.localPort).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            Protocol.writeHeader(out, "binary.txt", content.size.toLong())
            out.write(content)
            out.flush()
            DataInputStream(socket.getInputStream()).use { it.readBoolean() }
        }

        assertTrue("бинарный протокол должен продолжать работать", ok)
        assertArrayEquals(content, readSaved("binary.txt"))
        assertArrayEquals(httpContent, readSaved("http.txt"))
    }

    @Test
    fun `handles several http connections in a row`() {
        startServer()

        for (i in 1..3) {
            val content = "file $i".toByteArray()
            assertEquals(200, statusCode(upload("/upload?name=file$i.txt", content)))
            assertArrayEquals(content, readSaved("file$i.txt"))
        }
    }
}
