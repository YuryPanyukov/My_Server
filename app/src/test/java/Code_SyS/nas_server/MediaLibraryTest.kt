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

/** Видеотека: список видео, сайдкары с описанием и их перенос вместе с файлом. */
class MediaLibraryTest {

    private lateinit var root: File
    private lateinit var library: MediaLibrary
    private lateinit var storage: ReceivedStorage

    @Before
    fun setUp() {
        root = Files.createTempDirectory("media-library").toFile()
        library = MediaLibrary { root }
        storage = ReceivedStorage { root }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun video(name: String, content: String = "video"): File =
        File(root, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    @Test
    fun `lists only videos and uses the file name as the default title`() {
        video("Отпуск 2024.mp4")
        File(root, "photo.jpg").writeText("jpeg")
        File(root, "notes.txt").writeText("text")

        val item = library.items().single()

        assertEquals("Отпуск 2024.mp4", item.name)
        assertEquals("Отпуск 2024", item.title)
        assertEquals("", item.description)
        assertFalse(item.hasDescription)
    }

    @Test
    fun `lists videos from nested folders`() {
        video("clips/2026/first.mkv")

        assertEquals("clips/2026/first.mkv", library.items().single().path)
    }

    @Test
    fun `saves title and description in a sidecar`() {
        video("trip.mp4")

        val saved = requireNotNull(library.save("trip.mp4", "  Море  ", "Первый день\nи вечер"))

        assertEquals("Море", saved.title)
        assertEquals("Первый день\nи вечер", saved.description)
        assertTrue(saved.hasDescription)
        assertTrue("сайдкар рядом с видео", File(root, "trip.mp4.library.json").isFile)

        val listed = library.items().single()
        assertEquals("Море", listed.title)
        assertEquals("Первый день\nи вечер", listed.description)
        assertTrue(listed.hasDescription)
    }

    @Test
    fun `description survives quotes slashes and unicode`() {
        video("trip.mp4")
        val text = "Он сказал \"привет\" и ушёл \\ в лес\n\tновая строка — тире 🎬"

        library.save("trip.mp4", "Заголовок \"в кавычках\"", text)

        assertEquals(text, library.items().single().description)
        assertEquals("Заголовок \"в кавычках\"", library.item("trip.mp4")?.title)
    }

    @Test
    fun `blank title falls back to the file name`() {
        video("Отпуск.mp4")

        val saved = requireNotNull(library.save("Отпуск.mp4", "   ", "описание"))

        assertEquals("Отпуск", saved.title)
        assertEquals("Отпуск", library.items().single().title)
    }

    @Test
    fun `resets the description`() {
        video("trip.mp4")
        library.save("trip.mp4", "Море", "Описание")

        assertTrue(library.reset("trip.mp4"))

        val listed = library.items().single()
        assertEquals("trip", listed.title)
        assertEquals("", listed.description)
        assertFalse(listed.hasDescription)
        assertFalse(File(root, "trip.mp4.library.json").exists())
    }

    @Test
    fun `refuses non video and unknown paths`() {
        File(root, "photo.jpg").writeText("jpeg")

        assertNull(library.save("photo.jpg", "Фото", "не видео"))
        assertNull(library.save("gone.mp4", "Нет", "файла"))
        assertNull(library.item("..%2Foutside.mp4"))
        assertNull(library.item("photo.jpg"))
    }

    @Test
    fun `renaming a video keeps its description`() {
        video("old.mp4")
        library.save("old.mp4", "Старое", "Описание")

        requireNotNull(storage.rename("old.mp4", "new.mp4"))

        val item = library.items().single()
        assertEquals("new.mp4", item.name)
        assertEquals("Старое", item.title)
        assertEquals("Описание", item.description)
    }

    @Test
    fun `moving a video keeps its description`() {
        video("clip.mp4")
        library.save("clip.mp4", "Клип", "Описание")
        requireNotNull(storage.createFolder("", "clips"))

        requireNotNull(storage.move("clip.mp4", "clips"))

        val item = library.items().single()
        assertEquals("clips/clip.mp4", item.path)
        assertEquals("Клип", item.title)
        assertTrue(File(root, "clips/clip.mp4.library.json").isFile)
    }

    @Test
    fun `trash and restore keep the description`() {
        video("clip.mp4")
        library.save("clip.mp4", "Клип", "Описание")

        val trashed = requireNotNull(storage.trash("clip.mp4"))
        assertTrue(library.items().isEmpty())

        requireNotNull(storage.restore(trashed.id))

        val item = library.items().single()
        assertEquals("Клип", item.title)
        assertEquals("Описание", item.description)
    }

    @Test
    fun `deleting a video forever removes its sidecar`() {
        video("clip.mp4")
        library.save("clip.mp4", "Клип", "Описание")
        val trashed = requireNotNull(storage.trash("clip.mp4"))

        assertTrue(storage.deleteFromTrash(trashed.id))

        assertFalse(File(root, "clip.mp4.library.json").exists())
        assertTrue(library.items().isEmpty())
    }

    @Test
    fun `sidecar is hidden from the file list`() {
        video("clip.mp4")
        library.save("clip.mp4", "Клип", "Описание")

        val names = storage.entries("").map { it.name }
        val all = storage.allFiles().map { it.name }

        assertEquals(listOf("clip.mp4"), names)
        assertEquals(listOf("clip.mp4"), all)
    }

    @Test
    fun `ignores a broken sidecar`() {
        video("clip.mp4")
        File(root, "clip.mp4.library.json").writeText("not json at all")

        val item = library.items().single()

        assertEquals("clip", item.title)
        assertEquals("", item.description)
        assertFalse(item.hasDescription)
    }
}
