package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * Тесты частичного доступа: один день — «сегодня», «вчера», произвольная дата,
 * отбор файлов и источников галереи по режиму.
 */
class AccessModeTest {

    /** Полдень локального дня [dayBack] назад: границы дня должны его ловить. */
    private fun localNoon(dayBack: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -dayBack)
        calendar.set(Calendar.HOUR_OF_DAY, 12)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun fileWithTime(millis: Long): File {
        val file = File.createTempFile("access-", ".txt")
        file.deleteOnExit()
        file.setLastModified(millis)
        return file
    }

    private fun itemAt(millis: Long, name: String = "photo.jpg"): MediaItem =
        MediaItem(
            id = name,
            name = name,
            size = 10,
            dateMillis = millis,
            mimeType = "image/jpeg",
            kind = MediaItem.Kind.IMAGE,
        )

    private fun sourceOf(items: List<MediaItem>, key: String = "received"): MediaSource =
        object : MediaSource {
            override val key = key
            override val title = key
            override fun items(): List<MediaItem> = items
            override fun open(item: MediaItem) = null
        }

    // --- Файлы ------------------------------------------------------------

    @Test
    fun `all mode keeps files untouched`() {
        val files = listOf(fileWithTime(0), fileWithTime(System.currentTimeMillis()))

        assertEquals(files, AccessModeFilter.applyFiles(files, AccessMode.All, 0))
    }

    @Test
    fun `gallery only hides all received files`() {
        val files = listOf(fileWithTime(System.currentTimeMillis()))

        assertTrue(
            AccessModeFilter.applyFiles(files, AccessMode.GalleryOnly, 0).isEmpty()
        )
    }

    @Test
    fun `selected mode leaves only named files`() {
        val now = System.currentTimeMillis()
        val files = listOf(
            fileWithTime(now),
            fileWithTime(now),
            fileWithTime(now),
        )
        val named = AccessMode.Selected(setOf(files[1].name, "ghost.txt"))

        val visible = AccessModeFilter.applyFiles(files, named, now)

        assertEquals(listOf(files[1].name), visible.map { it.name })
    }

    @Test
    fun `today shows files from current day only`() {
        val now = System.currentTimeMillis()

        val visible = AccessModeFilter.applyFiles(
            listOf(fileWithTime(localNoon(0)), fileWithTime(localNoon(2))),
            AccessMode.Period.Today,
            now,
        )

        assertEquals(1, visible.size)
    }

    @Test
    fun `yesterday does not show today or older`() {
        val visible = AccessModeFilter.applyFiles(
            listOf(
                fileWithTime(localNoon(0)),
                fileWithTime(localNoon(1)),
                fileWithTime(localNoon(3)),
            ),
            AccessMode.Period.Yesterday,
            System.currentTimeMillis(),
        )

        assertEquals(1, visible.size)
    }

    // --- Галерея ----------------------------------------------------------

    @Test
    fun `period filters media items by their date`() {
        val now = System.currentTimeMillis()

        val sources = listOf(
            sourceOf(listOf(itemAt(localNoon(0)), itemAt(localNoon(4)))),
        )

        val filtered = AccessModeFilter.applySources(sources, AccessMode.Period.Today, now)

        assertEquals(listOf("photo.jpg"), filtered.single().items().map { it.name })
    }

    @Test
    fun `gallery only keeps device source and hides received`() {
        val now = System.currentTimeMillis()
        val sources = listOf(sourceOf(listOf(itemAt(now))), sourceOf(listOf(itemAt(now)), key = "device"))

        val filtered = AccessModeFilter.applySources(sources, AccessMode.GalleryOnly, now)

        assertEquals(listOf("device"), filtered.map { it.key })
    }

    @Test
    fun `selected keeps only received source with named items`() {
        val now = System.currentTimeMillis()
        val sources = listOf(
            sourceOf(listOf(itemAt(now, "keep.jpg"), itemAt(now, "drop.jpg"))),
            sourceOf(listOf(itemAt(now)), key = "device"),
        )

        val filtered = AccessModeFilter.applySources(
            sources,
            AccessMode.Selected(setOf("keep.jpg")),
            now,
        )

        assertEquals(listOf("received"), filtered.map { it.key })
        assertEquals(listOf("keep.jpg"), filtered.single().items().map { it.name })
    }

    @Test
    fun `filtered source hides content without breaking open contract`() {
        val origin = sourceOf(listOf(itemAt(1, "a.jpg"), itemAt(2, "b.jpg")))
        val filtered = AccessFilteredSource(origin, allowed = { it.name == "b.jpg" })

        assertEquals(1, filtered.items().size)
        assertEquals("received", filtered.key)
    }

    // --- Кодирование режима ------------------------------------------------

    @Test
    fun `every mode survives encode-decode roundtrip`() {
        val modes: List<AccessMode> = listOf(
            AccessMode.All,
            AccessMode.GalleryOnly,
            AccessMode.Period.Today,
            AccessMode.Period.Yesterday,
            AccessMode.Period.OnDate(20_000),
            AccessMode.Selected(setOf("a.jpg", "b.txt")),
        )

        modes.forEach { mode ->
            assertEquals(mode, AccessModeCodec.decode(AccessModeCodec.encode(mode)))
        }
    }

    @Test
    fun `unknown and empty encoded values fall back to all`() {
        assertEquals(AccessMode.All, AccessModeCodec.decode(null))
        assertEquals(AccessMode.All, AccessModeCodec.decode(""))
        assertEquals(AccessMode.All, AccessModeCodec.decode("nonsense"))
        assertEquals(AccessMode.All, AccessModeCodec.decode("selected:"))
    }

    @Test
    fun `empty selection encodes as all`() {
        assertEquals("all", AccessModeCodec.encode(AccessMode.Selected(emptySet())))
    }
}
