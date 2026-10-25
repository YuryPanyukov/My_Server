package Code_SyS.nas_server

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Бинарный протокол приёма файлов:
 *
 *   [2 байта: длина имени файла N (big-endian, unsigned short)]
 *   [N байт: имя файла в UTF-8]
 *   [8 байт: размер файла в байтах (long, big-endian)]
 *   [M байт: содержимое файла]
 */
object Protocol {
    const val PORT = 8080
    const val HEADER_NAME_LENGTH_SIZE = 2 // unsigned short, big-endian
    const val HEADER_FILE_SIZE_SIZE = 8   // long, big-endian
    const val BUFFER_SIZE = 65536
    const val MAX_NAME_LENGTH = 0xFFFF

    /** Разобранный заголовок файла. */
    data class FileHeader(val fileName: String, val fileSize: Long)

    /** Записывает заголовок (имя + размер) в поток. */
    fun writeHeader(out: DataOutputStream, fileName: String, fileSize: Long) {
        val nameBytes = fileName.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size <= MAX_NAME_LENGTH) {
            "Имя файла длиннее $MAX_NAME_LENGTH байт: ${nameBytes.size}"
        }
        require(fileSize >= 0) { "Отрицательный размер файла: $fileSize" }

        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeLong(fileSize)
        out.flush()
    }

    /** Читает заголовок (имя + размер) из потока. */
    fun readHeader(input: DataInputStream): FileHeader {
        val nameLength = input.readUnsignedShort()
        val nameBytes = ByteArray(nameLength)
        input.readFully(nameBytes)
        val fileName = String(nameBytes, StandardCharsets.UTF_8)
        val fileSize = input.readLong()
        return FileHeader(fileName, fileSize)
    }
}
