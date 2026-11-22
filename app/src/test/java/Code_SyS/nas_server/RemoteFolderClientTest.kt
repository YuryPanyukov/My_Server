package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Клиент второго телефона против настоящего сервера на loopback: создание папок,
 * загрузка внутрь папки, переименование, перемещение и удаление.
 *
 * Ответы в JSON читает серверная сторона (см. HttpFolderTest), здесь проверяется
 * то, ради чего клиент существует: что запросы доходят и меняют файлы на диске.
 */
class RemoteFolderClientTest {

    private lateinit var dir: File
    private var server: FileReceiverServer? = null
    private lateinit var client: RemoteClient

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("remote-client").toFile()
        val storage = ReceivedStorage { dir }
        val created = FileReceiverServer(
            port = 0,
            bindAddress = InetAddress.getByName("127.0.0.1"),
            socketTimeoutMs = 5_000,
            saver = { name, size, input -> FileSaver.writeTo(dir, name, size, input) },
            fileLister = { storage.allFiles() },
            storageRoot = { dir },
            folderSaver = { name, size, target, input -> FileSaver.writeTo(target, name, size, input) },
        )
        created.start()
        server = created
        client = RemoteClient("127.0.0.1", created.localPort)
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        dir.deleteRecursively()
    }

    private fun upload(name: String, content: String, path: String = "") {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        client.upload(
            name = name,
            size = bytes.size.toLong(),
            openStream = { ByteArrayInputStream(bytes) },
            path = path,
        )
    }

    @Test
    fun `creates nested folders`() {
        client.createFolder("", "Отпуск")
        client.createFolder("Отпуск", "2026")

        assertTrue(File(dir, "Отпуск").isDirectory)
        assertTrue(File(dir, "Отпуск/2026").isDirectory)
    }

    @Test
    fun `uploads into folder`() {
        client.createFolder("", "photos")
        upload("beach.jpg", "jpg", path = "photos")

        assertEquals("jpg", File(dir, "photos/beach.jpg").readText())
        assertFalse(File(dir, "beach.jpg").exists())
    }

    @Test
    fun `renames and moves`() {
        client.createFolder("", "photos")
        upload("old.txt", "data")

        client.rename("old.txt", "new.txt")
        assertTrue(File(dir, "new.txt").exists())
        assertFalse(File(dir, "old.txt").exists())

        client.move("new.txt", "photos")
        assertEquals("data", File(dir, "photos/new.txt").readText())
        assertFalse(File(dir, "new.txt").exists())
    }

    @Test
    fun `deletes folder recursively`() {
        client.createFolder("", "photos")
        upload("gone.txt", "gone", path = "photos")

        client.deleteFile("photos", recursive = true)

        assertFalse(File(dir, "photos").exists())
    }

    @Test
    fun `streams a file from the host for forwarding`() {
        upload("relay.txt", "переслать")

        val text = client.openFile("relay.txt")?.use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }

        assertEquals("переслать", text)
        assertNull("удалённого файла нет", client.openFile("missing.txt"))
    }

    @Test
    fun `cancelled upload stops mid-file and leaves no partial file`() {
        val payload = ByteArray(256 * 1024)
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads > 1) return -1
                payload.copyInto(buffer, offset, 0, length)
                return length
            }
        }

        val failure = runCatching {
            client.upload(
                name = "big.bin",
                size = payload.size.toLong(),
                openStream = { source },
                isCancelled = { reads > 0 },
            )
        }.exceptionOrNull()

        assertTrue("ожидалась отмена, а не успех", failure is IOException)
        assertEquals(CANCELLED_MESSAGE, failure?.message)
        // Сервер получает часть тела, но недокачанный файл удаляет сам.
        val deadline = System.currentTimeMillis() + 5_000
        while (File(dir, "big.bin").exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse("частичный файл не остался", File(dir, "big.bin").exists())
    }

    @Test
    fun `keeps cyrillic and spaces in names`() {
        client.createFolder("", "Мои фото")
        upload("план поездки.txt", "план", path = "Мои фото")

        assertEquals("план", File(dir, "Мои фото/план поездки.txt").readText())
        assertTrue(client.filePath("Мои фото/план поездки.txt").contains("%D0%9C"))
        assertTrue("слэш остаётся разделителем", client.filePath("a/b.txt").endsWith("/a/b.txt"))
        assertTrue(client.fileThumbPath("Мои фото/a.jpg").startsWith("/thumb-file?name="))
    }
}
