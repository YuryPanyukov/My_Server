package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Тесты автоархивации: отбор только старых файлов, которых ещё нет в
 * хранилище, и выключенная по умолчанию настройка.
 */
class AutoArchiveTest {

    /** Файл, изменённый ровно [ageMillis] назад от [now]: тест не зависит от времени суток. */
    private fun fileAged(ageMillis: Long, name: String): File {
        val file = File.createTempFile("auto-$name-", ".txt")
        file.deleteOnExit()
        file.setLastModified(now - ageMillis)
        return file
    }

    private val now = System.currentTimeMillis()

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `disabled config selects nothing`() {
        val old = fileAged(30 * day, "old")

        assertEquals(
            emptyList<File>(),
            AutoArchiveFilter.select(
                files = listOf(old),
                config = AutoArchiveConfig(enabled = false, maxAgeDays = 7),
                nowMillis = now,
                archivedNames = emptySet(),
            ),
        )
    }

    @Test
    fun `selects only files older than the threshold`() {
        val old = fileAged(8 * day, "old")
        val fresh = fileAged(6 * day, "fresh")

        val selected = AutoArchiveFilter.select(
            files = listOf(old, fresh),
            config = AutoArchiveConfig(enabled = true, maxAgeDays = 7),
            nowMillis = now,
            archivedNames = emptySet(),
        )

        assertEquals(listOf(old.name), selected.map { it.name })
    }

    @Test
    fun `threshold is strict — exactly cutoff is kept on the phone`() {
        // Порог: lastModified < now − 7 дней. Файл «ровно 7 суток» не выбран.
        val over = fileAged(7 * day + 1, "over")
        val exact = fileAged(7 * day, "exact")

        val selected = AutoArchiveFilter.select(
            files = listOf(over, exact),
            config = AutoArchiveConfig(enabled = true, maxAgeDays = 7),
            nowMillis = now,
            archivedNames = emptySet(),
        )

        assertEquals(listOf(over.name), selected.map { it.name })
    }

    @Test
    fun `files with existing records are not re-queued`() {
        val old = fileAged(30 * day, "old")
        val older = fileAged(60 * day, "older")

        val selected = AutoArchiveFilter.select(
            files = listOf(old, older),
            config = AutoArchiveConfig(enabled = true, maxAgeDays = 7),
            nowMillis = now,
            archivedNames = setOf(old.name),
        )

        assertEquals(listOf(older.name), selected.map { it.name })
    }

    @Test
    fun `directories and missing files are skipped`() {
        val dir = File(System.getProperty("java.io.tmpdir"))
        val gone = File(dir, "autotest-missing-${System.nanoTime()}.txt")
        val old = fileAged(30 * day, "old")

        val selected = AutoArchiveFilter.select(
            files = listOf(dir, gone, old),
            config = AutoArchiveConfig(enabled = true, maxAgeDays = 1),
            nowMillis = now,
            archivedNames = emptySet(),
        )

        assertEquals(listOf(old.name), selected.map { it.name })
    }

    @Test
    fun `default config is disabled with 7 days`() {
        val config = AutoArchiveConfig()

        assertFalse(config.enabled)
        assertEquals(7, config.maxAgeDays)
        assertEquals(intArrayOf(1, 3, 7, 30).contentToString(), AutoArchiveConfig.AGE_CHOICES.contentToString())
    }
}
