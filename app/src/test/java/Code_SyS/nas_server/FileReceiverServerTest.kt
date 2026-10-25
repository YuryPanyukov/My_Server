package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Полный цикл приёма файла через реальные TCP-сокеты на loopback.
 * Не требует Android-устройства и эмулятора.
 */
class FileReceiverServerTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("file-receiver").toFile()
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
            port = 0, // свободный порт, чтобы тесты не конфликтовали с 8080
            bindAddress = loopback,
            socketTimeoutMs = timeoutMs,
            saver = saver
        )
        created.start()
        server = created
        return created
    }

    /** Отправляет файл через production-код протокола. */
    private fun sendFile(name: String, content: ByteArray): Boolean {
        val srv = server ?: error("server is not started")
        Socket(loopback, srv.localPort).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            Protocol.writeHeader(out, name, content.size.toLong())
            out.write(content)
            out.flush()

            return DataInputStream(socket.getInputStream()).use { it.readBoolean() }
        }
    }

    /**
     * Отправляет ровно те байты, что формирует Python-клиент
     * (scripts/send_file.py), без использования Protocol.writeHeader.
     * Так тест проверяет реальный формат, а не сам себя.
     */
    private fun sendRawWireFormat(name: String, content: ByteArray): Boolean {
        val srv = server ?: error("server is not started")
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        Socket(loopback, srv.localPort).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            out.writeShort(nameBytes.size)            // 2B длина имени, big-endian
            out.write(nameBytes)                       // имя UTF-8
            out.writeLong(content.size.toLong())       // 8B размер, big-endian
            out.write(content)                         // тело
            out.flush()

            return DataInputStream(socket.getInputStream()).use { it.readBoolean() }
        }
    }

    private fun readSaved(name: String): ByteArray =
        FileInputStream(File(dir, name)).use { it.readBytes() }

    @Test
    fun `start binds port and listens`() {
        val srv = startServer()
        assertTrue(srv.isRunning)
        assertTrue("порт должен быть выделен", srv.localPort > 0)
    }

    @Test
    fun `receives file and answers success`() {
        startServer()
        val content = "Hello, World!".toByteArray()

        val response = sendFile("test.txt", content)

        assertTrue(response)
        assertTrue(File(dir, "test.txt").exists())
        assertArrayEquals(content, readSaved("test.txt"))
    }

    @Test
    fun `receives empty file`() {
        startServer()

        val response = sendFile("empty.bin", ByteArray(0))

        assertTrue(response)
        assertEquals(0L, File(dir, "empty.bin").length())
    }

    @Test
    fun `receives cyrillic file name`() {
        startServer()
        val content = "привет".toByteArray(StandardCharsets.UTF_8)

        val response = sendFile("Отчёт за 2026 год.txt", content)

        assertTrue(response)
        assertArrayEquals(content, readSaved("Отчёт за 2026 год.txt"))
    }

    @Test
    fun `receives file larger than one buffer`() {
        startServer()
        val content = ByteArray(Protocol.BUFFER_SIZE * 2 + 321) { (it % 251).toByte() }

        val response = sendFile("large.bin", content)

        assertTrue(response)
        assertEquals(content.size.toLong(), File(dir, "large.bin").length())
        assertArrayEquals(content, readSaved("large.bin"))
    }

    @Test
    fun `sanitizes path traversal in incoming name`() {
        startServer()
        val content = "secret".toByteArray()

        val response = sendFile("../../../etc/passwd", content)

        assertTrue(response)
        assertEquals("passwd", File(dir, "passwd").name)
        assertFalse(File(dir.parentFile, "etc/passwd").exists())
        assertArrayEquals(content, readSaved("passwd"))
    }

    @Test
    fun `accepts raw wire format produced by desktop client`() {
        startServer()
        val content = "raw bytes from python client".toByteArray(StandardCharsets.UTF_8)

        val response = sendRawWireFormat("raw.txt", content)

        assertTrue(response)
        assertArrayEquals(content, readSaved("raw.txt"))
    }

    @Test
    fun `answers false when saver rejects file`() {
        startServer(saver = { _, _, _ -> null })

        val response = sendFile("rejected.bin", "data".toByteArray())

        assertFalse(response)
    }

    @Test
    fun `answers false when body is shorter than declared`() {
        startServer(timeoutMs = 2_000)
        val srv = server ?: error("server is not started")

        Socket(loopback, srv.localPort).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            out.writeShort(9)                       // "trunc.bin".length == 9
            out.write("trunc.bin".toByteArray(StandardCharsets.UTF_8))
            out.writeLong(100L)                     // объявлено 100 байт
            out.write("only10byte".toByteArray())   // отправлено 10
            out.flush()
            // Сигнализируем конец данных, не закрывая соединение
            socket.shutdownOutput()

            val response = DataInputStream(socket.getInputStream()).use { it.readBoolean() }
            assertFalse("недописанный файл не должен считаться успехом", response)
        }

        // Недописанный файл не должен остаться на диске
        assertFalse(File(dir, "trunc.bin").exists())
    }

    @Test
    fun `handles several sequential connections`() {
        startServer()

        for (i in 1..3) {
            val content = "file $i".toByteArray()
            assertTrue("соединение $i", sendFile("file$i.txt", content))
            assertArrayEquals(content, readSaved("file$i.txt"))
        }
    }

    @Test
    fun `keeps serving after a failed transfer`() {
        startServer(saver = { name, _, _ ->
            if (name == "bad.bin") null else File(dir, name).apply { writeBytes(byteArrayOf(1)) }
        })

        assertFalse(sendFile("bad.bin", "x".toByteArray()))

        val srv = server ?: error("server is not started")
        assertTrue("сервер должен продолжать работать", srv.isRunning)
    }

    @Test
    fun `close stops listening`() {
        val srv = startServer()
        val port = srv.localPort

        srv.close()

        assertFalse(srv.isRunning)
        assertEquals(-1, srv.localPort)

        var connected = false
        try {
            Socket(loopback, port).use { connected = true }
        } catch (_: ConnectException) {
            connected = false
        }
        assertFalse("после close() новые подключения не принимаются", connected)
    }

    @Test
    fun `saver receives declared name and size`() {
        var seenName: String? = null
        var seenSize = -1L
        startServer(saver = { name, size, input ->
            seenName = name
            seenSize = size
            FileSaver.writeTo(dir, name, size, input)
        })

        val content = "0123456789".toByteArray()
        assertTrue(sendFile("meta.bin", content))

        assertEquals("meta.bin", seenName)
        assertEquals(10L, seenSize)
        assertNotNull(File(dir, "meta.bin"))
    }
}
