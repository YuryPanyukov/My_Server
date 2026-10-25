package Code_SyS.nas_server

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** QR для подключения: кодируем адрес и проверяем, что ZXing его читает. */
class QrCodeTest {

    @Test
    fun `encodes target URL to a square matrix`() {
        val modules = QrCode.modules("http://192.168.1.42:8080/open")

        assertFalse("нужно место под код", modules.isEmpty())
        assertEquals("матрица должна быть квадратной", modules.size, modules[0].size)
    }

    @Test
    fun `encoded QR decodes back to the same URL`() {
        val content = "http://192.168.1.42:8080/open"
        val decoded = decode(QrCode.modules(content))

        assertEquals(content, decoded)
    }

    @Test
    fun `decodes URL with IPv6 host`() {
        val content = "http://[fe80::1]:8080/open"

        assertEquals(content, decode(QrCode.modules(content)))
    }

    @Test
    fun `scanner decodes luminance frame from camera`() {
        val content = "http://192.168.1.42:8080/open"
        val modules = QrCode.modules(content)
        val modulesAcross = modules.size
        // Кадр камеры крупнее матрицы: один модуль — несколько пикселей,
        // иначе детектор QR в ZXing не находит код на 29×29 пикселях.
        val scale = 4
        val width = modulesAcross * scale
        val luminance = ByteArray(width * width) { index ->
            val x = index % width / scale
            val y = index / width / scale
            if (modules[y][x]) 0 else 255.toByte()
        }

        assertEquals(content, QrScanner.decode(luminance, width, width))
    }

    @Test
    fun `scanner ignores frames without QR and broken sizes`() {
        val blank = ByteArray(64 * 64) { 255.toByte() }

        assertNull(QrScanner.decode(blank, 64, 64))
        assertNull(QrScanner.decode(blank, 0, 64))
        assertNull(QrScanner.decode(blank, 100, 100))
    }

    /** Разворачивает модули в пиксели и читает код так же, как это сделал бы сканер. */
    private fun decode(modules: Array<BooleanArray>): String {
        val scale = 4
        val width = modules.size * scale
        val pixels = IntArray(width * width) { index ->
            val x = index % width / scale
            val y = index / width / scale
            if (modules[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(width, width, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
