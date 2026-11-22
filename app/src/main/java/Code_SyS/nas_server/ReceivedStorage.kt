package Code_SyS.nas_server

import java.io.File
import java.util.Locale

/**
 * Запись папки приёма: файл или каталог. [path] — путь относительно корня
 * хранилища, всегда через `/` и без ведущего слэша.
 */
data class StorageEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * Элемент корзины: удалённый файл или папка, ожидающие восстановления.
 * [originalPath] — путь, из которого элемент удалили.
 */
data class TrashEntry(
    val id: String,
    val name: String,
    val originalPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val deletedAt: Long,
)

/**
 * Разбор относительных путей хранилища. Чистые функции без Android API:
 * их проверяют JVM-тесты, а сервер, приложение и клиент используют одно правило.
 *
 * Путь приходит из URL или из интерфейса, поэтому `..`, абсолютные пути и
 * слишком длинные имена отбрасываются ещё до обращения к диску.
 */
internal object StoragePaths {

    /** Больше сегментов не бывает в реальной папке, а защита от рекурсии нужна. */
    const val MAX_DEPTH = 16

    /** Длина одного имени в файловых системах Android. */
    private const val MAX_NAME_LENGTH = 255

    /**
     * Нормализует относительный путь. `null` — путь недопустим.
     * Пустая строка — корень хранилища.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return ""
        var value = raw.replace('\\', '/')
        while (value.startsWith("/")) value = value.substring(1)
        while (value.endsWith("/")) value = value.dropLast(1)
        if (value.isEmpty()) return ""
        if (value.length > MAX_DEPTH * (MAX_NAME_LENGTH + 1)) return null
        if (value.indexOf('\u0000') >= 0) return null

        val segments = value.split('/')
        if (segments.size > MAX_DEPTH) return null
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") return null
            if (segment.length > MAX_NAME_LENGTH) return null
        }
        return segments.joinToString("/")
    }

    /**
     * Оставляет от пользовательского ввода только имя: `folder/name` и
     * `folder\name` превращаются в `name`, управляющие символы убираются.
     * `null` — имя пустое или недопустимое.
     */
    fun sanitizeName(raw: String?): String? {
        if (raw == null) return null
        val withoutPath = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = withoutPath.filter { it >= ' ' && it != '/' && it != '\\' }.trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        if (cleaned.length > MAX_NAME_LENGTH) return null
        return cleaned
    }

    /** Склеивает родительский путь и имя, проверяя оба. */
    fun join(parent: String, name: String): String? {
        val normalizedParent = normalize(parent) ?: return null
        val safeName = sanitizeName(name) ?: return null
        return if (normalizedParent.isEmpty()) safeName else "$normalizedParent/$safeName"
    }

    /** Родительский путь; для корня и первого уровня — пустая строка. */
    fun parent(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index <= 0) "" else path.substring(0, index)
    }

    fun name(path: String): String = path.substringAfterLast('/')

    /** [path] совпадает с [directory] или лежит внутри неё. */
    fun isSameOrInside(path: String, directory: String): Boolean =
        directory.isEmpty() || path == directory || path.startsWith("$directory/")

    /** Путь [file] относительно [root] в формате URL; `null` — файл вне корня. */
    fun relativePath(root: File, file: File): String? {
        val rootPath = try {
            root.canonicalPath
        } catch (_: Exception) {
            return null
        }
        val filePath = try {
            file.canonicalPath
        } catch (_: Exception) {
            return null
        }
        if (filePath == rootPath) return ""
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        if (!filePath.startsWith(prefix)) return null
        return filePath.removePrefix(prefix).replace(File.separatorChar, '/')
    }
}

/**
 * Папка принятых файлов как дерево каталогов.
 *
 * Класс не знает, где лежит корень: его даёт [rootProvider]. Так одна и та же
 * логика работает в сервисе (приватная папка приложения) и в JVM-тестах
 * (временный каталог), без Android API.
 *
 * Любая операция заново разбирает путь и проверяет канонический путь: клиент
 * не может выйти за пределы хранилища ни через `..`, ни через абсолютный путь.
 */
class ReceivedStorage(private val rootProvider: () -> File?) {

    /** Корень хранилища либо `null`, если внешнее хранилище недоступно. */
    fun root(): File? = rootProvider()

    /**
     * Служебная папка корзины. Она скрыта из списков и недоступна по обычным
     * путям: клиент не должен ни читать чужие удалённые файлы, ни ходить по
     * корзине как по папке хранилища.
     */
    private fun trashDirectory(): File? {
        val root = root() ?: return null
        val dir = File(root, TRASH_DIR)
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            dir.canonicalFile
        } catch (_: Exception) {
            null
        }
    }

    private fun isReserved(relative: String): Boolean =
        relative == TRASH_DIR || relative.startsWith("$TRASH_DIR/")

    /** Содержимое папки: сначала каталоги по алфавиту, затем файлы от новых к старым. */
    fun entries(path: String?): List<StorageEntry> {
        val root = root() ?: return emptyList()
        val directory = resolve(path) ?: return emptyList()
        if (!directory.isDirectory) return emptyList()

        val children = try {
            directory.listFiles()
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        // Служебные файлы (корзина и описания видео) не показываем как файлы.
        val entries = children
            .filter { it.name != TRASH_DIR && !MediaSidecar.isSidecar(it.name) }
            .mapNotNull { child ->
            val relative = StoragePaths.relativePath(root, child) ?: return@mapNotNull null
            StorageEntry(
                name = child.name,
                path = relative,
                isDirectory = child.isDirectory,
                size = if (child.isFile) child.length() else 0L,
                modified = child.lastModified(),
            )
        }

        return entries.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) } +
            entries.filterNot { it.isDirectory }.sortedByDescending { it.modified }
    }

    /** Все файлы во всех вложенных папках; нужны галерее, поиску и хранилищу. */
    fun allFiles(): List<File> {
        val root = resolve("") ?: return emptyList()
        val result = ArrayList<File>()

        fun walk(directory: File, depth: Int) {
            if (depth > StoragePaths.MAX_DEPTH) return
            val children = try {
                directory.listFiles()
            } catch (_: Exception) {
                null
            } ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (depth == 0 && child.name == TRASH_DIR) continue
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    if (MediaSidecar.isSidecar(child.name)) continue
                    result.add(child)
                }
            }
        }

        walk(root, 0)
        return result.sortedByDescending { it.lastModified() }
    }

    /** Все вложенные каталоги, включая корень: список для диалога «Переместить». */
    fun folders(): List<String> {
        val root = resolve("") ?: return emptyList()
        val result = mutableListOf<String>()

        fun walk(directory: File, prefix: String, depth: Int) {
            if (depth > StoragePaths.MAX_DEPTH) return
            val children = try {
                directory.listFiles()
            } catch (_: Exception) {
                null
            } ?: return
            for (child in children) {
                if (!child.isDirectory) continue
                if (depth == 0 && child.name == TRASH_DIR) continue
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                result.add(path)
                walk(child, path, depth + 1)
            }
        }

        walk(root, "", 0)
        return result.sortedBy { it.lowercase(Locale.ROOT) }
    }

    /** Разрешённый файл или каталог по относительному пути. */
    fun resolve(path: String?): File? {
        val root = root() ?: return null
        val relative = StoragePaths.normalize(path) ?: return null
        if (isReserved(relative)) return null

        val candidate = if (relative.isEmpty()) root else File(root, relative)
        val canonicalRoot = try {
            root.canonicalFile
        } catch (_: Exception) {
            return null
        }
        val canonical = try {
            candidate.canonicalFile
        } catch (_: Exception) {
            return null
        }
        if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            return null
        }
        return canonical
    }

    /** Запись для пути либо `null`, если файла/папки нет. */
    fun entry(path: String?): StorageEntry? {
        val root = root() ?: return null
        val file = resolve(path) ?: return null
        if (!file.exists()) return null
        val relative = StoragePaths.relativePath(root, file) ?: return null
        return StorageEntry(
            name = file.name,
            path = relative,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            modified = file.lastModified(),
        )
    }

    /**
     * Создаёт папку [name] внутри [parentPath]. Существующее имя не трогаем:
     * получается «Новая папка (1)», как и у файлов с одинаковыми именами.
     */
    fun createFolder(parentPath: String?, name: String): StorageEntry? {
        val parent = resolve(parentPath) ?: return null
        if (!parent.isDirectory) return null
        val safeName = StoragePaths.sanitizeName(name) ?: return null

        val target = uniqueChild(parent, safeName)
        val created = try {
            target.mkdirs()
        } catch (_: Exception) {
            false
        }
        if (!created) return null
        return entryOf(target)
    }

    /** Переименовывает файл или папку. Занятое имя — `null` (не перезаписываем). */
    fun rename(path: String, newName: String): StorageEntry? {
        val source = resolve(path) ?: return null
        if (StoragePaths.normalize(path).isNullOrEmpty()) return null
        val safeName = StoragePaths.sanitizeName(newName) ?: return null
        if (safeName == source.name) return entryOf(source)

        val parent = source.parentFile ?: return null
        val target = File(parent, safeName)
        if (target.exists()) return null
        val renamed = try {
            source.renameTo(target)
        } catch (_: Exception) {
            false
        }
        if (!renamed) return null
        moveSidecar(source, target)
        return entryOf(target)
    }

    /**
     * Перемещает файл или папку в [targetDirPath]. Папку нельзя переместить
     * внутрь самой себя; занятое имя не перезаписывается.
     */
    fun move(path: String, targetDirPath: String?): StorageEntry? {
        val source = resolve(path) ?: return null
        val sourcePath = StoragePaths.normalize(path) ?: return null
        if (sourcePath.isEmpty()) return null

        val targetDir = resolve(targetDirPath) ?: return null
        if (!targetDir.isDirectory) return null

        val targetDirRelative = StoragePaths.relativePath(root() ?: return null, targetDir) ?: return null
        if (source.isDirectory && StoragePaths.isSameOrInside(targetDirRelative, sourcePath)) return null

        val target = File(targetDir, source.name)
        if (target.exists()) return null
        val moved = try {
            source.renameTo(target)
        } catch (_: Exception) {
            false
        }
        if (!moved) return null
        moveSidecar(source, target)
        return entryOf(target)
    }

    /** Удаляет файл или папку со всем содержимым. Корень удалить нельзя. */
    fun delete(path: String): Boolean {
        val normalized = StoragePaths.normalize(path) ?: return false
        if (normalized.isEmpty()) return false
        val file = resolve(normalized) ?: return false
        val wasFile = file.isFile
        val deleted = try {
            file.deleteRecursively()
        } catch (_: Exception) {
            false
        }
        // У папки сайдкаров нет — они лежат рядом с видео внутри неё.
        if (deleted && wasFile) MediaSidecar.forVideo(file).delete()
        return deleted
    }

    // --- Корзина ------------------------------------------------------------

    /**
     * Удаляет файл или папку в корзину. Возвращает запись для кнопки «Отменить»
     * либо `null`, если путь недопустим или перемещение не удалось.
     *
     * Данные не стираются: элемент переезжает в `.trash/<id>/item`, а рядом
     * сохраняется исходный путь. Так восстановление не зависит от содержимого
     * файла и работает одинаково для файлов и папок.
     */
    fun trash(path: String): TrashEntry? {
        val normalized = StoragePaths.normalize(path) ?: return null
        if (normalized.isEmpty() || isReserved(normalized)) return null
        val source = resolve(normalized) ?: return null
        val trashRoot = trashDirectory() ?: return null
        val slot = uniqueTrashSlot(trashRoot) ?: return null

        val isDirectory = source.isDirectory
        val size = if (source.isFile) source.length() else 0L
        val payload = File(slot, TRASH_PAYLOAD)
        val moved = try {
            source.renameTo(payload)
        } catch (_: Exception) {
            false
        }
        if (!moved) {
            slot.deleteRecursively()
            return null
        }
        // Описание видео едет в корзину вместе с файлом.
        if (!isDirectory) {
            val sidecar = MediaSidecar.forVideo(source)
            if (sidecar.isFile) sidecar.renameTo(File(slot, TRASH_SIDECAR))
        }

        val deletedAt = System.currentTimeMillis()
        val metaWritten = try {
            File(slot, TRASH_META).writeText("$deletedAt\n$normalized", Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
        if (!metaWritten) {
            // Без исходного пути восстановить элемент нельзя — не теряем его.
            payload.renameTo(source)
            val sidecar = File(slot, TRASH_SIDECAR)
            if (sidecar.isFile) sidecar.renameTo(MediaSidecar.forVideo(source))
            slot.deleteRecursively()
            return null
        }

        return TrashEntry(
            id = slot.name,
            name = source.name,
            originalPath = normalized,
            isDirectory = isDirectory,
            size = size,
            deletedAt = deletedAt,
        )
    }

    /** Содержимое корзины, от новых удалений к старым. */
    fun trashEntries(): List<TrashEntry> {
        val trashRoot = trashDirectory() ?: return emptyList()
        val slots = try {
            trashRoot.listFiles()
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return slots.mapNotNull { slot ->
            if (!slot.isDirectory) return@mapNotNull null
            val meta = readTrashMeta(slot) ?: return@mapNotNull null
            val payload = File(slot, TRASH_PAYLOAD)
            if (!payload.exists()) return@mapNotNull null
            TrashEntry(
                id = slot.name,
                name = StoragePaths.name(meta.second),
                originalPath = meta.second,
                isDirectory = payload.isDirectory,
                size = if (payload.isFile) payload.length() else 0L,
                deletedAt = meta.first,
            )
        }.sortedByDescending { it.deletedAt }
    }

    /** Восстанавливает элемент на исходное место. Занятое имя получает суффикс. */
    fun restore(id: String): StorageEntry? {
        val slot = trashSlot(id) ?: return null
        val meta = readTrashMeta(slot) ?: return null
        val payload = File(slot, TRASH_PAYLOAD)
        if (!payload.exists()) return null

        val root = root() ?: return null
        val originalPath = meta.second
        val parentPath = StoragePaths.parent(originalPath)
        val parent: File = if (parentPath.isEmpty()) {
            resolve("") ?: return null
        } else {
            val existing = resolve(parentPath)
            if (existing != null && existing.isDirectory) {
                existing
            } else {
                // Папку могли удалить после файла — восстанавливаем и её.
                val recreated = File(root, parentPath)
                if (!recreated.mkdirs() && !recreated.isDirectory) return null
                recreated
            }
        }
        if (!parent.isDirectory) return null

        val target = File(parent, StoragePaths.name(originalPath))
        val destination = if (target.exists()) uniqueChild(parent, target.name) else target
        val moved = try {
            payload.renameTo(destination)
        } catch (_: Exception) {
            false
        }
        if (!moved) return null

        // Возвращаем и описание видео, если оно ездило в корзину вместе с ним.
        if (!destination.isDirectory) {
            val sidecar = File(slot, TRASH_SIDECAR)
            if (sidecar.isFile) sidecar.renameTo(MediaSidecar.forVideo(destination))
        }

        slot.deleteRecursively()
        return entryOf(destination)
    }

    /** Удаляет один элемент корзины навсегда. */
    fun deleteFromTrash(id: String): Boolean {
        val slot = trashSlot(id) ?: return false
        return try {
            slot.deleteRecursively()
        } catch (_: Exception) {
            false
        }
    }

    /** Очищает корзину и возвращает число удалённых элементов. */
    fun emptyTrash(): Int {
        var deleted = 0
        for (entry in trashEntries()) {
            if (deleteFromTrash(entry.id)) deleted++
        }
        return deleted
    }

    /** Размер корзины для ссылки «Корзина (N)» на странице. */
    fun trashSize(): Int = trashEntries().size

    private fun trashSlot(id: String): File? {
        val safe = StoragePaths.sanitizeName(id) ?: return null
        if (safe != id) return null
        val trashRoot = trashDirectory() ?: return null
        val slot = try {
            File(trashRoot, safe).canonicalFile
        } catch (_: Exception) {
            return null
        }
        if (slot.parentFile != trashRoot || !slot.isDirectory) return null
        return slot
    }

    private fun readTrashMeta(slot: File): Pair<Long, String>? {
        val lines = try {
            File(slot, TRASH_META).readText(Charsets.UTF_8).split('\n')
        } catch (_: Exception) {
            return null
        }
        val deletedAt = lines.getOrNull(0)?.toLongOrNull() ?: return null
        val originalPath = lines.getOrNull(1) ?: return null
        if (StoragePaths.normalize(originalPath) != originalPath) return null
        if (originalPath.isEmpty()) return null
        return deletedAt to originalPath
    }

    /** Переносит сайдкар описания вслед за переименованным/перемещённым видео. */
    private fun moveSidecar(from: File, to: File) {
        if (from.isDirectory) return
        val old = MediaSidecar.forVideo(from)
        if (!old.isFile) return
        try {
            old.renameTo(MediaSidecar.forVideo(to))
        } catch (_: Exception) {
            // Описание не критично: видео уже на новом месте.
        }
    }

    /** Свободный слот корзины: время удаления, при совпадении — с суффиксом. */
    private fun uniqueTrashSlot(trashRoot: File): File? {
        val base = System.currentTimeMillis()
        for (index in 0 until MAX_TRASH_SLOT_ATTEMPTS) {
            val name = if (index == 0) base.toString() else "$base-$index"
            val candidate = File(trashRoot, name)
            if (candidate.mkdirs()) return candidate
        }
        return null
    }

    private fun entryOf(file: File): StorageEntry? {
        val root = root() ?: return null
        val relative = StoragePaths.relativePath(root, file) ?: return null
        return StorageEntry(
            name = file.name,
            path = relative,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            modified = file.lastModified(),
        )
    }

    private companion object {
        /** Служебная папка корзины в корне хранилища. */
        const val TRASH_DIR = ".trash"

        /** Сам удалённый файл или папка внутри слота корзины. */
        const val TRASH_PAYLOAD = "item"

        /** Исходный путь и время удаления (две строки). */
        const val TRASH_META = "meta"

        /** Описание видео, лежавшее рядом с удалённым файлом. */
        const val TRASH_SIDECAR = "library"

        /** Сколько раз пробовать занять слот, если время удаления совпало. */
        const val MAX_TRASH_SLOT_ATTEMPTS = 100
    }

    /** Свободное имя в папке: `name`, `name (1)`, `name (2)`… */
    private fun uniqueChild(parent: File, name: String): File =
        FileNames.numbered(name).map { File(parent, it) }.first { !it.exists() }
}
