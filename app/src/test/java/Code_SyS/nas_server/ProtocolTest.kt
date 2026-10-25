package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ProtocolTest {

    private fun encodeHeader(fileName: String, fileSize: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { Protocol.writeHeader(it, fileName, fileSize) }
        return buffer.toByteArray()
    }

    private fun decodeHeader(bytes: ByteArray): Protocol.FileHeader =
        DataInputStream(ByteArrayInputStream(bytes)).use { Protocol.readHeader(it) }

    @Test
    fun `PORT constant is 8080`() {
        assertEquals(8080, Protocol.PORT)
    }

    @Test
    fun `header sizes are 2 and 8 bytes`() {
        assertEquals(2, Protocol.HEADER_NAME_LENGTH_SIZE)
        assertEquals(8, Protocol.HEADER_FILE_SIZE_SIZE)
    }

    @Test
    fun `BUFFER_SIZE is 65536`() {
        assertEquals(65536, Protocol.BUFFER_SIZE)
    }

    @Test
    fun `header roundtrip keeps name and size`() {
        val header = decodeHeader(encodeHeader("test_file.bin", 12345L))
        assertEquals("test_file.bin", header.fileName)
        assertEquals(12345L, header.fileSize)
    }

    @Test
    fun `header roundtrip keeps cyrillic name`() {
        val header = decodeHeader(encodeHeader("Отчёт за 2026 год.docx", 4096L))
        assertEquals("Отчёт за 2026 год.docx", header.fileName)
        assertEquals(4096L, header.fileSize)
    }

    @Test
    fun `header roundtrip supports empty name`() {
        val header = decodeHeader(encodeHeader("", 0L))
        assertEquals("", header.fileName)
        assertEquals(0L, header.fileSize)
    }

    @Test
    fun `header roundtrip supports size larger than 4GB`() {
        val size = (5L shl 32) + 123L
        val header = decodeHeader(encodeHeader("big.bin", size))
        assertEquals("big.bin", header.fileName)
        assertEquals(size, header.fileSize)
    }

    @Test
    fun `encoded header has expected length`() {
        val name = "abc.bin"
        val bytes = encodeHeader(name, 1L)
        assertEquals(
            Protocol.HEADER_NAME_LENGTH_SIZE + name.toByteArray(Charsets.UTF_8).size + Protocol.HEADER_FILE_SIZE_SIZE,
            bytes.size
        )
    }

    @Test
    fun `name length is written as unsigned short big endian`() {
        val bytes = encodeHeader("ab", 0L)
        val length = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        assertEquals(2, length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writeHeader rejects name longer than 65535 bytes`() {
        encodeHeader("x".repeat(Protocol.MAX_NAME_LENGTH + 1), 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writeHeader rejects negative file size`() {
        encodeHeader("a.bin", -1L)
    }

    @Test
    fun `readHeader reads exactly the header and leaves the body in the stream`() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val header = encodeHeader("body.bin", body.size.toLong())
        val stream = ByteArrayInputStream(header + body)

        val parsed = DataInputStream(stream).use { Protocol.readHeader(it) }
        assertEquals("body.bin", parsed.fileName)
        assertEquals(5L, parsed.fileSize)

        val rest = stream.readBytes()
        assertEquals(5, rest.size)
        assertTrue(body.contentEquals(rest))
    }
}
