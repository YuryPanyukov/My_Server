package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * PIN-защита: вход через браузер выдаёт сессионную cookie, API без неё
 * отвечает 403, а бинарный клиент без метки PIN не принимается.
 */
class PinAuthHttpTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pin-http").toFile()
        PinAuthCodec.update(PinAuthConfig(enabled = true, code = "1234"))
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        dir.deleteRecursively()
        PinAuthCodec.update(PinAuthConfig(enabled = false, code = null))
    }

    private fun startServer(): FileReceiverServer {
        val created = FileReceiverServer(
            port = 0,
            bindAddress = loopback,
            socketTimeoutMs = 5_000,
            saver = { name, size, input -> FileSaver.writeTo(dir, name, size, input) },
            fileLister = { dir.listFiles()?.filter { it.isFile }.orEmpty() },
            storageRoot = { dir },
        )
        created.start()
        server = created
        return created
    }

    private fun request(
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
        val packet = head.toByteArray(StandardCharsets.UTF_8) + body
        val srv = server ?: error("server is not started")
        Socket(loopback, srv.localPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(packet)
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

    /** Запрос навигации браузера: без HTML в `Accept` сервер отвечает 403, а не редиректом. */
    private fun navigate(target: String, cookie: String? = null): ByteArray =
        request(
            "GET", target,
            headers = buildMap {
                put("Accept", "text/html,application/xhtml+xml")
                cookie?.let { put("Cookie", it) }
            },
        )

    private fun headerValue(response: ByteArray, name: String): String? =
        headersOf(response).lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    /** Вход по PIN; возвращает cookie либо null при отказе. */
    private fun login(code: String, next: String = "/"): String? {
        val response = request(
            "POST", "/pin-verify",
            "code=$code&next=${next.replace("/", "%2F")}".toByteArray(StandardCharsets.UTF_8),
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        if (statusOf(response) != 303) return null
        return headerValue(response, "Set-Cookie")?.substringBefore(';')
    }

    @Test
    fun `browser is redirected to pin page and health stays open`() {
        startServer()

        assertEquals("API без сессии получает JSON-отказ", 403, statusOf(request("GET", "/")))

        val root = navigate("/")
        assertEquals(303, statusOf(root))
        assertTrue("редирект на форму входа", headerValue(root, "Location")!!.startsWith("/pin?next="))

        assertEquals("OK", bodyOf(request("GET", "/health")))

        val check = bodyOf(request("GET", "/pin-check"))
        assertTrue("PIN включён", check.contains("\"enabled\":true"))
        assertTrue("сессии нет", check.contains("\"authorized\":false"))

        val page = bodyOf(request("GET", "/pin"))
        assertTrue("страница просит код", page.contains("Введите PIN"))
        assertTrue("форма отправляет код", page.contains("action=\"/pin-verify\""))
    }

    @Test
    fun `api without session is forbidden and page lists files after login`() {
        startServer()
        File(dir, "note.txt").writeText("hello")

        assertEquals(403, statusOf(request("GET", "/api/files")))
        assertEquals(403, statusOf(request("POST", "/upload?name=a.txt")))

        val cookie = login("1234") ?: error("верный PIN должен выдавать сессию")

        val page = navigate("/", cookie)
        assertEquals(200, statusOf(page))
        assertTrue("файл виден после входа", bodyOf(page).contains("note.txt"))

        val check = bodyOf(request("GET", "/pin-check", headers = mapOf("Cookie" to cookie)))
        assertTrue("сессия распознана", check.contains("\"authorized\":true"))
    }

    @Test
    fun `login page keeps next path and wrong code is rejected`() {
        startServer()

        val page = bodyOf(request("GET", "/pin?next=%2Ftrash"))
        assertTrue("next сохраняется в форме", page.contains("value=\"/trash\""))

        val wrong = request(
            "POST", "/pin-verify",
            "code=0000&next=%2Ftrash".toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(401, statusOf(wrong))
        assertTrue("сообщение об ошибке", bodyOf(wrong).contains("Неверный PIN"))
        assertTrue("форма помнит, куда вернуться", bodyOf(wrong).contains("value=\"/trash\""))

        val ok = request(
            "POST", "/pin-verify",
            "code=1234&next=%2Ftrash".toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(303, statusOf(ok))
        assertEquals("/trash", headerValue(ok, "Location"))
    }

    @Test
    fun `next with control characters cannot inject headers`() {
        startServer()

        val ok = request(
            "POST", "/pin-verify",
            "code=1234&next=%2Fok%0D%0AX-Bad%3A%201".toByteArray(StandardCharsets.UTF_8),
        )

        assertEquals(303, statusOf(ok))
        assertEquals("подозрительный next отбрасывается", "/", headerValue(ok, "Location"))
        assertEquals("лишнего заголовка нет", null, headerValue(ok, "X-Bad"))
    }

    @Test
    fun `changing pin invalidates existing sessions`() {
        startServer()
        val cookie = login("1234") ?: error("верный PIN должен выдавать сессию")
        assertEquals(200, statusOf(navigate("/", cookie)))

        PinAuthCodec.update(PinAuthConfig(enabled = true, code = "9876"))

        val stale = navigate("/", cookie)
        assertEquals("старая сессия должна перестать действовать", 303, statusOf(stale))
        assertEquals(200, statusOf(navigate("/", login("9876")!!)))
    }

    @Test
    fun `too many wrong guesses block the login`() {
        startServer()
        // Первые четыре ошибки — просто отказ, пятая исчерпывает лимит.
        repeat(4) {
            assertEquals(
                401,
                statusOf(
                    request("POST", "/pin-verify", "code=0000".toByteArray(StandardCharsets.UTF_8))
                ),
            )
        }

        val limit = request("POST", "/pin-verify", "code=0000".toByteArray(StandardCharsets.UTF_8))
        assertEquals("пятая ошибка исчерпывает лимит", 429, statusOf(limit))

        val blocked = request("POST", "/pin-verify", "code=1234".toByteArray(StandardCharsets.UTF_8))
        assertEquals("перебор блокируется даже для верного кода", 429, statusOf(blocked))
        assertTrue(bodyOf(blocked).contains("Слишком много"))
    }

    @Test
    fun `binary client without pin is rejected and with pin is accepted`() {
        startServer()

        val rejected = sendBinary(binaryFrame("hidden.txt", "data".toByteArray()))
        assertEquals("без метки PIN сервер отказывает", 0, rejected)

        val accepted = sendBinary("P1234".toByteArray() + binaryFrame("ok.txt", "data".toByteArray()))
        assertEquals("с верным PIN файл принят", 1, accepted)

        assertTrue(File(dir, "ok.txt").exists())
        assertFalse(File(dir, "hidden.txt").exists())
    }

    private fun binaryFrame(name: String, content: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        Protocol.writeHeader(out, name, content.size.toLong())
        out.write(content)
        out.flush()
        return bytes.toByteArray()
    }

    /** Отправляет пакет в бинарном режиме и возвращает байт-ответ сервера (0/1). */
    private fun sendBinary(packet: ByteArray): Int {
        val srv = server ?: error("server is not started")
        Socket(loopback, srv.localPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(packet)
                flush()
            }
            return socket.getInputStream().read()
        }
    }
}
