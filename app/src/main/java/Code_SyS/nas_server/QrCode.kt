package Code_SyS.nas_server

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.min

/**
 * QR-код для подключения со второго телефона.
 *
 * Кодируется обычный HTTP-адрес страницы-мостика (`/open`): его понимает любой
 * сканер, а уже страница предлагает открыть приложение LanDrop.
 */
object QrCode {

    /** «Тихая зона» вокруг кода в модулях: без неё сканеры хуже находят код. */
    const val QUIET_ZONE = 2

    /** Матрица модулей: `true` — тёмный. Размер квадратный. */
    fun modules(content: String): Array<BooleanArray> {
        require(content.isNotEmpty()) { "Пустое содержимое QR-кода" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix[x, y] }
        }
    }
}

/**
 * Рисует QR-код на белом фоне: инверсия в тёмной теме сломала бы распознавание.
 * Модули рисуются вручную, а не через Bitmap, поэтому код остаётся чётким
 * на любой плотности экрана.
 */
@Composable
fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val modules = remember(content) {
        runCatching { QrCode.modules(content) }.getOrNull()
    }

    Box(
        modifier = modifier.background(Color.White, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (modules == null) return@Box
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val count = modules.size
            val side = min(size.width, size.height)
            val module = side / count
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            for (y in 0 until count) {
                for (x in 0 until count) {
                    if (!modules[y][x]) continue
                    drawRect(
                        color = QR_DARK,
                        topLeft = Offset(left + x * module, top + y * module),
                        // Небольшой нахлёст убирает светлые швы между модулями.
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}

/** Цвет модулей: как фон приложения, а не чистый чёрный. */
private val QR_DARK = Color(0xFF0A0C11)
