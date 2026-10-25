Ниже краткая проверка предыдущего ответа, а затем — полный, готовый к передаче в разработку план с исходным кодом, разрешениями и тестами.

---

### Проверка предыдущего ответа

Предыдущий план в целом верен, но есть несколько уточнений, которые стоит внедрить перед стартом разработки:

1. **Протокол.** Лучше использовать `DataInputStream` / `DataOutputStream` вместо ручного чтения байтов — это надёжнее и читаемее.
2. **Сохранение файлов.** На Android 10+ нельзя просто писать в `/sdcard/Download`. В плане ниже сделан акцент на **приватную внешнюю папку приложения** (`getExternalFilesDir`) для MVP, с опциональным переходом на **SAF** (`Storage Access Framework`) или `MediaStore`.
3. **Foreground Service.** Указан тип `dataSync` (для API 31+), что корректно для длительной работы сервера в фоне.
4. **Безопасность.** Сервер слушает только в локальной Wi-Fi сети; проброс порта на роутере не требуется и не рекомендуется.

---

<details>
<summary>1. Общее описание и стек</summary>

**Цель:** Приложение для Android, которое при включении поднимает `ServerSocket` на заданном порту и принимает файлы с любого устройства в той же Wi-Fi сети по IP-адресу телефона.

**Стек:**
* Язык: **Kotlin**
* Минимальный SDK: **API 24** (Android 7.0)
* Целевой SDK: **API 34** (Android 14)
* Архитектура: Activity + Foreground Service + фоновый поток с `ExecutorService`
* Сетевой стек: `java.net.ServerSocket`, `Socket`, `DataInputStream`, `DataOutputStream`

**Порядок работы:**
1. Пользователь открывает приложение и нажимает «Включить сервер».
2. Приложение запускает `ForegroundService`, который открывает `ServerSocket(PORT)`.
3. На экране отображается локальный IP телефона (например, `192.168.1.15`) и порт (по умолчанию `8080`).
4. С компьютера по тому же IP и порту подключается клиент и передаёт файл по бинарному протоколу.
5. Файл сохраняется во внутреннее хранилище приложения (или в папку, выбранную через SAF).
6. Сервер отправляет подтверждение (`true`/`false`) и закрывает соединение.

</details>

---

<details>
<summary>2. Разрешения и манифест</summary>

`AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.wififilereceiver">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">

        <service
            android:name=".FileReceiverService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

**Особенности разрешений:**
* `POST_NOTIFICATIONS` — обязательно для Android 13+ (запрашивается в runtime).
* `FOREGROUND_SERVICE_DATA_SYNC` — для Android 12+ (API 31+).
* `WRITE_EXTERNAL_STORAGE` **не требуется**, если сохранять в `getExternalFilesDir(...)` (приватная папка приложения) или использовать SAF.

</details>

---

<details>
<summary>3. Файловая структура проекта</summary>

```
app/
├── src/main/
│   ├── java/com/example/wififilereceiver/
│   │   ├── MainActivity.kt
│   │   ├── FileReceiverService.kt
│   │   ├── NetworkUtils.kt
│   │   ├── FileSaver.kt
│   │   └── Protocol.kt
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml
│   │   ├── values/
│   │   │   ├── strings.xml
│   │   │   └── colors.xml
│   │   └── xml/
│   │       └── notification_channel.xml
│   └── AndroidManifest.xml
├── src/test/
│   └── java/com/example/wififilereceiver/
│       ├── NetworkUtilsTest.kt
│       └── FileSaverTest.kt
└── src/androidTest/
    └── java/com/example/wififilereceiver/
        ├── MainActivityTest.kt
        └── FileReceiverServiceTest.kt
```

</details>

---

<details>
<summary>4. Ключевые исходные файлы</summary>

### `Protocol.kt` — бинарный протокол

Обе стороны (Android и ПК) должны придерживаться этого формата:

```
[2 байта: длина имени файла N (big-endian)]
[N байт: имя файла в UTF-8]
[8 байт: размер файла в байтах (long, big-endian)]
[M байт: содержимое файла]
```

```kotlin
object Protocol {
    const val HEADER_NAME_LENGTH_SIZE = 2  // unsigned short
    const val HEADER_FILE_SIZE_SIZE = 8    // long
    const val BUFFER_SIZE = 65536
}
```

### `NetworkUtils.kt` — получение IP и проверка Wi-Fi

```kotlin
import android.content.Context
import android.net.wifi.WifiManager
import android.net.NetworkInfo

object NetworkUtils {
    fun getWifiIpAddress(context: Context): String? {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val connectionInfo = wifiManager.connectionInfo
        val ipInt = connectionInfo.ipAddress
        return if (ipInt == 0) null else {
            "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}" +
                ".${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetworkInfo
        return network != null && network.type == ConnectivityManager.TYPE_WIFI && network.isConnected
    }
}
```

### `FileSaver.kt` — сохранение в приватную внешнюю папку

```kotlin
import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class FileSaver(private val context: Context) {
    fun save(fileName: String, fileSize: Long, input: InputStream): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return null
        dir.mkdirs()

        // Санитизация имени: убираем путь, оставляем только имя файла
        val safeName = File(fileName).name
        val outFile = File(dir, safeName)

        FileOutputStream(outFile).use { out ->
            input.copyTo(out, Protocol.BUFFER_SIZE)
        }
        return outFile
    }
}
```

### `FileReceiverService.kt` — сервер в фоновом режиме

```kotlin
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class FileReceiverService : Service() {

    companion object {
        const val TAG = "FileReceiverService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "file_receiver_channel"
        const val ACTION_TOGGLE = "com.example.wififilereceiver.ACTION_TOGGLE"
        const val PORT = 8080
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var isRunning = false
    private var fileSaver: FileSaver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (intent?.action == ACTION_TOGGLE) {
            if (!isRunning) {
                isRunning = true
                fileSaver = FileSaver(this)
                executor.execute { runServer() }
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun runServer() {
        try {
            serverSocket = ServerSocket(PORT)
            Log.i(TAG, "ServerSocket started on port $PORT")
            while (isRunning) {
                val client: Socket = serverSocket!!.accept()
                Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")
                executor.execute { handleClient(client) }
            }
        } catch (e: IOException) {
            if (isRunning) {
                Log.e(TAG, "ServerSocket error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var dis: DataInputStream? = null
        var dos: DataOutputStream? = null
        try {
            dis = DataInputStream(socket.inputStream)
            dos = DataOutputStream(socket.outputStream)

            // Читаем заголовок
            val nameLength = dis.readUnsignedShort()
            val nameBytes = ByteArray(nameLength)
            dis.readFully(nameBytes)
            val fileName = String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)

            val fileSize = dis.readLong()

            Log.i(TAG, "Receiving: $fileName ($fileSize bytes)")

            // Сохраняем
            val savedFile = fileSaver?.save(fileName, fileSize, dis)
            val success = savedFile != null

            // Отправляем ответ ПК
            dos.writeBoolean(success)
            dos.flush()

            Log.i(TAG, "Transfer $fileName: ${if (success) "OK" else "FAILED"}")
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            try { dis?.close() } catch (_: Exception) {}
            try { dos?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wi-Fi File Receiver")
            .setContentText("Сервер запущен на порту $PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
```

### `MainActivity.kt` — экран управления

```kotlin
import android.content.Intent
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var switchServer: Switch
    private lateinit var textIp: TextView
    private lateinit var textStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchServer = findViewById(R.id.switchServer)
        textIp = findViewById(R.id.textIp)
        textStatus = findViewById(R.id.textStatus)

        // Отображаем IP
        val ip = NetworkUtils.getWifiIpAddress(this)
        textIp.text = "IP: ${ip ?: "недоступен"}"

        switchServer.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, FileReceiverService::class.java)
            intent.action = FileReceiverService.ACTION_TOGGLE
            if (isChecked) {
                ContextCompat.startForegroundService(this, intent)
                textStatus.text = "Статус: запущен"
            } else {
                stopService(intent)
                textStatus.text = "Статус: остановлен"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val ip = NetworkUtils.getWifiIpAddress(this)
        textIp.text = "IP: ${ip ?: "недоступен"}"
    }
}
```

### `activity_main.xml` (упрощённый макет)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="20dp">

    <TextView
        android:id="@+id/textIp"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="IP: —"
        android:textSize="18sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/textStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Статус: остановлен"
        android:textSize="16sp"
        android:layout_marginTop="8dp" />

    <Switch
        android:id="@+id/switchServer"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Включить сервер приёма файлов"
        android:layout_marginTop="24dp" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Порт: ${FileReceiverService.PORT}"
        android:layout_marginTop="24dp" />

</LinearLayout>
```

</details>

---

<details>
<summary>5. Инструкция по подключению с компьютера (для второй части)</summary>

Когда Android-приложение запущено, с компьютера нужно сделать следующее:

1. Убедиться, что ПК и телефон в **одной Wi-Fi сети**.
2. Узнать IP телефона с экрана приложения.
3. Подключиться по TCP к `<IP_телефона>:8080`.
4. Отправить заголовок:
   * 2 байта — длина имени файла (big-endian).
   * N байт — имя файла (UTF-8).
   * 8 байт — размер файла в байтах (long, big-endian).
5. Отправить содержимое файла.
6. Прочитать 1 байт-ответ: `1` = успех, `0` = ошибка.
7. Закрыть сокет.

Пример на Python (для вашей второй части):

```python
import socket
import os
import sys

PORT = 8080

def send_file(host: str, filepath: str):
    if not os.path.isfile(filepath):
        print("Файл не найден")
        return

    filename = os.path.basename(filepath).encode("utf-8")
    filesize = os.path.getsize(filepath)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((host, PORT))
        s.sendall(len(filename).to_bytes(2, "big"))
        s.sendall(filename)
        s.sendall(filesize.to_bytes(8, "big"))

        with open(filepath, "rb") as f:
            while chunk := f.read(65536):
                s.sendall(chunk)

        # Читаем ответ (1 байт)
        response = s.recv(1)
        print("Ответ:", "Успех" if response == b'\x01' else "Ошибка")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(f"Использование: python {sys.argv[0]} <IP_телефона> <путь_к_файлу>")
        sys.exit(1)
    send_file(sys.argv[1], sys.argv[2])
```

</details>

---

<details>
<summary>6. План тестирования</summary>

### Юнит-тесты (`src/test`)

**`NetworkUtilsTest.kt`**
* Проверка корректного преобразования `ipInt` в строку (`0x0A000102` → `10.0.1.2`).
* Возврат `null`, когда Wi-Fi отключён (`ipInt == 0`).

**`FileSaverTest.kt`**
* Сохранение файла известного размера и имени в временную папку.
* Проверка, что содержимое совпадает с отправленным.
* Санитизация имени: передача `../../../etc/passwd` должна сохранить файл под именем `passwd` без обхода пути.

### Инструментальные тесты (`src/androidTest`)

**`MainActivityTest.kt`** (Espresso)
* Нажатие на Switch запускает сервис (проверяется через текст статуса или наличие уведомления).
* Повторное нажатие останавливает сервис.

**`FileReceiverServiceTest.kt`**
* Запуск сервиса.
* Открытие клиентского сокета на локальном эмуляторе/устройстве к `10.0.2.2:8080` (или тестовый IP).
* Отправка тестового файла по протоколу `Protocol`.
* Проверка, что файл появился в `getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`.
* Проверка ответа сервера (`true`).

**Сценарии ручного тестирования:**
1. Телефон и ПК в одной Wi-Fi → подключение проходит, файл приходит.
2. Телефон в Wi-Fi, ПК в мобильной сети → подключение не проходит (ожидаемо).
3. Выключение экрана телефона во время передачиlarge-файла → соединение не рвётся (благодаря Foreground Service).
4. Передача файла с кириллицей в имени → имя сохраняется корректно.

</details>

---

<details>
<summary>7. Чек-лист готовности к передаче на разработку</summary>

- [ ] Манифест содержит все нужные разрешения, включая `FOREGROUND_SERVICE_DATA_SYNC` и `POST_NOTIFICATIONS`.
- [ ] Сервис объявлен с `android:foregroundServiceType="dataSync"`.
- [ ] `MainActivity` запускает сервис через `ContextCompat.startForegroundService`.
- [ ] `ServerSocket` binds на порт `8080` (или конфигурируемый).
- [ ] Протокол приёма зафиксирован (имя + размер + данные).
- [ ] Сохранение файла реализовано без `WRITE_EXTERNAL_STORAGE` (через `getExternalFilesDir`).
- [ ] Добавлены юнит-тесты и инструментальные тесты.
- [ ] Описано, как ПК подключается по IP (раздел выше).

</details>

---