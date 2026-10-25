package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Сквозная проверка настоящим HTTP-клиентом (curl): частичные ответы для
 * перемотки видео и удаление файла. Если curl не установлен — тест пропускается.
 */
class CurlCompatibilityTest {

    private lateinit var dir: File
    private lateinit var outDir: File
    private lateinit var server: FileReceiverServer

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("curl-server").toFile()
        outDir = Files.createTempDirectory("curl-client").toFile()

        server = FileReceiverServer(
            port = 0,
            bindAddress = InetAddress.getByName("127.0.0.1"),
            socketTimeoutMs = 10_000,
            saver = { name, size, input -> FileSaver.writeTo(dir, name, size, input) },
            fileLister = { dir.listFiles()?.filter { it.isFile }?.toList() ?: emptyList() }
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
        outDir.deleteRecursively()
    }

    private fun findCurl(): String? {
        val candidates = listOf("curl", "curl.exe")
        for (command in candidates) {
            val ok = try {
                val process = ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
            } catch (_: Exception) {
                false
            }
            if (ok) return command
        }
        return null
    }

    private fun runCurl(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .apply { environment()["LANG"] = "C.UTF-8" }
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("curl не завершился вовремя: $output", finished)
        return process.exitValue() to output
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

    @Test
    fun `curl receives requested byte range`() {
        val curl = findCurl()
        assumeTrue("curl не найден, тест пропущен", curl != null)

        val content = "0123456789".toByteArray()
        File(dir, "range.bin").writeBytes(content)
        val target = File(outDir, "part.bin")

        val (exit, output) = runCurl(
            curl!!, "-sS", "-D", "-", "-H", "Range: bytes=2-5",
            "-o", target.absolutePath, url("/files/range.bin")
        )

        assertEquals("curl завершился с ошибкой: $output", 0, exit)
        assertTrue("должен быть 206 Partial Content: $output", output.contains("206 Partial Content"))
        assertTrue("должен быть Content-Range: $output", output.contains("Content-Range: bytes 2-5/10"))
        assertEquals("2345", target.readText())
    }

    @Test
    fun `curl deletes file`() {
        val curl = findCurl()
        assumeTrue("curl не найден, тест пропущен", curl != null)

        File(dir, "trash.txt").writeText("bye")

        val (exit, output) = runCurl(curl!!, "-sS", "-X", "DELETE", url("/files/trash.txt"))

        assertEquals("curl завершился с ошибкой: $output", 0, exit)
        assertTrue("ответ должен подтверждать удаление: $output", output.contains("\"ok\":true"))
        assertFalse("файл должен быть удалён", File(dir, "trash.txt").exists())
    }

    @Test
    fun `curl sees search field on page`() {
        val curl = findCurl()
        assumeTrue("curl не найден, тест пропущен", curl != null)

        val (exit, output) = runCurl(curl!!, "-sS", url("/"))

        assertEquals("curl завершился с ошибкой: $output", 0, exit)
        assertTrue("на странице должно быть поле поиска", output.contains("id=\"q\""))
    }
}
