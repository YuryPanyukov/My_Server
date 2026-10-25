package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentHostsTest {

    @Test
    fun `encodes and decodes hosts in order`() {
        val targets = listOf(
            ShareTarget("192.168.1.42", 8080),
            ShareTarget("[fe80::1]", 9090),
        )

        val encoded = RecentHostsCodec.encode(targets)

        assertEquals(targets, RecentHostsCodec.decode(encoded))
    }

    @Test
    fun `decodes empty and broken values safely`() {
        assertEquals(emptyList<ShareTarget>(), RecentHostsCodec.decode(null))
        assertEquals(emptyList<ShareTarget>(), RecentHostsCodec.decode(""))
        assertEquals(emptyList<ShareTarget>(), RecentHostsCodec.decode("broken"))
        assertEquals(emptyList<ShareTarget>(), RecentHostsCodec.decode("host|not-a-port"))
        assertEquals(emptyList<ShareTarget>(), RecentHostsCodec.decode("|8080"))
    }

    @Test
    fun `skips invalid lines but keeps valid ones`() {
        val decoded = RecentHostsCodec.decode("192.168.1.5|8080\nnonsense\n10.0.0.2|9090")

        assertEquals(
            listOf(ShareTarget("192.168.1.5", 8080), ShareTarget("10.0.0.2", 9090)),
            decoded,
        )
    }

    @Test
    fun `limits stored hosts`() {
        val targets = (1..9).map { ShareTarget("10.0.0.$it", 8080) }

        val decoded = RecentHostsCodec.decode(RecentHostsCodec.encode(targets))

        assertEquals(RecentHosts.MAX, decoded.size)
        assertTrue(decoded.first().host.endsWith(".1"))
    }
}
