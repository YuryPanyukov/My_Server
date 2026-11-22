package Code_SyS.nas_server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Перелив ответа сервера в файл: полнота данных и прогресс. */
class DownloadStreamTest {

    @Test
    fun `copies every byte and reports progress`() {
        val payload = ByteArray(HostDownloader.BUFFER_SIZE * 2 + 7) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val fractions = mutableListOf<Float>()

        val complete = copyStream(
            input = ByteArrayInputStream(payload),
            out = out,
            total = payload.size.toLong(),
        ) { fractions.add(it) }

        assertTrue(complete)
        assertArrayEquals(payload, out.toByteArray())
        assertTrue("прогресс должен дойти до конца", fractions.last() == 1f)
        assertEquals("доля не выходит за пределы", fractions.sorted(), fractions)
    }

    @Test
    fun `detects truncated response`() {
        val payload = ByteArray(100) { 7 }
        val out = ByteArrayOutputStream()

        val complete = copyStream(ByteArrayInputStream(payload), out, total = 150L) {}

        assertFalse("файл короче обещанного — сохранять нельзя", complete)
        assertEquals(100, out.size())
    }

    @Test
    fun `accepts response without content length`() {
        val payload = "текст".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val fractions = mutableListOf<Float>()

        val complete = copyStream(ByteArrayInputStream(payload), out, total = -1L) {
            fractions.add(it)
        }

        assertTrue(complete)
        assertArrayEquals(payload, out.toByteArray())
        assertEquals(listOf(-1f), fractions)
    }
}
