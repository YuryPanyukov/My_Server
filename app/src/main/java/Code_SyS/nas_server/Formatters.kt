package Code_SyS.nas_server

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Подписи размера и даты: одинаковы в списке файлов и в клиенте к другому телефону. */
internal object FileFormat {

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, value, units[unit])
    }

    fun date(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))
}
