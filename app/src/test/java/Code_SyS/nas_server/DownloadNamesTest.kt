package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Test

/** Имена файлов для «Загрузок»: без путей, запрещённых символов и перезаписи. */
class DownloadNamesTest {

    @Test
    fun `strips directories from unix and windows paths`() {
        assertEquals("photo.jpg", DownloadNames.sanitize("../../private/photo.jpg"))
        assertEquals("photo.jpg", DownloadNames.sanitize("C:\\Users\\me\\photo.jpg"))
        assertEquals("photo.jpg", DownloadNames.sanitize("photos\\2026/photo.jpg"))
    }

    @Test
    fun `removes characters forbidden by file systems`() {
        assertEquals("ab.txt", DownloadNames.sanitize("a:b*?\"<>|.txt"))
        assertEquals("report.txt", DownloadNames.sanitize("re\u0000po\u001Frt.txt"))
    }

    @Test
    fun `keeps cyrillic and spaces`() {
        assertEquals("план поездки.txt", DownloadNames.sanitize(" Отпуск/план поездки.txt "))
    }

    @Test
    fun `rejects empty and dot names`() {
        assertEquals("", DownloadNames.sanitize(""))
        assertEquals("", DownloadNames.sanitize("   "))
        assertEquals("", DownloadNames.sanitize("."))
        assertEquals("", DownloadNames.sanitize(".."))
        assertEquals("", DownloadNames.sanitize("photos/"))
    }

    @Test
    fun `numbers duplicate names before the extension`() {
        assertEquals("photo.jpg", DownloadNames.unique("photo.jpg", emptySet()))
        assertEquals("photo (1).jpg", DownloadNames.unique("photo.jpg", setOf("photo.jpg")))
        assertEquals(
            "photo (3).jpg",
            DownloadNames.unique("photo.jpg", setOf("photo.jpg", "photo (1).jpg", "photo (2).jpg")),
        )
    }

    @Test
    fun `numbers names without extension`() {
        assertEquals("README (1)", DownloadNames.unique("README", setOf("README")))
        assertEquals(".hidden (1)", DownloadNames.unique(".hidden", setOf(".hidden")))
    }
}
