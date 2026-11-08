package Code_SyS.nas_server

import android.content.res.Resources
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Подписи размера и даты: одинаковы в списке файлов и в клиенте к другому телефону. */
internal object FileFormat {

    fun size(resources: Resources, bytes: Long): String {
        if (bytes < 1024) return resources.getString(R.string.size_bytes, bytes)
        val units = arrayOf(R.string.size_kb, R.string.size_mb, R.string.size_gb, R.string.size_tb)
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, value, resources.getString(units[unit]))
    }

    fun date(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))
}
