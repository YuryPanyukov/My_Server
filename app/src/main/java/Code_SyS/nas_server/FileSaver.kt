package Code_SyS.nas_server

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class FileSaver(private val context: Context) {

    /** Корень принятых файлов: Android/data/<package>/files/Download. */
    val storageDir: File?
        get() = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    /** Та же папка как дерево каталогов: список, создание, перемещение, удаление. */
    private val storage = ReceivedStorage { storageDir }

    /**
     * Сохраняет файл в корень приватной внешней папки приложения
     * (Android/data/<package>/files/Download). Разрешение WRITE_EXTERNAL_STORAGE не требуется.
     */
    fun save(fileName: String, fileSize: Long, input: InputStream): File? =
        save(storageDir, fileName, fileSize, input)

    /**
     * Сохраняет файл в выбранную вложенную папку [path] (пустая строка — корень).
     * Несуществующая папка и путь за пределы хранилища дают `null`.
     */
    fun save(path: String, fileName: String, fileSize: Long, input: InputStream): File? {
        val dir = storage.resolve(path) ?: return null
        if (!dir.isDirectory) return null
        return save(dir, fileName, fileSize, input)
    }

    private fun save(dir: File?, fileName: String, fileSize: Long, input: InputStream): File? {
        val target = dir ?: return null
        return writeTo(target, fileName, fileSize, input)
    }

    /**
     * Все принятые файлы, включая вложенные папки, от новых к старым.
     * Используется галереей, поиском по всей папке и очередью хранилища.
     */
    fun listFiles(): List<File> = storage.allFiles()

    /** Содержимое одной папки: вложенные папки и файлы. */
    fun entries(path: String?): List<StorageEntry> = storage.entries(path)

    /** Все папки хранилища — для диалога «Переместить». */
    fun folders(): List<String> = storage.folders()

    fun createFolder(parentPath: String?, name: String): StorageEntry? =
        storage.createFolder(parentPath, name)

    fun rename(path: String, newName: String): StorageEntry? = storage.rename(path, newName)

    fun move(path: String, targetDirPath: String?): StorageEntry? = storage.move(path, targetDirPath)

    /** Удаляет файл или папку без возможности восстановления (очистка корзины). */
    fun delete(path: String): Boolean = storage.delete(path)

    /** Удаление в корзину: элемент можно вернуть кнопкой «Отменить». */
    fun trash(path: String): TrashEntry? = storage.trash(path)

    /** Содержимое корзины, от новых удалений к старым. */
    fun trashEntries(): List<TrashEntry> = storage.trashEntries()

    /** Возвращает элемент корзины на исходное место. */
    fun restore(id: String): StorageEntry? = storage.restore(id)

    /** Удаляет один элемент корзины навсегда. */
    fun deleteFromTrash(id: String): Boolean = storage.deleteFromTrash(id)

    /** Очищает корзину и возвращает число удалённых элементов. */
    fun emptyTrash(): Int = storage.emptyTrash()

    /** Разрешённый файл или папка по относительному пути. */
    fun resolve(path: String?): File? = storage.resolve(path)

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
            val candidates = FileNames.numbered(name).iterator()
            while (true) {
                val candidate = File(dir, candidates.next())
                if (candidate.createNewFile()) return candidate
            }
        }
    }
}
