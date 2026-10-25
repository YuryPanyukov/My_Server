package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Сквозная проверка протокола: настоящий сервер на JVM и настоящий
 * десктопный клиент `scripts/send_file.py`, запущенный отдельным процессом.
 */
class PythonClientCompatibilityTest {

    private lateinit var dir: File
    private lateinit var sourceDir: File
    private lateinit var server: FileReceiverServer

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("py-compat").toFile()
        // Исходный файл держим отдельно: сервер пишет принятое в dir и
        // иначе обнулил бы файл, который клиент ещё читает.
        sourceDir = Files.createTempDirectory("py-source").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
        sourceDir.deleteRecursively()
    }

    private fun findScript(): File? {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(4) {
            val candidate = current?.resolve("scripts/send_file.py")
            if (candidate != null && candidate.isFile) return candidate
            current = current?.parentFile
        }
        return null
    }

    private fun findPython(): String? {
        val candidates = listOf("python", "python3", "py")
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

    @Test
    fun `desktop python client transfers file to jvm server`() {
        val python = findPython()
        assumeTrue("Python не найден, тест совместимости пропущен", python != null)
        val script = findScript()
        assumeTrue("scripts/send_file.py не найден", script != null)

        server = FileReceiverServer(
            port = 0,
            bindAddress = InetAddress.getByName("127.0.0.1"),
            socketTimeoutMs = 10_000,
            saver = { name, size, input -> FileSaver.writeTo(dir, name, size, input) }
        )
        server.start()

        val content = ("Строка с кириллицей и цифрами 12345\n".repeat(500))
            .toByteArray(StandardCharsets.UTF_8)
        val source = File(sourceDir, "Отчёт клиента.txt")
        source.writeBytes(content)

        val process = ProcessBuilder(
            python!!,
            script!!.absolutePath,
            "127.0.0.1",
            source.absolutePath,
            server.localPort.toString()
        )
            .redirectErrorStream(true)
            .apply { environment()["PYTHONIOENCODING"] = "utf-8" }
            .start()

        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()

        assertTrue("клиент не завершился вовремя, вывод: $output", finished)
        assertEquals("клиент завершился с ошибкой, вывод: $output", 0, process.exitValue())
        assertTrue("клиент должен сообщить об успехе, вывод: $output", output.contains("Успех"))

        val received = File(dir, "Отчёт клиента.txt")
        assertTrue("файл должен быть сохранён", received.isFile)
        assertEquals(content.size.toLong(), received.length())
        assertArrayEquals(content, FileInputStream(received).use { it.readBytes() })
    }

    @Test
    fun `desktop python client reports failure when server rejects file`() {
        val python = findPython()
        assumeTrue("Python не найден, тест совместимости пропущен", python != null)
        val script = findScript()
        assumeTrue("scripts/send_file.py не найден", script != null)

        server = FileReceiverServer(
            port = 0,
            bindAddress = InetAddress.getByName("127.0.0.1"),
            socketTimeoutMs = 10_000,
            saver = { _, _, _ -> null } // сервер всегда отказывает
        )
        server.start()

        val source = File(sourceDir, "rejected.txt").apply { writeText("data") }

        val process = ProcessBuilder(
            python!!,
            script!!.absolutePath,
            "127.0.0.1",
            source.absolutePath,
            server.localPort.toString()
        )
            .redirectErrorStream(true)
            .apply { environment()["PYTHONIOENCODING"] = "utf-8" }
            .start()

        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()

        assertTrue("клиент не завершился вовремя, вывод: $output", finished)
        assertTrue("клиент должен сообщить об ошибке, вывод: $output", output.contains("Ошибка"))
    }
}
