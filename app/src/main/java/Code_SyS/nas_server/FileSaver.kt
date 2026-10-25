package Code_SyS.nas_server

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class FileSaver(private val context: Context) {

    /**
     * Сохраняет файл в приватную внешнюю папку приложения
     * (Android/data/<package>/files/Download). Разрешение WRITE_EXTERNAL_STORAGE не требуется.
     */
    fun save(fileName: String, fileSize: Long, input: InputStream): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return writeTo(dir, fileName, fileSize, input)
    }

    /**
     * Файлы, уже принятые в папку приложения, от новых к старым.
     * Используется HTTP-страницей для списка и просмотра.
     */
    fun listFiles(): List<File> {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    companion object {
        /**
         * Чистая функция сохранения: читает из [input] ровно [fileSize] байт
         * и записывает их в [dir] под санитизированным именем [fileName].
         *
         * Если файл с таким именем уже есть, создаётся `photo (1).jpg`:
         * молча затирать принятые файлы нельзя — второй телефон легко
         * отправит снимок с тем же именем.
         *
         * Читается именно [fileSize] байт, а не до EOF: клиент после отправки
         * файла держит соединение открытым и ждёт ответа, поэтому чтение до EOF
         * привело бы к зависанию.
         */
        fun writeTo(dir: File, fileName: String, fileSize: Long, input: InputStream): File? {
            val safeName = File(fileName).name
            if (safeName.isEmpty()) return null
            if (fileSize < 0) return null
            if (!dir.exists() && !dir.mkdirs()) return null

            val outFile = createUniqueFile(dir, safeName)
            var receivedAll = false
            try {
                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(Protocol.BUFFER_SIZE)
                    var remaining = fileSize
                    while (remaining > 0) {
                        val toRead = minOf(remaining, Protocol.BUFFER_SIZE.toLong()).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read < 0) break // клиент прислал меньше данных, чем объявил
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    receivedAll = remaining == 0L
                    out.flush()
                }
            } catch (e: IOException) {
                outFile.delete()
                throw e
            }

            // Недописанный файл не должен ни оставаться на диске, ни считаться успехом.
            if (!receivedAll) {
                outFile.delete()
                return null
            }
            return outFile
        }

        /**
         * Атомарно резервирует свободное имя: `createNewFile` не даёт двум
         * одновременным загрузкам выбрать один и тот же путь.
         */
        private fun createUniqueFile(dir: File, name: String): File {
            val dot = name.lastIndexOf('.')
            val hasExtension = dot > 0
            val base = if (hasExtension) name.substring(0, dot) else name
            val extension = if (hasExtension) name.substring(dot) else ""

            var index = 0
            while (true) {
                val candidateName = if (index == 0) name else "$base ($index)$extension"
                val candidate = File(dir, candidateName)
                if (candidate.createNewFile()) return candidate
                index++
            }
        }
    }
}
