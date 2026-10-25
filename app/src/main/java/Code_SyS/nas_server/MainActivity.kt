package Code_SyS.nas_server

import android.Manifest
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import Code_SyS.nas_server.ui.theme.LanDropTheme
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {

    companion object {
        /** Разрешения на чтение фото и видео, нужные на текущей версии Android. */
        fun requiredMediaPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

        /** Есть ли доступ к фото и видео: без него галерея покажет только принятые файлы. */
        fun hasMediaAccess(context: Context): Boolean {
            val fullAccess = requiredMediaPermissions().all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (fullAccess) return true

            // Android 14+: пользователь может выдать доступ только к выбранным фото.
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private var notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* foreground notification may work on some devices even without permission, but we request for API 33+ */ }

    private var mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* статус перечитывается в опросе FileReceiverScreen */ }

    /** Показывает системный запрос доступа к фото и видео. */
    fun requestMediaPermissions() {
        mediaPermissionLauncher.launch(requiredMediaPermissions())
    }

    /** Файлы из системного «Поделиться», ещё не обработанные экраном. */
    private var pendingShare by mutableStateOf<List<SharedFile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (!hasMediaAccess(this)) {
            requestMediaPermissions()
        }

        // Поворот экрана не должен заново показывать диалог отправки.
        if (savedInstanceState == null) {
            readShareIntent(intent)
        }

        setContent {
            LanDropTheme {
                val dark = isSystemInDarkTheme()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                if (dark) {
                                    listOf(Color(0xFF0A0C11), Color(0xFF0E1320))
                                } else {
                                    listOf(Color(0xFFF5F7FB), Color(0xFFEAF0FB))
                                }
                            )
                        )
                ) {
                    FileReceiverScreen(
                        sharedFiles = pendingShare,
                        onSharedHandled = {
                            pendingShare = emptyList()
                            // Чистим action, чтобы диалог не вернулся после пересоздания.
                            setIntent(Intent(this@MainActivity, MainActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShareIntent(intent)
    }

    private fun readShareIntent(intent: Intent?) {
        val files = SharedFile.fromIntent(this, intent)
        if (files.isNotEmpty()) {
            pendingShare = files
        }
    }
}

@Composable
fun FileReceiverScreen(
    sharedFiles: List<SharedFile> = emptyList(),
    onSharedHandled: () -> Unit = {},
) {
    val context = LocalContext.current

    var isServiceRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Статус: остановлен") }
    var ipText by remember { mutableStateOf("IP: —") }
    var httpUrlText by remember { mutableStateOf("Браузер: —") }
    var galleryUrlText by remember { mutableStateOf("Галерея: —") }
    var mediaAccess by remember { mutableStateOf(MainActivity.hasMediaAccess(context)) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var fileToArchive by remember { mutableStateOf<File?>(null) }
    var showSelection by remember { mutableStateOf(false) }
    var recentHosts by remember { mutableStateOf(RecentHosts.list(context)) }
    var galleryCopy by remember { mutableStateOf(GallerySaver.isEnabled(context)) }
    var accessMode by remember { mutableStateOf(AccessModeStore.load(context)) }
    var vaultPending by remember { mutableIntStateOf(0) }
    var vaultAutoPending by remember { mutableIntStateOf(0) }
    val fileSaver = remember { FileSaver(context) }

    // Список недавних телефонов обновляется после возврата со сканера.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recentHosts = RecentHosts.list(context)
                galleryCopy = GallerySaver.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (sharedFiles.isNotEmpty()) {
        SharedFilesDialog(
            files = sharedFiles,
            saver = fileSaver,
            onDismiss = {
                onSharedHandled()
                files = fileSaver.listFiles()
            },
        )
    }

    // IP читается один раз при создании экрана
    LaunchedEffect(Unit) {
        val ip = NetworkUtils.getWifiIpAddress(context)
        ipText = "IP: ${ip ?: "недоступен"}"
        httpUrlText = if (ip != null) "Браузер: http://$ip:${Protocol.PORT}" else "Браузер: —"
        galleryUrlText = if (ip != null) "Галерея: http://$ip:${Protocol.PORT}/gallery" else "Галерея: —"
    }

    // Статус обновляется при изменении переключателя
    LaunchedEffect(isServiceRunning) {
        statusText = if (isServiceRunning) "Статус: запущен" else "Статус: остановлен"
    }

    // Периодический опрос статуса сервиса и IP (IP меняется при переподключении к Wi-Fi)
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                isServiceRunning = FileReceiverService.isRunning(context)
                val ip = NetworkUtils.getWifiIpAddress(context)
                ipText = "IP: ${ip ?: "недоступен"}"
                httpUrlText = if (ip != null) "Браузер: http://$ip:${Protocol.PORT}" else "Браузер: —"
                galleryUrlText = if (ip != null) "Галерея: http://$ip:${Protocol.PORT}/gallery" else "Галерея: —"
                mediaAccess = MainActivity.hasMediaAccess(context)
                files = fileSaver.listFiles()
                // Хранилище: возобновить очередь, показать число недоставленных
                // и разово просканировать автоархивацию (внутри троттлинг ~15 мин).
                ArchiveManager.resume(context)
                ArchiveManager.runAutoArchive(context, files)
                val vaultRecords = ArchiveManager.records(context)
                vaultPending = vaultRecords.count { !it.isFinished }
                vaultAutoPending = vaultRecords.count { it.auto && !it.isFinished }
            } catch (_: Exception) {
                // ignored
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    val filteredFiles = remember(files, query) {
        val needle = query.trim()
        if (needle.isEmpty()) files else files.filter { it.name.contains(needle, ignoreCase = true) }
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Удалить файл?") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = try {
                        file.delete()
                    } catch (_: Exception) {
                        false
                    }
                    fileToDelete = null
                    files = fileSaver.listFiles()
                    if (!deleted) {
                        Toast.makeText(context, "Не удалось удалить файл", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Отмена") }
            }
        )
    }

    val ipValue = ipText.removePrefix("IP: ")
    val address = if (ipValue.isBlank() || ipValue == "—" || ipValue == "недоступен") {
        "Нет Wi-Fi"
    } else {
        "$ipValue:${Protocol.PORT}"
    }
    val webUrl = httpUrlText.removePrefix("Браузер: ").takeIf { it.startsWith("http") }
    val galleryUrl = galleryUrlText.removePrefix("Галерея: ").takeIf { it.startsWith("http") }
    val shareUrl = ipValue.takeIf { it.isNotBlank() && it != "—" && it != "недоступен" }
        ?.let { ip -> ShareTarget(ip, Protocol.PORT).openUrl }
    val statusHint = when (statusText.removePrefix("Статус: ")) {
        "запущен" -> "Ждём подключения в Wi-Fi"
        "остановлен" -> "Включите, чтобы принимать файлы"
        else -> statusText.removePrefix("Статус: ")
    }

    // Один список на весь экран: карточки и файлы прокручиваются вместе,
    // иначе на невысоких экранах списку файлов почти не остаётся места.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
    ) {
        item {
        BrandHeader()
        Spacer(modifier = Modifier.height(20.dp))

        ServerCard(
            running = isServiceRunning,
            hint = statusHint,
            onCheckedChange = { checked ->
                if (!checked) {
                    context.startService(
                        Intent(context, FileReceiverService::class.java)
                            .setAction(FileReceiverService.ACTION_STOP)
                    )
                    isServiceRunning = false
                } else {
                    // Сервер работает только в локальной Wi-Fi сети: без IP стартовать нечего.
                    val ip = NetworkUtils.getWifiIpAddress(context)
                    if (ip == null) {
                        statusText = if (NetworkUtils.isWifiConnected(context)) {
                            "Не удалось определить IP-адрес"
                        } else {
                            "Нет подключения к Wi-Fi"
                        }
                    } else {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, FileReceiverService::class.java)
                                .setAction(FileReceiverService.ACTION_START)
                        )
                        isServiceRunning = true
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
        AddressCard(
            address = address,
            webUrl = webUrl,
            galleryUrl = galleryUrl,
            shareUrl = shareUrl,
            running = isServiceRunning,
            onOpen = { url -> openBrowser(context, url) },
        )

        Spacer(modifier = Modifier.height(12.dp))
        VaultCard(
            pending = vaultPending,
            autoPending = vaultAutoPending,
            onClick = {
                try {
                    context.startActivity(Intent(context, VaultActivity::class.java))
                } catch (_: Exception) {
                    Toast.makeText(context, "Не удалось открыть хранилище", Toast.LENGTH_SHORT).show()
                }
            },
        )

        // Режим доступа виден только при работающем сервере: тумблер и переключение
        // режима — часть одного действия «открыть телефон в сеть».
        if (isServiceRunning) {
            Spacer(modifier = Modifier.height(12.dp))
            AccessModeCard(
                mode = accessMode,
                fileCount = files.size,
                onModeChange = { mode ->
                    accessMode = mode
                    AccessModeStore.set(context, mode)
                },
                onPickSelection = { showSelection = true },
                onPickDate = { pickDate(context) { epochDay ->
                    val mode = AccessMode.Period.OnDate(epochDay)
                    accessMode = mode
                    AccessModeStore.set(context, mode)
                } },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        ConnectCard(
            hosts = recentHosts,
            onScan = {
                try {
                    context.startActivity(Intent(context, ScanActivity::class.java))
                } catch (_: Exception) {
                    Toast.makeText(context, "Не удалось открыть камеру", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenHost = { target ->
                context.startActivity(
                    Intent(context, RemoteActivity::class.java)
                        .setData(Uri.parse(target.deepLink))
                )
            },
        )

        if (GallerySaver.canPublish()) {
            Spacer(modifier = Modifier.height(12.dp))
            GalleryCopyCard(
                checked = galleryCopy,
                onCheckedChange = { checked ->
                    galleryCopy = checked
                    GallerySaver.setEnabled(context, checked)
                },
            )
        }

        if (!mediaAccess) {
            Spacer(modifier = Modifier.height(12.dp))
            MediaAccessCard(onGrant = { (context as? MainActivity)?.requestMediaPermissions() })
        }

        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Принятые файлы",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            CountChip(
                text = if (query.isBlank()) files.size.toString() else "${filteredFiles.size}/${files.size}"
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Поиск по файлам", fontSize = 14.sp) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = .6f),
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        }

        if (filteredFiles.isEmpty()) {
            item {
                EmptyFiles(
                    title = if (files.isEmpty()) "Пока нет принятых файлов" else "Ничего не найдено",
                    hint = if (files.isEmpty()) {
                        "Перетащите файл в браузере — он появится здесь"
                    } else {
                        "Измените запрос в поиске"
                    }
                )
            }
        }
        items(filteredFiles, key = { it.absolutePath }) { file ->
            ReceivedFileRow(
                file = file,
                onOpen = { openFile(context, file) },
                onDelete = { fileToDelete = file },
                onArchive = { fileToArchive = file },
            )
        }
    }

    if (showSelection) {
        SelectionDialog(
            files = files,
            initial = (accessMode as? AccessMode.Selected)?.names ?: emptySet(),
            onConfirm = { selected ->
                showSelection = false
                val mode: AccessMode = if (selected.isEmpty()) AccessMode.All else AccessMode.Selected(selected)
                accessMode = mode
                AccessModeStore.set(context, mode)
            },
            onDismiss = { showSelection = false },
        )
    }

    fileToArchive?.let { file ->
        ArchiveDialog(
            file = file,
            storageReady = ArchiveManager.isConfigured(context),
            onUploadAndDelete = {
                fileToArchive = null
                ArchiveManager.queueFile(context, file, deleteAfter = true)
                Toast.makeText(context, "В очереди на хранилище", Toast.LENGTH_SHORT).show()
            },
            onUploadKeep = {
                fileToArchive = null
                ArchiveManager.queueFile(context, file, deleteAfter = false)
                Toast.makeText(context, "В очереди на хранилище", Toast.LENGTH_SHORT).show()
            },
            onConfigure = {
                fileToArchive = null
                try {
                    context.startActivity(Intent(context, VaultActivity::class.java))
                } catch (_: Exception) {
                }
            },
            onDismiss = { fileToArchive = null },
        )
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF7B9CFF), Color(0xFFB98CFF))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↓",
                color = Color(0xFF081018),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "LanDrop",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
            )
            Text(
                text = "Файлы по Wi-Fi",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerCard(
    running: Boolean,
    hint: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (running) Color(0xFF3DDC97) else scheme.outline)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "Сервер включён" else "Сервер выключен",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = running,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = scheme.onPrimary,
                    checkedTrackColor = scheme.primary,
                    uncheckedThumbColor = scheme.onSurfaceVariant,
                    uncheckedTrackColor = scheme.surfaceVariant,
                    uncheckedBorderColor = scheme.outline,
                )
            )
        }
    }
}

@Composable
private fun AddressCard(
    address: String,
    webUrl: String?,
    galleryUrl: String?,
    shareUrl: String?,
    running: Boolean,
    onOpen: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "АДРЕС В БРАУЗЕРЕ",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = scheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = address,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "Порт ${Protocol.PORT} · только ваша Wi-Fi сеть",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (shareUrl != null) {
                        Text(
                            text = if (running) {
                                "Наведите камеру: откроется приложение или эта страница"
                            } else {
                                "QR заработает, когда включите сервер"
                            },
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (shareUrl != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    // Тёмный фон не мешает: подложка кода всегда белая.
                    QrCodeImage(
                        content = shareUrl,
                        modifier = Modifier
                            .size(124.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, scheme.outline.copy(alpha = .5f), RoundedCornerShape(12.dp)),
                    )
                }
            }
            if (webUrl != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onOpen(webUrl) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Открыть страницу", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (galleryUrl != null) {
                Text(
                    text = "Галерея: открывается со страницы загрузки",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectCard(
    hosts: List<ShareTarget>,
    onScan: () -> Unit,
    onOpenHost: (ShareTarget) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ПОДКЛЮЧИТЬСЯ К ТЕЛЕФОНУ",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = "Смотрите галерею другого телефона или отправляйте туда файлы.",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onScan,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Сканировать QR", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            hosts.take(3).forEach { host ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenHost(host) }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${host.host}:${host.port}",
                        fontSize = 12.5.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "открыть ›",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryCopyCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Фото и видео в Галерею",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Копировать принятые снимки в Pictures/LanDrop",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = scheme.onPrimary,
                    checkedTrackColor = scheme.primary,
                    uncheckedThumbColor = scheme.onSurfaceVariant,
                    uncheckedTrackColor = scheme.surfaceVariant,
                    uncheckedBorderColor = scheme.outline,
                ),
            )
        }
    }
}

@Composable
private fun MediaAccessCard(onGrant: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Галерея без доступа к фото",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Разрешите доступ, чтобы открывать фото и видео с телефона в браузере.",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            TextButton(onClick = onGrant, contentPadding = PaddingValues(0.dp)) {
                Text("Разрешить доступ", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CountChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyFiles(title: String, hint: String) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, scheme.outline.copy(alpha = .6f), RoundedCornerShape(16.dp))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            text = hint,
            fontSize = 11.5.sp,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ReceivedFileRow(
    file: File,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val kind = kindOf(file)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(kind.color.copy(alpha = .14f))
                .border(1.dp, kind.color.copy(alpha = .28f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = kind.label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                color = kind.color,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
        ) {
            Text(
                text = file.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${FileFormat.size(file.length())} · ${FileFormat.date(file.lastModified())}",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onArchive) {
            Text("В хранилище", fontSize = 11.5.sp)
        }
        TextButton(onClick = onDelete) {
            Text("Удалить", fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = scheme.outlineVariant)
}

@Composable
private fun VaultCard(
    pending: Int,
    autoPending: Int,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF6C8CFF).copy(alpha = .14f))
                    .border(1.dp, Color(0xFF6C8CFF).copy(alpha = .3f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("≡", fontSize = 15.sp, color = Color(0xFF6C8CFF), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Хранилище",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        pending > 0 && autoPending > 0 ->
                            "Ожидают выгрузки: $pending · автоархивация: $autoPending"
                        pending > 0 -> "Ожидают выгрузки: $pending"
                        autoPending > 0 -> "Автоархивация выгружает файлы: $autoPending"
                        else -> "Долгое хранение: WebDAV-папка на вашем сервере"
                    },
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "открыть ›",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Карточка выбора режима частичного просмотра; видна при работающем сервере. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccessModeCard(
    mode: AccessMode,
    fileCount: Int,
    onModeChange: (AccessMode) -> Unit,
    onPickSelection: () -> Unit,
    onPickDate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ЧТО ВИДНО КЛИЕНТАМ",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip("Всё", mode is AccessMode.All) { onModeChange(AccessMode.All) }
                ModeChip("Галерея", mode is AccessMode.GalleryOnly) { onModeChange(AccessMode.GalleryOnly) }
                ModeChip("Выбор…", mode is AccessMode.Selected, onClick = onPickSelection)
                ModeChip("Сегодня", mode is AccessMode.Period.Today) { onModeChange(AccessMode.Period.Today) }
                ModeChip("Вчера", mode is AccessMode.Period.Yesterday) { onModeChange(AccessMode.Period.Yesterday) }
                ModeChip("Дата…", mode is AccessMode.Period.OnDate, onClick = onPickDate)
            }
            Text(
                text = when (mode) {
                    is AccessMode.All -> "Клиенты видят принятые файлы и галерею телефона"
                    is AccessMode.GalleryOnly -> "Видна только галерея телефона, принятые файлы скрыты"
                    is AccessMode.Selected -> "Видны только отмеченные файлы ($fileCount во всей папке)"
                    is AccessMode.Period.Today -> "Видны файлы и фото за сегодня"
                    is AccessMode.Period.Yesterday -> "Видны файлы и фото, полученные вчера"
                    is AccessMode.Period.OnDate -> "Видны файлы и фото за выбранную дату"
                },
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) scheme.primary.copy(alpha = .12f) else scheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary else scheme.outline.copy(alpha = .6f),
        ),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Диалог отбора файлов: только отмеченные видны клиентам в режиме «Выбор…». */
@Composable
private fun SelectionDialog(
    files: List<File>,
    initial: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Что видно клиентам") },
        text = {
            if (files.isEmpty()) {
                Text("Папка приёма пуста")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(files, key = { it.absolutePath }) { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = file.name in selected.value,
                                onCheckedChange = { checked ->
                                    selected.value = if (checked) {
                                        selected.value + file.name
                                    } else {
                                        selected.value - file.name
                                    }
                                },
                            )
                            Text(
                                text = file.name,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.value) },
                enabled = files.isEmpty() || selected.value.isNotEmpty(),
            ) {
                Text("Показывать ${selected.value.size}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Диалог отправки файла в хранилище. Решение «оставить или удалить» —
 * каждый раз спрашивает пользователя.
 */
@Composable
private fun ArchiveDialog(
    file: File,
    storageReady: Boolean,
    onUploadAndDelete: () -> Unit,
    onUploadKeep: () -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отправить в хранилище?") },
        text = {
            Column {
                Text(
                    text = file.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (storageReady) {
                        "Файл выгрузится на ваш WebDAV-сервер."
                    } else {
                        "Хранилище ещё не настроено: нужен адрес WebDAV-папки."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            Column {
                if (storageReady) {
                    TextButton(onClick = onUploadAndDelete) {
                        Text("Выгрузить и удалить с телефона")
                    }
                    TextButton(onClick = onUploadKeep) {
                        Text("Выгрузить, оставить на телефоне")
                    }
                } else {
                    TextButton(onClick = onConfigure) {
                        Text("Настроить хранилище")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
            }
        },
    )
}

/** Системный диалог выбора даты; в режиме остаётся ссылка на целые сутки. */
private fun pickDate(context: android.content.Context, onPicked: (Long) -> Unit) {
    val today = Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utc.clear()
            utc.set(year, month, dayOfMonth, 12, 0, 0)
            onPicked(utc.timeInMillis / (24L * 60 * 60 * 1000))
        },
        today.get(Calendar.YEAR),
        today.get(Calendar.MONTH),
        today.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private enum class FileKind(val label: String, val color: Color) {
    IMAGE("IMG", Color(0xFFF5A623)),
    VIDEO("VID", Color(0xFFB98CFF)),
    AUDIO("AUD", Color(0xFF38D6C4)),
    DOC("DOC", Color(0xFF6C9CFF)),
    ARCHIVE("ZIP", Color(0xFFFF8A8A)),
    OTHER("FILE", Color(0xFF98A2B3)),
}

private fun kindOf(file: File): FileKind {
    val extension = file.extension.lowercase(Locale.ROOT)
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg", "avif" -> FileKind.IMAGE
        "mp4", "mkv", "mov", "avi", "webm", "m4v" -> FileKind.VIDEO
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> FileKind.AUDIO
        "pdf", "doc", "docx", "txt", "md", "rtf", "xls", "xlsx", "ppt", "pptx" -> FileKind.DOC
        "zip", "rar", "7z", "tar", "gz" -> FileKind.ARCHIVE
        else -> FileKind.OTHER
    }
}

/** Открывает веб-интерфейс в системном браузере. */
private fun openBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "Нет браузера", Toast.LENGTH_SHORT).show()
    }
}

/** Открывает принятый файл во внешнем приложении через FileProvider. */
private fun openFile(context: Context, file: File) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (_: IllegalArgumentException) {
        Toast.makeText(context, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, MimeTypes.forFileName(file.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    try {
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения для этого типа файла", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
    }
}
