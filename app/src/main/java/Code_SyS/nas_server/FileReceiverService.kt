package Code_SyS.nas_server

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import java.util.concurrent.Executors

class FileReceiverService : Service() {

    companion object {
        const val TAG = "FileReceiverService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "file_receiver_channel"
        const val ACTION_START = "Code_SyS.nas_server.ACTION_START"
        const val ACTION_STOP = "Code_SyS.nas_server.ACTION_STOP"

        /** Запущен ли сейчас сервис приёма файлов. */
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = manager.getRunningServices(Int.MAX_VALUE)
            return services.any { service ->
                service.service.className == "Code_SyS.nas_server.FileReceiverService"
            }
        }
    }

    @Volatile
    private var isRunning = false
    private var server: FileReceiverServer? = null

    /** Копирование в Галерею не должно задерживать ответ клиенту. */
    private val galleryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "landrop-gallery").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        // Канал создаётся здесь, а не только в Activity: сервис обязан уметь
        // показать foreground-уведомление даже если Activity уже уничтожена.
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            else -> {
                // Запуск без явного действия (или перезапуск системой) не должен
                // оставлять foreground-уведомление о "работающем" сервере.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (isRunning) {
            Log.i(TAG, "Server is already running on port ${Protocol.PORT}")
            return
        }

        val fileSaver = FileSaver(this)
        // План: сервер слушает только локальную Wi-Fi сеть, а не все интерфейсы.
        val bindAddress = NetworkUtils.getWifiIpAddress(this)?.let { ip ->
            try {
                InetAddress.getByName(ip)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось разобрать Wi-Fi адрес $ip", e)
                null
            }
        }
        if (bindAddress == null) {
            Log.w(TAG, "Wi-Fi address is unavailable, binding to all interfaces")
        }

        // Галерея: принятые файлы + фото и видео из медиатеки телефона.
        val receivedSource = ReceivedMediaSource(
            files = { fileSaver.listFiles() },
            thumbnailer = { file -> Thumbnails.fromFile(file, MimeTypes.forFileName(file.name)) },
        )
        val deviceSource = DeviceMediaSource(this)

        // Режим доступа (всё / только галерея / выбранные / за период) читается
        // на каждый запрос: смена тумблера на экране не требует перезапуска
        // сервера. Режим хранится в AccessModeStore — общий prefs-источник для
        // экрана и сервиса.
        AccessModeStore.load(this)

        val newServer = FileReceiverServer(
            port = Protocol.PORT,
            bindAddress = bindAddress,
            logger = { Log.i(TAG, it) },
            saver = { fileName, fileSize, input ->
                val saved = fileSaver.save(fileName, fileSize, input)
                if (saved != null && GallerySaver.isEnabled(this)) {
                    galleryExecutor.execute { GallerySaver.publish(this, saved) }
                }
                saved
            },
            fileLister = {
                AccessModeFilter.applyFiles(
                    fileSaver.listFiles(),
                    AccessModeState.current,
                    System.currentTimeMillis(),
                )
            },
            mediaSources = {
                AccessModeFilter.applySources(
                    listOf(receivedSource, deviceSource),
                    AccessModeState.current,
                    System.currentTimeMillis(),
                )
            },
            fileThumbnailer = { file -> Thumbnails.fromFile(file, MimeTypes.forFileName(file.name)) },
        )

        try {
            newServer.start()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить сервер", e)
            newServer.close()
            isRunning = false
            stopForegroundCompat()
            stopSelf()
            return
        }

        server = newServer
        isRunning = true
        Log.i(TAG, "ServerSocket started on port ${newServer.localPort}")
    }

    private fun stopServer() {
        isRunning = false
        server?.close()
        server = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        isRunning = false
        server?.close()
        server = null
        galleryExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Сервер запущен на порту ${Protocol.PORT}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
