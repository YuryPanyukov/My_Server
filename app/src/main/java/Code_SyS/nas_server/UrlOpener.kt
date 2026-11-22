package Code_SyS.nas_server

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Открывает ссылку во внешнем приложении. Если подходящего нет (например,
 * на устройстве не выбран браузер), показываем тост вместо падения.
 *
 * Функция одна на сервер и клиент: раньше в обоих экранах лежала своя копия.
 */
internal fun openUrl(context: Context, url: String, noAppMessage: String? = null) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        Toast.makeText(
            context,
            noAppMessage ?: context.getString(R.string.toast_no_app_for_link),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
