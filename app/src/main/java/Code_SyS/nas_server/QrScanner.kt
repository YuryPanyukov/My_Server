package Code_SyS.nas_server

import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Декодирование QR из одного яркостного кадра (Y-плоскость камеры).
 *
 * Отделено от CameraX, чтобы путь «кадр → текст» можно было проверить
 * JVM-тестом без устройства и камеры.
 */
internal object QrScanner {

    /** `null` — в кадре нет читаемого QR. */
    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (luminance.size < width * height) return null

        return try {
            val source = PlanarYUVLuminanceSource(
                luminance,
                width,
                height,
                0,
                0,
                width,
                height,
                false,
            )
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            // Битый кадр или неподдерживаемый формат — просто ждём следующий.
            null
        }
    }
}
