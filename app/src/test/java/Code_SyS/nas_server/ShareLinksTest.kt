package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ссылки для QR и deep link: чистые функции без Android API. */
class ShareLinksTest {

    @Test
    fun `builds browser, bridge and app links`() {
        val target = ShareTarget("192.168.1.42", 8080)

        assertEquals("http://192.168.1.42:8080", target.browserUrl)
        assertEquals("http://192.168.1.42:8080/open", target.openUrl)
        assertEquals("landrop://connect?host=192.168.1.42&port=8080", target.deepLink)
    }

    @Test
    fun `parses deep link with port`() {
        val target = ShareLinks.parseDeepLink("landrop://connect?host=192.168.1.42&port=9090")

        assertEquals(ShareTarget("192.168.1.42", 9090), target)
    }

    @Test
    fun `parses deep link without port`() {
        val target = ShareLinks.parseDeepLink("landrop://connect?host=192.168.1.42")

        assertEquals(ShareTarget("192.168.1.42", Protocol.PORT), target)
    }

    @Test
    fun `rejects foreign and broken links`() {
        assertNull(ShareLinks.parseDeepLink(null))
        assertNull(ShareLinks.parseDeepLink("https://192.168.1.42:8080"))
        assertNull(ShareLinks.parseDeepLink("landrop://other?host=192.168.1.42"))
        assertNull(ShareLinks.parseDeepLink("landrop://connect"))
        assertNull(ShareLinks.parseDeepLink("landrop://connect?px=1"))
        assertNull(ShareLinks.parseDeepLink("landrop://connect?host=192.168.1.42&port=0"))
        assertNull(ShareLinks.parseDeepLink("landrop://connect?host=../etc&port=8080"))
        assertNull(ShareLinks.parseDeepLink("landrop://connect?host=1.2.3.4&port=99999"))
    }

    @Test
    fun `parses host header with and without port`() {
        assertEquals(ShareTarget("192.168.1.42", 8080), ShareLinks.parseAuthority("192.168.1.42:8080"))
        assertEquals(ShareTarget("192.168.1.42", Protocol.PORT), ShareLinks.parseAuthority("192.168.1.42"))
        assertEquals(ShareTarget("[fe80::1]", 8080), ShareLinks.parseAuthority("[fe80::1]:8080"))
    }

    @Test
    fun `reads target from scanned QR variants`() {
        assertEquals(
            ShareTarget("192.168.1.42", 8080),
            ShareLinks.parseScanResult("http://192.168.1.42:8080/open"),
        )
        assertEquals(
            ShareTarget("192.168.1.42", 9090),
            ShareLinks.parseScanResult("http://192.168.1.42:9090"),
        )
        assertEquals(
            ShareTarget("192.168.1.42", Protocol.PORT),
            ShareLinks.parseScanResult("192.168.1.42"),
        )
        assertEquals(
            ShareTarget("192.168.1.42", 9090),
            ShareLinks.parseScanResult("192.168.1.42:9090"),
        )
        assertEquals(
            ShareTarget("192.168.1.42", 8080),
            ShareLinks.parseScanResult("landrop://connect?host=192.168.1.42&port=8080"),
        )
    }

    @Test
    fun `rejects non-host QR content`() {
        assertNull(ShareLinks.parseScanResult(null))
        assertNull(ShareLinks.parseScanResult("   "))
        assertNull(ShareLinks.parseScanResult("Привет"))
        assertNull(ShareLinks.parseScanResult("https://example.com/path"))
        assertNull(ShareLinks.parseScanResult("http://"))
    }

    @Test
    fun `rejects malformed host header`() {
        assertNull(ShareLinks.parseAuthority(null))
        assertNull(ShareLinks.parseAuthority(""))
        assertNull(ShareLinks.parseAuthority("192.168.1.42:not-a-port"))
        assertNull(ShareLinks.parseAuthority("192.168.1.42:70000"))
        assertNull(ShareLinks.parseAuthority("[fe80::1"))
        assertNull(ShareLinks.parseAuthority("192.168.1.42/evil"))
    }
}
