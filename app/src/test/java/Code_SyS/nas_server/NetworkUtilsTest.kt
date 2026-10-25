package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun `formatIp converts little-endian int to dotted string`() {
        // WifiInfo.getIpAddress() отдаёт адрес в little-endian порядке:
        // младший байт — первая октет. Для IP 10.0.1.2 это 0x0201000A.
        assertEquals("10.0.1.2", NetworkUtils.formatIp(0x0201000A))
    }

    @Test
    fun `formatIp keeps little-endian byte order`() {
        // 0x0A000102 => байты от младшего к старшему: 0x02 0x01 0x00 0x0A => 2.1.0.10
        assertEquals("2.1.0.10", NetworkUtils.formatIp(0x0A000102))
    }

    @Test
    fun `formatIp returns null when address is zero`() {
        assertNull(NetworkUtils.formatIp(0))
    }

    @Test
    fun `formatIp converts loopback address`() {
        // 0x0100007F => 127.0.0.1
        assertEquals("127.0.0.1", NetworkUtils.formatIp(0x0100007F))
    }

    @Test
    fun `formatIp converts broadcast address`() {
        // 0xFFFFFFFF => 255.255.255.255
        assertEquals("255.255.255.255", NetworkUtils.formatIp(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `formatIp converts typical home network address`() {
        // 192.168.1.15 => 0x0F01A8C0
        assertEquals("192.168.1.15", NetworkUtils.formatIp(0x0F01A8C0))
    }

    @Test
    fun `formatIp handles negative int as unsigned 32-bit value`() {
        // -1 как int32 => 255.255.255.255
        assertEquals("255.255.255.255", NetworkUtils.formatIp(-1))
    }
}
