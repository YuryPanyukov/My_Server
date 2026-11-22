package Code_SyS.nas_server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Дерево папки приёма: пути, вложенность, создание, перемещение и удаление. */
class ReceivedStorageTest {

    private lateinit var root: File
    private lateinit var storage: ReceivedStorage

    @Before
    fun setUp() {
        root = Files.createTempDirectory("received-storage").toFile()
        storage = ReceivedStorage { root }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // --- Разбор путей -------------------------------------------------------

    @Test
    fun `normalizes nested relative paths`() {
        assertEquals("", StoragePaths.normalize(null))
        assertEquals("", StoragePaths.normalize("/"))
        assertEquals("photos/2026", StoragePaths.normalize("/photos/2026/"))
        assertEquals("photos/2026", StoragePaths.normalize("photos\\2026"))
    }

    @Test
    fun `rejects traversal and invalid paths`() {
        assertNull(StoragePaths.normalize(".."))
        assertNull(StoragePaths.normalize("photos/../secret"))
        assertNull(StoragePaths.normalize("photos/./x"))
        assertNull(StoragePaths.normalize("photos//x"))
        assertNull(StoragePaths.normalize("photos/\u0000x"))
    }

    @Test
    fun `sanitizes names and strips paths`() {
        assertEquals("photo.jpg", StoragePaths.sanitizeName("../../photo.jpg"))
        assertEquals("photo.jpg", StoragePaths.sanitizeName("""C:\temp\photo.jpg"""))
        assertNull(StoragePaths.sanitizeName("   "))
        assertNull(StoragePaths.sanitizeName("."))
        assertNull(StoragePaths.sanitizeName(".."))
    }

    @Test
    fun `resolve keeps files inside the root only`() {
        val inside = File(root, "photos/a.jpg").apply {
            parentFile?.mkdirs()
            writeText("x")
        }

        assertEquals(inside.canonicalFile, storage.resolve("photos/a.jpg"))
        assertNull(storage.resolve("../" + inside.name))
        // Абсолютный URL-путь трактуется как путь от корня и не выходит наружу.
        val absolute = storage.resolve("/abs/path")
        assertTrue(absolute == null || absolute.canonicalPath.startsWith(root.canonicalPath))
    }

    // --- Список -------------------------------------------------------------

    @Test
    fun `lists folders first and files newest first`() {
        File(root, "z-folder").mkdirs()
        File(root, "a-folder").mkdirs()
        val older = File(root, "old.txt").apply { writeText("old") }
        val newer = File(root, "new.txt").apply { writeText("new") }
        older.setLastModified(1_000L)
        newer.setLastModified(2_000L)

        val entries = storage.entries("")

        assertEquals(listOf("a-folder", "z-folder", "new.txt", "old.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals("new.txt", entries[2].path)
        assertEquals(3L, entries[2].size)
    }

    @Test
    fun `lists nested folder contents`() {
        File(root, "photos/2026").mkdirs()
        File(root, "photos/2026/a.jpg").writeText("jpeg")

        val entries = storage.entries("photos/2026")

        assertEquals(1, entries.size)
        assertEquals("photos/2026/a.jpg", entries.first().path)
    }

    @Test
    fun `walks all nested files`() {
        File(root, "photos/2026").mkdirs()
        File(root, "photos/2026/a.jpg").writeText("a")
        File(root, "notes.txt").writeText("b")

        val names = storage.allFiles().map { it.name }.toSet()

        assertEquals(setOf("a.jpg", "notes.txt"), names)
    }

    @Test
    fun `lists folders for the move dialog`() {
        File(root, "photos/2026").mkdirs()
        File(root, "music").mkdirs()

        assertEquals(listOf("music", "photos", "photos/2026"), storage.folders())
    }

    // --- Создание папок -----------------------------------------------------

    @Test
    fun `creates folder in root and nested folder`() {
        val first = requireNotNull(storage.createFolder("", "Фото"))
        assertEquals("Фото", first.name)
        assertEquals("Фото", first.path)
        assertTrue(File(root, "Фото").isDirectory)

        val second = requireNotNull(storage.createFolder("Фото", "2026"))
        assertEquals("Фото/2026", second.path)
        assertTrue(File(root, "Фото/2026").isDirectory)
    }

    @Test
    fun `does not overwrite an existing folder name`() {
        requireNotNull(storage.createFolder("", "Новая папка"))
        val second = requireNotNull(storage.createFolder("", "Новая папка"))

        assertEquals("Новая папка (1)", second.name)
    }

    @Test
    fun `does not create folder outside storage`() {
        assertNull(storage.createFolder("../outside", "x"))
        assertFalse(File(root.parentFile, "outside/x").exists())
    }

    // --- Переименование и перемещение --------------------------------------

    @Test
    fun `renames file`() {
        File(root, "old.txt").writeText("data")

        val renamed = requireNotNull(storage.rename("old.txt", "new.txt"))

        assertEquals("new.txt", renamed.path)
        assertTrue(File(root, "new.txt").exists())
        assertFalse(File(root, "old.txt").exists())
    }

    @Test
    fun `rename refuses an occupied name`() {
        File(root, "a.txt").writeText("a")
        File(root, "b.txt").writeText("b")

        assertNull(storage.rename("a.txt", "b.txt"))
        assertEquals("a", File(root, "a.txt").readText())
        assertEquals("b", File(root, "b.txt").readText())
    }

    @Test
    fun `moves file into a nested folder`() {
        File(root, "photos").mkdirs()
        File(root, "a.jpg").writeText("jpeg")

        val moved = requireNotNull(storage.move("a.jpg", "photos"))

        assertEquals("photos/a.jpg", moved.path)
        assertTrue(File(root, "photos/a.jpg").exists())
        assertFalse(File(root, "a.jpg").exists())
    }

    @Test
    fun `moves folder with its contents`() {
        File(root, "photos/2026").mkdirs()
        File(root, "photos/2026/a.jpg").writeText("jpeg")
        File(root, "archive").mkdirs()

        val moved = requireNotNull(storage.move("photos", "archive"))

        assertEquals("archive/photos", moved.path)
        assertTrue(File(root, "archive/photos/2026/a.jpg").exists())
    }

    @Test
    fun `refuses moving a folder into itself`() {
        File(root, "photos/2026").mkdirs()

        assertNull(storage.move("photos", "photos"))
        assertNull(storage.move("photos", "photos/2026"))
        assertTrue(File(root, "photos/2026").isDirectory)
    }

    @Test
    fun `refuses moving onto an existing name`() {
        File(root, "a.txt").writeText("a")
        File(root, "folder").mkdirs()
        File(root, "folder/a.txt").writeText("b")

        assertNull(storage.move("a.txt", "folder"))
        assertEquals("a", File(root, "a.txt").readText())
    }

    // --- Удаление -----------------------------------------------------------

    @Test
    fun `deletes file`() {
        File(root, "gone.txt").writeText("x")

        assertTrue(storage.delete("gone.txt"))
        assertFalse(File(root, "gone.txt").exists())
    }

    @Test
    fun `deletes folder with all contents`() {
        File(root, "photos/2026").mkdirs()
        File(root, "photos/2026/a.jpg").writeText("jpeg")

        assertTrue(storage.delete("photos"))
        assertFalse(File(root, "photos").exists())
    }

    @Test
    fun `never deletes storage root`() {
        File(root, "keep.txt").writeText("x")

        assertFalse(storage.delete(""))
        assertTrue(File(root, "keep.txt").exists())
    }

    @Test
    fun `delete cannot escape the storage root`() {
        val outside = File(root.parentFile, "outside-keep.txt").apply { writeText("keep") }

        assertFalse(storage.delete("../outside-keep.txt"))
        assertTrue(outside.exists())
        outside.delete()
    }

    // --- Корзина ------------------------------------------------------------

    @Test
    fun `trash hides the file from listings and keeps it recoverable`() {
        File(root, "note.txt").writeText("hello")

        val trashed = requireNotNull(storage.trash("note.txt"))

        assertEquals("note.txt", trashed.name)
        assertEquals("note.txt", trashed.originalPath)
        assertFalse(File(root, "note.txt").exists())
        assertTrue(storage.entries("").isEmpty())
        assertTrue(storage.allFiles().isEmpty())
        assertEquals(1, storage.trashEntries().size)
    }

    @Test
    fun `restores a trashed file to its original path`() {
        File(root, "photos/a.jpg").apply { parentFile?.mkdirs() }.writeText("jpeg")
        val trashed = requireNotNull(storage.trash("photos/a.jpg"))

        val restored = requireNotNull(storage.restore(trashed.id))

        assertEquals("photos/a.jpg", restored.path)
        assertEquals("jpeg", File(root, "photos/a.jpg").readText())
        assertTrue(storage.trashEntries().isEmpty())
    }

    @Test
    fun `restores a trashed folder with its contents`() {
        File(root, "photos/2026/a.jpg").apply { parentFile?.mkdirs() }.writeText("jpeg")
        val trashed = requireNotNull(storage.trash("photos"))

        val restored = requireNotNull(storage.restore(trashed.id))

        assertEquals("photos", restored.path)
        assertEquals("jpeg", File(root, "photos/2026/a.jpg").readText())
    }

    @Test
    fun `restore keeps both files when the original name is taken`() {
        File(root, "a.txt").writeText("old")
        val trashed = requireNotNull(storage.trash("a.txt"))
        File(root, "a.txt").writeText("new")

        val restored = requireNotNull(storage.restore(trashed.id))

        assertEquals("a (1).txt", restored.name)
        assertEquals("new", File(root, "a.txt").readText())
        assertEquals("old", File(root, "a (1).txt").readText())
    }

    @Test
    fun `restore recreates a deleted parent folder`() {
        File(root, "photos/a.jpg").apply { parentFile?.mkdirs() }.writeText("jpeg")
        val trashed = requireNotNull(storage.trash("photos/a.jpg"))
        File(root, "photos").deleteRecursively()

        val restored = requireNotNull(storage.restore(trashed.id))

        assertEquals("photos/a.jpg", restored.path)
        assertEquals("jpeg", File(root, "photos/a.jpg").readText())
    }

    @Test
    fun `trash folder is hidden and cannot be browsed`() {
        File(root, "a.txt").writeText("a")
        requireNotNull(storage.trash("a.txt"))

        assertTrue(File(root, ".trash").isDirectory)
        assertTrue(storage.entries("").none { it.name == ".trash" })
        assertTrue(storage.folders().none { it.startsWith(".trash") })
        assertNull(storage.resolve(".trash"))
        assertNull(storage.entry(".trash/meta"))
        assertFalse(storage.delete(".trash"))
    }

    @Test
    fun `refuses to trash the root or the trash itself`() {
        File(root, "keep.txt").writeText("x")

        assertNull(storage.trash(""))
        assertNull(storage.trash(".trash"))
        assertTrue(File(root, "keep.txt").exists())
    }

    @Test
    fun `forgets a trashed item after deleting it forever`() {
        File(root, "a.txt").writeText("a")
        val trashed = requireNotNull(storage.trash("a.txt"))

        assertTrue(storage.deleteFromTrash(trashed.id))
        assertTrue(storage.trashEntries().isEmpty())
        assertNull(storage.restore(trashed.id))
    }

    @Test
    fun `empties the whole trash`() {
        File(root, "a.txt").writeText("a")
        File(root, "b.txt").writeText("b")
        requireNotNull(storage.trash("a.txt"))
        requireNotNull(storage.trash("b.txt"))

        assertEquals(2, storage.emptyTrash())
        assertTrue(storage.trashEntries().isEmpty())
        assertEquals(0, storage.trashSize())
    }

    @Test
    fun `entry describes a file`() {
        File(root, "note.txt").writeText("hello")

        val entry = requireNotNull(storage.entry("note.txt"))

        assertEquals("note.txt", entry.name)
        assertEquals(5L, entry.size)
        assertFalse(entry.isDirectory)
    }
}
