package Code_SyS.nas_server

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat

/**
 * Фото и видео, которые браузер попросил удалить, но Android требует
 * подтверждения. Запросы копятся в настройках, пока пользователь не откроет
 * приложение (карточка на главном экране) или уведомление.
 *
 * На Android 11+ удаление чужих медиа возможно только через
 * `MediaStore.createDeleteRequest`, а его показывает [DeleteConsentActivity].
 */
object PendingDeletes {

    private const val PREFS = "pending-media-deletes"
    private const val KEY_URIS = "uris"
    private const val CHANNEL_ID = "media-delete"
    private const val NOTIFICATION_ID = 42

    /** Добавляет запрос и напоминает о нём уведомлением, если это разрешено. */
    fun request(context: Context, uri: Uri) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty().toMutableSet()
        if (current.add(uri.toString())) {
            prefs.edit { putStringSet(KEY_URIS, current) }
        }
        notifyConsent(context, current.size)
    }

    /** Сколько элементов ждёт подтверждения. */
    fun count(context: Context): Int =
        prefs(context).getStringSet(KEY_URIS, emptySet())?.size ?: 0

    /** Отложенные элементы. Уже удалённые вручную URI просто не найдутся. */
    fun list(context: Context): List<Uri> =
        prefs(context).getStringSet(KEY_URIS, emptySet())
            .orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_URIS) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notifyConsent(context: Context, count: Int) {
        // Без разрешения на уведомления молчим: карточка в приложении всё равно
        // покажет запрос, когда пользователь откроет LanDrop.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_delete_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

        val intent = Intent(context, DeleteConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle(context.getString(R.string.delete_confirm_title))
            .setContentText(
                if (count == 1) {
                    context.getString(R.string.delete_pending_single)
                } else {
                    context.resources.getQuantityString(
                        R.plurals.delete_pending_plural,
                        count,
                        count,
                    )
                }
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Уведомления запрещены — останется карточка в приложении.
        }
    }
}
