package Code_SyS.nas_server

import android.Manifest
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        /** Есть ли доступ к камере: без него кнопка сканера ведёт в пустой экран. */
        fun hasCameraAccess(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
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

    private var cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* статус перечитывается в опросе FileReceiverScreen */ }

    /** Системный запрос камеры из карточки на главном экране. */
    fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
    var cameraAccess by remember { mutableStateOf(MainActivity.hasCameraAccess(context)) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var entries by remember { mutableStateOf<List<StorageEntry>>(emptyList()) }
    /** Текущая папка внутри хранилища: пустая строка — корень. */
    var currentPath by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var filesToDelete by remember { mutableStateOf<List<StorageEntry>>(emptyList()) }
    var filesToArchive by remember { mutableStateOf<List<File>>(emptyList()) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var moveFolders by remember { mutableStateOf<List<String>?>(null) }
    var entryToRename by remember { mutableStateOf<StorageEntry?>(null) }
    val scope = rememberCoroutineScope()

    /** Режим выделения: включается долгим нажатием по плитке файла. */
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
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
                cameraAccess = MainActivity.hasCameraAccess(context)
                val snapshot = withContext(Dispatchers.IO) {
                    fileSaver.listFiles() to fileSaver.entries(currentPath)
                }
                files = snapshot.first
                entries = snapshot.second
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

    val filteredEntries = remember(entries, query) {
        val needle = query.trim()
        if (needle.isEmpty()) entries else entries.filter { it.name.contains(needle, ignoreCase = true) }
    }

    // Выбор храним относительными путями: список обновляется каждую секунду,
    // а выделение не должно от него зависеть.
    val selectedEntries = remember(entries, selectedPaths) {
        entries.filter { it.path in selectedPaths }
    }
    // В хранилище выгружаются только файлы: папки в эту очередь не берём.
    val selectedFiles = remember(selectedEntries) {
        selectedEntries.filterNot { it.isDirectory }.mapNotNull { fileSaver.resolve(it.path) }
    }
    val allVisibleSelected = filteredEntries.isNotEmpty() &&
        filteredEntries.all { it.path in selectedPaths }

    fun clearSelection() {
        selectionMode = false
        selectedPaths = emptySet()
    }

    fun navigateTo(path: String) {
        currentPath = path
        query = ""
        clearSelection()
        scope.launch {
            entries = withContext(Dispatchers.IO) { fileSaver.entries(path) }
        }
    }

    // В режиме выделения «назад» выходит из него, а не закрывает приложение.
    BackHandler(enabled = selectionMode) { clearSelection() }
    // Внутри папки «назад» поднимается на уровень выше.
    BackHandler(enabled = !selectionMode && currentPath.isNotEmpty()) {
        navigateTo(currentPath.substringBeforeLast('/', ""))
    }

    if (filesToDelete.isNotEmpty()) {
        val targets = filesToDelete
        val hasFolder = targets.any { it.isDirectory }
        AlertDialog(
            onDismissRequest = { filesToDelete = emptyList() },
            title = {
                Text(
                    when {
                        targets.size == 1 && targets.first().isDirectory -> "Удалить папку?"
                        targets.size == 1 -> "Удалить файл?"
                        else -> "Удалить ${targets.size} элементов?"
                    }
                )
            },
            text = {
                Text(
                    when {
                        targets.size == 1 -> targets.first().name
                        hasFolder -> "Выбранные файлы и папки будут удалены с телефона. Содержимое папок тоже удалится."
                        else -> "Выбранные файлы будут удалены с телефона."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    filesToDelete = emptyList()
                    scope.launch {
                        var failed = 0
                        withContext(Dispatchers.IO) {
                            for (target in targets) {
                                if (!fileSaver.delete(target.path)) failed++
                            }
                        }
                        files = withContext(Dispatchers.IO) { fileSaver.listFiles() }
                        entries = withContext(Dispatchers.IO) { fileSaver.entries(currentPath) }
                        clearSelection()
                        if (failed > 0) {
                            val message = if (failed == 1) {
                                "Не удалось удалить элемент"
                            } else {
                                "Не удалось удалить элементов: $failed"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { filesToDelete = emptyList() }) { Text("Отмена") }
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

    // Один список на весь экран: карточки и плитки файлов прокручиваются вместе,
    // иначе на невысоких экранах списку файлов почти не остаётся места.
    // Карточки занимают всю ширину строки, а файлы идут по два в ряд.
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
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
            cameraGranted = cameraAccess,
            onGrantCamera = { (context as? MainActivity)?.requestCameraPermission() },
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
        if (selectionMode) {
            SelectionHeader(
                count = selectedEntries.size,
                allSelected = allVisibleSelected,
                canArchive = selectedFiles.isNotEmpty() && ArchiveManager.isConfigured(context),
                onSelectAll = {
                    selectedPaths = if (allVisibleSelected) {
                        emptySet()
                    } else {
                        filteredEntries.map { it.path }.toSet()
                    }
                },
                canRename = selectedEntries.size == 1,
                onDelete = { filesToDelete = selectedEntries },
                onRename = { entryToRename = selectedEntries.firstOrNull() },
                onMove = {
                    moveFolders = fileSaver.folders()
                },
                onArchive = { filesToArchive = selectedFiles },
                onCancel = { clearSelection() },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (currentPath.isEmpty()) "Принятые файлы" else currentPath.substringAfterLast('/'),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(
                    onClick = { showCreateFolder = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("＋ Папка", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                CountChip(
                    text = if (query.isBlank()) entries.size.toString() else "${filteredEntries.size}/${entries.size}"
                )
            }
            if (currentPath.isNotEmpty()) {
                PathCrumbs(
                    path = currentPath,
                    onNavigate = { path -> navigateTo(path) },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Поиск по файлам и папкам", fontSize = 14.sp) },
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

        if (filteredEntries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyFiles(
                    title = when {
                        entries.isEmpty() && currentPath.isEmpty() -> "Пока нет принятых файлов"
                        entries.isEmpty() -> "Папка пуста"
                        else -> "Ничего не найдено"
                    },
                    hint = when {
                        entries.isEmpty() && currentPath.isEmpty() ->
                            "Перетащите файл в браузере — он появится здесь"
                        entries.isEmpty() -> "Загрузите файл в браузере — он попадёт в эту папку"
                        else -> "Измените запрос в поиске"
                    }
                )
            }
        }
        gridItems(filteredEntries, key = { it.path }) { entry ->
            ReceivedFileTile(
                entry = entry,
                file = if (entry.isDirectory) null else fileSaver.resolve(entry.path),
                selected = entry.path in selectedPaths,
                selectionMode = selectionMode,
                onOpenFile = { file -> openFile(context, file) },
                onOpenFolder = { navigateTo(entry.path) },
                onSelect = {
                    selectionMode = true
                    selectedPaths = selectedPaths + entry.path
                },
                onToggle = {
                    selectedPaths = if (entry.path in selectedPaths) {
                        selectedPaths - entry.path
                    } else {
                        selectedPaths + entry.path
                    }
                },
            )
        }
    }

    if (showCreateFolder) {
        CreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onCreate = { name ->
                showCreateFolder = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        fileSaver.createFolder(currentPath, name)
                    }
                    entries = withContext(Dispatchers.IO) { fileSaver.entries(currentPath) }
                    if (created == null) {
                        Toast.makeText(context, "Не удалось создать папку", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    moveFolders?.let { folders ->
        val moveTargets = selectedEntries
        MoveEntriesDialog(
            folders = folders,
            count = moveTargets.size,
            onDismiss = { moveFolders = null },
            onMove = { target ->
                moveFolders = null
                scope.launch {
                    var failed = 0
                    withContext(Dispatchers.IO) {
                        for (entry in moveTargets) {
                            if (fileSaver.move(entry.path, target) == null) failed++
                        }
                    }
                    entries = withContext(Dispatchers.IO) { fileSaver.entries(currentPath) }
                    files = withContext(Dispatchers.IO) { fileSaver.listFiles() }
                    clearSelection()
                    if (failed > 0) {
                        Toast.makeText(
                            context,
                            if (failed == 1) "Не удалось переместить элемент"
                            else "Не удалось переместить: $failed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    entryToRename?.let { entry ->
        RenameEntryDialog(
            currentName = entry.name,
            isDirectory = entry.isDirectory,
            onDismiss = { entryToRename = null },
            onRename = { newName ->
                entryToRename = null
                scope.launch {
                    val renamed = withContext(Dispatchers.IO) {
                        fileSaver.rename(entry.path, newName)
                    }
                    entries = withContext(Dispatchers.IO) { fileSaver.entries(currentPath) }
                    files = withContext(Dispatchers.IO) { fileSaver.listFiles() }
                    clearSelection()
                    if (renamed == null) {
                        Toast.makeText(
                            context,
                            "Не удалось переименовать: имя занято или недопустимо",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
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

    if (filesToArchive.isNotEmpty()) {
        val targets = filesToArchive
        ArchiveDialog(
            files = targets,
            storageReady = ArchiveManager.isConfigured(context),
            onUploadAndDelete = {
                filesToArchive = emptyList()
                for (target in targets) {
                    ArchiveManager.queueFile(context, target, deleteAfter = true)
                }
                clearSelection()
                Toast.makeText(context, "В очереди на хранилище", Toast.LENGTH_SHORT).show()
            },
            onUploadKeep = {
                filesToArchive = emptyList()
                for (target in targets) {
                    ArchiveManager.queueFile(context, target, deleteAfter = false)
                }
                clearSelection()
                Toast.makeText(context, "В очереди на хранилище", Toast.LENGTH_SHORT).show()
            },
            onConfigure = {
                filesToArchive = emptyList()
                try {
                    context.startActivity(Intent(context, VaultActivity::class.java))
                } catch (_: Exception) {
                }
            },
            onDismiss = { filesToArchive = emptyList() },
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
    cameraGranted: Boolean,
    onGrantCamera: () -> Unit,
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

            // Камера нужна только сканеру, поэтому про отказ сообщаем здесь же —
            // иначе пользователь узнаёт о нём уже внутри сканера.
            if (!cameraGranted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceVariant)
                        .border(1.dp, scheme.outline.copy(alpha = .6f), RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Нет доступа к камере: QR не отсканировать",
                        fontSize = 11.5.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onGrantCamera) {
                        Text("Разрешить", fontSize = 12.sp)
                    }
                }
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

/**
 * Панель над плитками в режиме выделения: сколько выбрано, «Все»/«Снять»,
 * массовые действия и выход из режима.
 */
@Composable
private fun SelectionHeader(
    count: Int,
    allSelected: Boolean,
    canArchive: Boolean,
    canRename: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onArchive: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Выбрано $count",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Переименовать можно ровно один элемент — иначе непонятно, что менять.
            if (canRename) {
                TextButton(
                    onClick = onRename,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Переименовать", fontSize = 12.sp)
                }
            }
            TextButton(onClick = onSelectAll) {
                Text(if (allSelected) "Снять" else "Все", fontSize = 12.sp)
            }
            TextButton(onClick = onCancel) {
                Text("Отмена", fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onDelete,
                enabled = count > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.error,
                    contentColor = scheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Удалить", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onMove,
                enabled = count > 0,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                modifier = Modifier.weight(1f),
            ) {
                Text("Переместить", fontSize = 12.sp)
            }
            if (canArchive) {
                OutlinedButton(
                    onClick = onArchive,
                    enabled = count > 0,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Хранилище", fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * Плитка принятого файла: превью или бейдж типа, имя и отметка выделения.
 * Долгое нажатие включает режим выделения, тап в нём — помечает и снимает.
 */
@Composable
private fun ReceivedFileTile(
    entry: StorageEntry,
    file: File?,
    selected: Boolean,
    selectionMode: Boolean,
    onOpenFile: (File) -> Unit,
    onOpenFolder: () -> Unit,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val kind = if (entry.isDirectory) FileKind.FOLDER else file?.let { kindOf(it) } ?: FileKind.OTHER
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggle()
                        entry.isDirectory -> onOpenFolder()
                        file != null -> onOpenFile(file)
                    }
                },
                onLongClick = { if (selected) onToggle() else onSelect() },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(kind.color.copy(alpha = .12f))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) scheme.primary else kind.color.copy(alpha = .28f),
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (file != null) {
                FileThumb(file = file, kind = kind, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    text = kind.label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    color = kind.color,
                )
            }
            if (selectionMode || selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected) scheme.primary else Color(0x66000000))
                        .border(1.dp, Color.White.copy(alpha = .7f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Text("✓", fontSize = 12.sp, color = scheme.onPrimary)
                    }
                }
            }
        }
        Text(
            text = entry.name,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            text = if (entry.isDirectory) "Папка" else FileFormat.size(entry.size),
            fontSize = 10.5.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

/** Путь до текущей папки: «Все файлы / Фото / 2026», каждый сегмент кликабелен. */
@Composable
private fun PathCrumbs(path: String, onNavigate: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Все файлы",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onNavigate("") }
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
        var accumulated = ""
        val segments = path.split('/')
        segments.forEachIndexed { index, segment ->
            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
            Text("/", fontSize = 11.sp, color = scheme.outline)
            val target = accumulated
            Text(
                text = segment,
                fontSize = 11.sp,
                fontWeight = if (index == segments.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                color = if (index == segments.lastIndex) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onNavigate(target) }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
    }
}

/** Диалог создания папки: имя проверяет [ReceivedStorage], занятые имена нумеруются. */
@Composable
private fun CreateFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая папка") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Например, Отпуск", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Диалог переименования одного файла или папки: имя проверяет [ReceivedStorage],
 * занятое имя не затирается — сервер вернёт ошибку, и она покажется тостом.
 */
@Composable
private fun RenameEntryDialog(
    currentName: String,
    isDirectory: Boolean,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val trimmed = name.trim()
    val unchanged = trimmed == currentName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isDirectory) "Переименовать папку" else "Переименовать файл") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(trimmed) },
                enabled = trimmed.isNotEmpty() && !unchanged,
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Диалог перемещения выбранных файлов и папок в другую папку хранилища. */
@Composable
private fun MoveEntriesDialog(
    folders: List<String>,
    count: Int,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переместить") },
        text = {
            Column {
                Text(
                    text = if (count == 1) "1 элемент" else "Элементов: $count",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    item {
                        MoveTargetRow(
                            label = "Все файлы (корень)",
                            selected = target.isEmpty(),
                            onClick = { target = "" },
                        )
                    }
                    items(folders, key = { it }) { folder ->
                        MoveTargetRow(
                            label = folder,
                            selected = target == folder,
                            onClick = { target = folder },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(target) }, enabled = count > 0) {
                Text("Переместить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun MoveTargetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Превью принятого файла: фото и видео — уменьшенным кадром, остальные типы —
 * бейджем. Кадр готовится в фоне, чтобы прокрутка сетки не дёргалась.
 */
@Composable
private fun FileThumb(file: File, kind: FileKind, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        file.absolutePath,
        file.lastModified(),
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Thumbnails.fromFile(file, MimeTypes.forFileName(file.name))
                    ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }.getOrNull()
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = kind.label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                color = kind.color,
            )
        }
    }
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
 * Диалог отправки файлов в хранилище. Решение «оставить или удалить» —
 * каждый раз спрашивает пользователя.
 */
@Composable
private fun ArchiveDialog(
    files: List<File>,
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
                    text = if (files.size == 1) files.first().name else "Файлов: ${files.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (storageReady) {
                        if (files.size == 1) {
                            "Файл выгрузится на ваш WebDAV-сервер."
                        } else {
                            "Файлы выгрузятся на ваш WebDAV-сервер."
                        }
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
    FOLDER("DIR", Color(0xFF7B9CFF)),
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
