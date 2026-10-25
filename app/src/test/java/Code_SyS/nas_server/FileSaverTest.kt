package Code_SyS.nas_server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class FileSaverTest {

    private fun tempDir(): File = Files.createTempDirectory("wifisave").toFile().also { it.deleteOnExit() }

    @Test
    fun `writeTo saves file with exact content`() {
        val dir = tempDir()
        val content = "Hello, World!".toByteArray()

        val saved = requireNotNull(FileSaver.writeTo(dir, "test.txt", content.size.toLong(), ByteArrayInputStream(content)))

        assertTrue(saved.exists())
        assertEquals("test.txt", saved.name)
        assertEquals(content.size.toLong(), saved.length())
        assertArrayEquals(content, FileInputStream(saved).use { it.readBytes() })
    }

    @Test
    fun `writeTo sanitizes path traversal in filename`() {
        val dir = tempDir()
        val content = "secret".toByteArray()

        val saved = requireNotNull(FileSaver.writeTo(dir, "../../../etc/passwd", content.size.toLong(), ByteArrayInputStream(content)))

        assertEquals("passwd", saved.name)
        assertEquals(dir, saved.parentFile)
        assertArrayEquals(content, FileInputStream(saved).use { it.readBytes() })
    }

    @Test
    fun `writeTo sanitizes windows style path`() {
        val dir = tempDir()
        val content = "x".toByteArray()

        val saved = requireNotNull(FileSaver.writeTo(dir, """C:\Windows\System32\evil.dll""", content.size.toLong(), ByteArrayInputStream(content)))

        assertEquals("evil.dll", saved.name)
        assertEquals(dir, saved.parentFile)
    }

    @Test
    fun `writeTo returns null for empty filename`() {
        val dir = tempDir()
        assertNull(FileSaver.writeTo(dir, "", 0L, ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun `writeTo returns null for negative size`() {
        val dir = tempDir()
        assertNull(FileSaver.writeTo(dir, "a.bin", -1L, ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun `writeTo reads exactly fileSize bytes and does not wait for EOF`() {
        val dir = tempDir()
        val declared = "0123456789".toByteArray()
        // В потоке больше данных, чем объявлено в заголовке
        val stream = ByteArrayInputStream(declared + "EXTRA".toByteArray())

        val saved = requireNotNull(FileSaver.writeTo(dir, "exact.bin", declared.size.toLong(), stream))

        assertEquals(declared.size.toLong(), saved.length())
        assertArrayEquals(declared, FileInputStream(saved).use { it.readBytes() })
    }

    @Test
    fun `writeTo returns null and removes partial file when stream ends earlier than declared`() {
        val dir = tempDir()
        val content = "short".toByteArray()

        val saved = FileSaver.writeTo(dir, "short.bin", 100L, ByteArrayInputStream(content))

        // Недописанный файл не считается успехом и не остаётся на диске
        assertNull(saved)
        assertTrue(!File(dir, "short.bin").exists())
    }

    @Test
    fun `writeTo handles file larger than buffer size`() {
        val dir = tempDir()
        val content = ByteArray(Protocol.BUFFER_SIZE * 2 + 123) { (it % 251).toByte() }

        val saved = requireNotNull(FileSaver.writeTo(dir, "large.bin", content.size.toLong(), ByteArrayInputStream(content)))

        assertEquals(content.size.toLong(), saved.length())
        assertArrayEquals(content, FileInputStream(saved).use { it.readBytes() })
    }

    @Test
    fun `writeTo keeps both files when name repeats`() {
        val dir = tempDir()
        val first = "first".toByteArray()
        val second = "second".toByteArray()

        val savedFirst = requireNotNull(
            FileSaver.writeTo(dir, "photo.png", first.size.toLong(), ByteArrayInputStream(first))
        )
        val savedSecond = requireNotNull(
            FileSaver.writeTo(dir, "photo.png", second.size.toLong(), ByteArrayInputStream(second))
        )

        assertEquals("photo.png", savedFirst.name)
        assertEquals("photo (1).png", savedSecond.name)
        assertArrayEquals(first, FileInputStream(savedFirst).use { it.readBytes() })
        assertArrayEquals(second, FileInputStream(savedSecond).use { it.readBytes() })
    }

    @Test
    fun `writeTo numbers names without extension`() {
        val dir = tempDir()
        requireNotNull(FileSaver.writeTo(dir, "README", 1L, ByteArrayInputStream(byteArrayOf(1))))
        val second = requireNotNull(FileSaver.writeTo(dir, "README", 1L, ByteArrayInputStream(byteArrayOf(2))))

        assertEquals("README (1)", second.name)
    }

    @Test
    fun `writeTo treats dotfile as a whole name`() {
        val dir = tempDir()
        requireNotNull(FileSaver.writeTo(dir, ".env", 1L, ByteArrayInputStream(byteArrayOf(1))))
        val second = requireNotNull(FileSaver.writeTo(dir, ".env", 1L, ByteArrayInputStream(byteArrayOf(2))))

        assertEquals(".env (1)", second.name)
    }

    @Test
    fun `writeTo creates target directory if missing`() {
        val dir = File(tempDir(), "nested/Download")

        val saved = requireNotNull(FileSaver.writeTo(dir, "a.bin", 3L, ByteArrayInputStream("abc".toByteArray())))

        assertTrue(dir.exists())
        assertTrue(saved.exists())
    }
}
