package Code_SyS.nas_server

import android.Manifest
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import Code_SyS.nas_server.ui.theme.LanDropTheme
import Code_SyS.nas_server.ui.theme.tint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
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
    // Ресурсы читаем через Compose: иначе смена конфигурации (язык) оставляет
    // в обратных вызовах устаревшие строки, на что и ругается lint.
    val resources = LocalResources.current

    // Строки читаются один раз в Compose-плоскости: внутри лямбд и
    // LaunchedEffect stringResource вызвать нельзя.
    val browserLabelText = stringResource(R.string.browser_label)
    val galleryLabelText = stringResource(R.string.gallery_label)
    val statusRunningText = stringResource(R.string.status_running)
    val statusStoppedText = stringResource(R.string.status_stopped)
    val statusHintRunningText = stringResource(R.string.status_hint_running)
    val statusHintStoppedText = stringResource(R.string.status_hint_stopped)
    val ipLabelText = stringResource(R.string.ip_label)
    val ipUnavailableText = stringResource(R.string.ip_unavailable)
    val ipNoWifiText = stringResource(R.string.no_wifi)
    val errorIpResolveText = stringResource(R.string.error_ip_resolve)
    val errorNoWifiText = stringResource(R.string.error_no_wifi)
    val toastVaultOpenError = stringResource(R.string.toast_vault_open_error)
    val toastCameraOpenError = stringResource(R.string.toast_camera_open_error)
    val toastConfirmOpenError = stringResource(R.string.toast_confirm_open_error)
    val toastCreateError = stringResource(R.string.create_error)
    val pinSavedText = stringResource(R.string.pin_saved)
    val toastRenameError = stringResource(R.string.rename_error)
    val toastMoveErrorSingle = stringResource(R.string.toast_move_error_single)
    val undoLabel = stringResource(R.string.undo)
    val deleteActionLabel = stringResource(R.string.delete_action)
    val cancelButtonLabel = stringResource(R.string.cancel_button)

    var isServiceRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(statusStoppedText) }
    var ipText by remember { mutableStateOf(ipLabelText + " —") }
    var pinCode by remember { mutableStateOf("") }
    var showPinDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        pinCode = PinAuthSettings.load(context).code.orEmpty()
    }
    var httpUrlText by remember { mutableStateOf(browserLabelText + " —") }
    var galleryUrlText by remember { mutableStateOf(galleryLabelText + " —") }
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
    val snackbarHostState = remember { SnackbarHostState() }

    /** Режим выделения: включается долгим нажатием по плитке файла. */
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSelection by remember { mutableStateOf(false) }
    var recentHosts by remember { mutableStateOf(RecentHosts.list(context)) }
    var galleryCopy by remember { mutableStateOf(GallerySaver.isEnabled(context)) }
    var accessMode by remember { mutableStateOf(AccessModeStore.load(context)) }
    var vaultPending by remember { mutableIntStateOf(0) }
    var vaultAutoPending by remember { mutableIntStateOf(0) }
    /** Сколько фото/видео ждёт подтверждения удаления из браузера. */
    var pendingDeletes by remember { mutableIntStateOf(0) }
    val fileSaver = remember { FileSaver(context) }
    /** Очередь отправки выделенных файлов на недавний телефон. */
    val uploadQueue = rememberUploadQueue()
    var pendingSend by remember { mutableStateOf<List<UploadFile>?>(null) }

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

    if (showPinDialog) {
        PinSettingsDialog(
            initial = PinAuthSettings.load(context),
            onSave = { config ->
                PinAuthSettings.save(context, config)
                // Сервер читает PIN из PinAuthCodec на каждый запрос,
                // поэтому перезапуск не нужен: старые сессии сразу станут недействительны.
                PinAuthCodec.update(config)
                pinCode = config.code.orEmpty()
                showPinDialog = false
                Toast.makeText(context, pinSavedText, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showPinDialog = false },
        )
    }

    // IP читается один раз при создании экрана


    LaunchedEffect(Unit) {
        val ip = NetworkUtils.getWifiIpAddress(context)
        ipText = ipLabelText + " ${ip ?: ipUnavailableText}"
        httpUrlText = if (ip != null) browserLabelText + " http://$ip:${Protocol.PORT}" else browserLabelText + " —"
        galleryUrlText = if (ip != null) galleryLabelText + " http://$ip:${Protocol.PORT}/gallery" else galleryLabelText + " —"
    }

    // Статус обновляется при изменении переключателя
    LaunchedEffect(isServiceRunning) {
        statusText = if (isServiceRunning) statusRunningText else statusStoppedText
    }

    // Периодический опрос статуса сервиса и IP (IP меняется при переподключении к Wi-Fi)
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                isServiceRunning = FileReceiverService.isRunning()
                val ip = NetworkUtils.getWifiIpAddress(context)
                ipText = ipLabelText + " ${ip ?: ipUnavailableText}"
                httpUrlText = if (ip != null) browserLabelText + " http://$ip:${Protocol.PORT}" else browserLabelText + " —"
                galleryUrlText = if (ip != null) galleryLabelText + " http://$ip:${Protocol.PORT}/gallery" else galleryLabelText + " —"
                mediaAccess = MainActivity.hasMediaAccess(context)
                cameraAccess = MainActivity.hasCameraAccess(context)
                pendingDeletes = PendingDeletes.count(context)
                val snapshot = withContext(Dispatchers.IO) {
                    fileSaver.listFiles() to fileSaver.entries(currentPath)
                }
                files = snapshot.first
                entries = snapshot.second
                // PIN: обновить код защиты (пустая строка — защита выключена)
                pinCode = PinAuthSettings.load(context).code.orEmpty()

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
                            targets.size == 1 && targets.first().isDirectory -> stringResource(R.string.delete_folder_title)
                            targets.size == 1 -> stringResource(R.string.delete_file_title)
                            else -> pluralStringResource(R.plurals.delete_items_title, targets.size, targets.size)
                        }
                    )
                },
                text = {
                    Text(
                        when {
                            targets.size == 1 -> targets.first().name + "\n\n" + stringResource(R.string.delete_file_body)
                            hasFolder -> stringResource(R.string.delete_folder_body)
                            else -> stringResource(R.string.delete_files_body)
                        }
                    )
                },
            confirmButton = {
                TextButton(onClick = {
                    filesToDelete = emptyList()
                    scope.launch {
                        val trashed = withContext(Dispatchers.IO) {
                            targets.mapNotNull { target -> fileSaver.trash(target.path)?.id }
                        }
                        files = withContext(Dispatchers.IO) { fileSaver.listFiles() }
                        entries = withContext(Dispatchers.IO) { fileSaver.entries(currentPath) }
                        clearSelection()

                        val failedCount = targets.size - trashed.size
                        if (failedCount > 0) {
                            val failedText = if (failedCount == 1) {
                                resources.getString(R.string.delete_error_single)
                            } else {
                                resources.getQuantityString(R.plurals.delete_error_plural, failedCount, failedCount)
                            }
                            Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                        }

                        // Корзина позволяет быстро передумать. Если снекбар уже
                        // закрылся, те же файлы лежат на странице /trash.
                        if (trashed.isNotEmpty()) {
                            val deletedMessage = if (trashed.size == 1 && targets.size == 1) {
                                resources.getString(R.string.deleted_single, targets.first().name)
                            } else {
                                resources.getString(R.string.deleted_plural, trashed.size)
                            }
                            val result = snackbarHostState.showSnackbar(
                                message = deletedMessage,
                                actionLabel = undoLabel,
                                withDismissAction = true,
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                withContext(Dispatchers.IO) {
                                    trashed.forEach { id -> fileSaver.restore(id) }
                                }
                                files = withContext(Dispatchers.IO) { fileSaver.listFiles() }
                                entries = withContext(Dispatchers.IO) { fileSaver.entries(currentPath) }
                            }
                        }
                    }
                }) { Text(deleteActionLabel) }
            },
            dismissButton = {
                TextButton(onClick = { filesToDelete = emptyList() }) { Text(cancelButtonLabel)                }
            }
        )
    }

    // Кнопка настроек PIN: appears only when server is running and PIN is enabled.
    val ipValue = ipText.removePrefix(ipLabelText + " ")
    val address = if (ipValue.isBlank() || ipValue == "—" || ipValue == ipUnavailableText) {
        ipNoWifiText
    } else {
        "$ipValue:${Protocol.PORT}"
    }
    val webUrl = httpUrlText.removePrefix(browserLabelText + " ").takeIf { it.startsWith("http") }
    val galleryUrl = galleryUrlText.removePrefix(galleryLabelText + " ").takeIf { it.startsWith("http") }
    val shareUrl = ipValue.takeIf { it.isNotBlank() && it != "—" && it != ipUnavailableText }
        ?.let { ip -> ShareTarget(ip, Protocol.PORT).openUrl }

    val statusHintText = when (statusText) {
        statusRunningText -> statusHintRunningText
        statusStoppedText -> statusHintStoppedText
        // Ошибка старта сервера показывается вместо подсказки.
        else -> statusText
    }

    // Один список на весь экран: карточки и плитки файлов прокручиваются вместе,
    // иначе на невысоких экранах списку файлов почти не остаётся места.
    // Карточки занимают всю ширину строки, а файлы идут по два в ряд.
    Box(modifier = Modifier.fillMaxSize()) {
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
            // Явная колонка обязательна: дети ленивой ячейки без контейнера
            // раскладываются не по порядку и часть карточек пропадает.
            Column(modifier = Modifier.fillMaxWidth()) {
            BrandHeader()
            Spacer(modifier = Modifier.height(20.dp))

            ServerCard(
                running = isServiceRunning,
                hint = statusHintText,
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
                                errorIpResolveText
                            } else {
                                errorNoWifiText
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
            PinSettingsButton(
                code = pinCode.ifEmpty { null },
                onSettingsClick = { showPinDialog = true },
            )

            Spacer(modifier = Modifier.height(12.dp))
            AddressCard(
                address = address,
                webUrl = webUrl,
                galleryUrl = galleryUrl,
                libraryUrl = galleryUrl?.replace("/gallery", "/library"),
                shareUrl = shareUrl,
                running = isServiceRunning,
                onOpen = { url -> openUrl(context, url, resources.getString(R.string.toast_no_browser)) },
            )

            Spacer(modifier = Modifier.height(12.dp))
            VaultCard(
                pending = vaultPending,
                autoPending = vaultAutoPending,
                onClick = {
                    try {
                        context.startActivity(Intent(context, VaultActivity::class.java))
                    } catch (_: Exception) {
                        Toast.makeText(context, toastVaultOpenError, Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(context, toastCameraOpenError, Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenHost = { target ->
                    context.startActivity(
                        Intent(context, RemoteActivity::class.java)
                            .setData(target.deepLink.toUri())
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

        if (pendingDeletes > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            DeleteConsentCard(
                count = pendingDeletes,
                onConfirm = {
                    try {
                        context.startActivity(Intent(context, DeleteConsentActivity::class.java))
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            toastConfirmOpenError,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
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
                    canSend = selectedFiles.isNotEmpty() && recentHosts.isNotEmpty(),
                    onSend = {
                        pendingSend = selectedFiles.map { file ->
                            UploadFile(file.name, file.length()) {
                                runCatching { file.inputStream() }.getOrNull()
                            }
                        }
                    },
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
                        text = if (currentPath.isEmpty()) stringResource(R.string.received_files) else currentPath.substringAfterLast('/'),
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
                        Text(stringResource(R.string.add_folder), fontSize = 12.sp)
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
                placeholder = { Text(stringResource(R.string.search_placeholder), fontSize = 14.sp) },
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
            }

            if (filteredEntries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyFiles(
                        title = when {
                            entries.isEmpty() && currentPath.isEmpty() -> stringResource(R.string.no_files)
                            entries.isEmpty() -> stringResource(R.string.folder_empty)
                            else -> stringResource(R.string.nothing_found)
                        },
                        hint = when {
                            entries.isEmpty() && currentPath.isEmpty() -> stringResource(R.string.drop_hint)
                            entries.isEmpty() -> stringResource(R.string.upload_hint)
                            else -> stringResource(R.string.change_query)
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
                    onMenuRename = { entryToRename = entry },
                    onMenuMove = {
                        selectedPaths = setOf(entry.path)
                        moveFolders = fileSaver.folders()
                    },
                    onMenuDelete = { filesToDelete = listOf(entry) },
                )
            }
        }
    // «Отменить» после удаления: файлы лежат в корзине и доступны со страницы /trash.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(16.dp),
    )
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
                        Toast.makeText(context, toastCreateError, Toast.LENGTH_SHORT).show()
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
                        val moveErrorMsg = if (failed == 1) {
                            toastMoveErrorSingle
                        } else {
                            resources.getString(R.string.toast_move_error_plural, failed)
                        }
                        Toast.makeText(context, moveErrorMsg, Toast.LENGTH_SHORT).show()
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
                            toastRenameError,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    if (pendingSend != null) {
        HostPickerDialog(
            title = stringResource(R.string.send_to_phone_title),
            hosts = recentHosts,
            onPick = { target ->
                val files = pendingSend.orEmpty()
                pendingSend = null
                uploadQueue.enqueue(target, files)
                clearSelection()
            },
            onDismiss = { pendingSend = null },
        )
    }

    if (uploadQueue.open) {
        UploadQueueDialog(
            queue = uploadQueue,
            onClose = { uploadQueue.dismiss() },
        )
    }

    // Пачка ушла: запоминаем телефон и говорим, сколько файлов принято.
    LaunchedEffect(uploadQueue.completed) {
        val done = uploadQueue.completed ?: return@LaunchedEffect
        val target = uploadQueue.target ?: return@LaunchedEffect
        val sent = done.delivered
        if (sent > 0) {
            RecentHosts.remember(context, target)
            recentHosts = RecentHosts.list(context)
            Toast.makeText(
                context,
                if (sent == 1) {
                    resources.getString(R.string.send_single, target.host)
                } else {
                    resources.getQuantityString(R.plurals.send_plural, sent, sent)
                },
                Toast.LENGTH_SHORT,
            ).show()
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
                Toast.makeText(context, resources.getString(R.string.vault_queue_toast), Toast.LENGTH_SHORT).show()
            },
            onUploadKeep = {
                filesToArchive = emptyList()
                for (target in targets) {
                    ArchiveManager.queueFile(context, target, deleteAfter = false)
                }
                clearSelection()
                Toast.makeText(context, resources.getString(R.string.vault_queue_toast), Toast.LENGTH_SHORT).show()
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

/** Настройка PIN: включение защиты и ввод нового четырёхзначного кода. */
@Composable
private fun PinSettingsDialog(
    initial: PinAuthConfig,
    onSave: (PinAuthConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var pinInput by remember { mutableStateOf(initial.code.orEmpty()) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    val codeValid = pinInput.length == PinAuthSettings.PIN_LENGTH && pinInput.all { it.isDigit() }
    val showError = pinInput.isNotEmpty() && !codeValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_dialog_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pin_dialog_enable), fontSize = 14.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { value ->
                            pinInput = value.filter { it.isDigit() }
                                .take(PinAuthSettings.PIN_LENGTH)
                        },
                        label = { Text(stringResource(R.string.pin_dialog_hint)) },
                        singleLine = true,
                        isError = showError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (showError) {
                        Text(
                            text = stringResource(R.string.pin_invalid),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { pinInput = PinAuthSettings.generateCode() }) {
                            Text(stringResource(R.string.pin_generate), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !enabled || codeValid,
                onClick = {
                    onSave(
                        if (enabled) {
                            PinAuthConfig(enabled = true, code = pinInput)
                        } else {
                            PinAuthConfig(enabled = false, code = null)
                        }
                    )
                },
            ) { Text(stringResource(R.string.save_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}

/**
 * Единственная строка про PIN на главном экране: показывает состояние защиты
 * и открывает [PinSettingsDialog] по «Изменить». `code == null` — защита выключена.
 */
@Composable
private fun PinSettingsButton(code: String?, onSettingsClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val active = code != null
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceVariant.copy(alpha = .6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) scheme.primary else scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (code != null) {
                    stringResource(R.string.pin_enabled_with_code, code)
                } else {
                    stringResource(R.string.pin_disabled)
                },
                fontSize = 12.5.sp,
                color = if (active) scheme.primary else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSettingsClick) {
                Text(
                    text = stringResource(R.string.pin_change_button),
                    fontSize = 11.sp,
                )
            }
        }
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
                text = stringResource(R.string.app_tagline),
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
                    text = if (running) stringResource(R.string.server_on) else stringResource(R.string.server_off),
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
    libraryUrl: String?,
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
                text = stringResource(R.string.address_section),
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
                        text = stringResource(R.string.port_label, Protocol.PORT),
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (shareUrl != null) {
                        Text(
                            text = if (running) {
                                stringResource(R.string.qr_camera_hint)
                            } else {
                                stringResource(R.string.qr_server_hint)
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
                    Text(stringResource(R.string.open_page), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (galleryUrl != null || libraryUrl != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (galleryUrl != null) {
                        OutlinedButton(
                            onClick = { onOpen(galleryUrl) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.gallery), fontSize = 12.sp)
                        }
                    }
                    if (libraryUrl != null) {
                        OutlinedButton(
                            onClick = { onOpen(libraryUrl) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.library), fontSize = 12.sp)
                        }
                    }
                }
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
                text = stringResource(R.string.connect_section),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.connect_desc),
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
                Text(stringResource(R.string.scan_qr), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                        text = stringResource(R.string.no_camera_access),
                        fontSize = 11.5.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onGrantCamera) {
                        Text(stringResource(R.string.grant_camera), fontSize = 12.sp)
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
                        text = stringResource(R.string.open_host),
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
                    text = stringResource(R.string.gallery_copy_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.gallery_copy_desc),
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
                text = stringResource(R.string.media_access_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.media_access_desc),
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            TextButton(onClick = onGrant, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.grant_media_access), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DeleteConsentCard(count: Int, onConfirm: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.error.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.delete_confirm_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (count == 1) {
                    stringResource(R.string.delete_confirm_single)
                } else {
                    pluralStringResource(R.plurals.delete_confirm_plural, count, count)
                },
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            TextButton(onClick = onConfirm, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.delete_confirm_button), fontSize = 12.sp)
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
    canSend: Boolean,
    onSelectAll: () -> Unit,
    onSend: () -> Unit,
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
                text = stringResource(R.string.selected_count, count),
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
                    Text(stringResource(R.string.rename_action), fontSize = 12.sp)
                }
            }
            TextButton(onClick = onSelectAll) {
                Text(if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all), fontSize = 12.sp)
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel_button), fontSize = 12.sp)
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
                Text(stringResource(R.string.delete_action), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onMove,
                enabled = count > 0,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.move_action), fontSize = 12.sp)
            }
            if (canArchive) {
                OutlinedButton(
                    onClick = onArchive,
                    enabled = count > 0,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.archive_action), fontSize = 12.sp)
                }
            }
        }
        // Отправка на телефон — отдельной строкой: четыре кнопки в ряд
        // на узком экране превращают подписи в кашу.
        if (canSend) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSend,
                enabled = count > 0,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.send_to_phone_action), fontSize = 12.sp)
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
    onMenuRename: () -> Unit,
    onMenuMove: () -> Unit,
    onMenuDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val kind = if (entry.isDirectory) FileKind.FOLDER else file?.let { FileKind.ofFileName(it.name) } ?: FileKind.OTHER
    val shape = RoundedCornerShape(14.dp)
    var menuOpen by remember { mutableStateOf(false) }
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
                .background(kind.tint.copy(alpha = .12f))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) scheme.primary else kind.tint.copy(alpha = .28f),
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (file != null) {
                FileThumb(file = file, kind = kind, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    text = kind.badge,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    color = kind.tint,
                )
            }
            // Меню на каждой плитке: удалить и переименовать можно без
            // долгого нажатия и без входа в режим выделения.
            if (!selectionMode && !selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .border(1.dp, Color.White.copy(alpha = .55f), CircleShape)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋮", fontSize = 16.sp, color = Color.White)
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename_action), fontSize = 13.sp) },
                            onClick = {
                                menuOpen = false
                                onMenuRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_action), fontSize = 13.sp) },
                            onClick = {
                                menuOpen = false
                                onMenuMove()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_action), fontSize = 13.sp, color = scheme.error) },
                            onClick = {
                                menuOpen = false
                                onMenuDelete()
                            },
                        )
                    }
                }
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
            text = if (entry.isDirectory) {
                stringResource(R.string.folder_label)
            } else {
                FileFormat.size(androidx.compose.ui.platform.LocalResources.current, entry.size)
            },
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
            text = stringResource(R.string.all_files),
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
        title = { Text(stringResource(R.string.create_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.create_folder_example), fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.create_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
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
        title = { Text(stringResource(if (isDirectory) R.string.rename_folder else R.string.rename_file)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name_label), fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(trimmed) },
                enabled = trimmed.isNotEmpty() && !unchanged,
            ) { Text(stringResource(R.string.save_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
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
        title = { Text(stringResource(R.string.move_title)) },
        text = {
            Column {
                Text(
                    text = if (count == 1) {
                        stringResource(R.string.move_item_single)
                    } else {
                        stringResource(R.string.move_items_plural, count)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    item {
                        MoveTargetRow(
                            label = stringResource(R.string.move_root),
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
                Text(stringResource(R.string.move_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
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
                text = kind.badge,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                color = kind.tint,
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
                    text = stringResource(R.string.vault_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        pending > 0 && autoPending > 0 ->
                            stringResource(R.string.vault_both, pending, autoPending)
                        pending > 0 -> stringResource(R.string.vault_uploads, pending)
                        autoPending > 0 -> stringResource(R.string.vault_auto, autoPending)
                        else -> stringResource(R.string.vault_desc)
                    },
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = stringResource(R.string.vault_open),
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
                text = stringResource(R.string.access_section),
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
                ModeChip(stringResource(R.string.access_all), mode is AccessMode.All) { onModeChange(AccessMode.All) }
                ModeChip(stringResource(R.string.access_gallery), mode is AccessMode.GalleryOnly) { onModeChange(AccessMode.GalleryOnly) }
                ModeChip(stringResource(R.string.access_selected), mode is AccessMode.Selected, onClick = onPickSelection)
                ModeChip(stringResource(R.string.access_today), mode is AccessMode.Period.Today) { onModeChange(AccessMode.Period.Today) }
                ModeChip(stringResource(R.string.access_yesterday), mode is AccessMode.Period.Yesterday) { onModeChange(AccessMode.Period.Yesterday) }
                ModeChip(stringResource(R.string.access_date), mode is AccessMode.Period.OnDate, onClick = onPickDate)
            }
            Text(
                text = when (mode) {
                    is AccessMode.All -> stringResource(R.string.access_desc_all)
                    is AccessMode.GalleryOnly -> stringResource(R.string.access_desc_gallery)
                    is AccessMode.Selected -> stringResource(R.string.access_desc_selected, fileCount)
                    is AccessMode.Period.Today -> stringResource(R.string.access_desc_today)
                    is AccessMode.Period.Yesterday -> stringResource(R.string.access_desc_yesterday)
                    is AccessMode.Period.OnDate -> stringResource(R.string.access_desc_date)
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
        title = { Text(stringResource(R.string.selection_title)) },
        text = {
            if (files.isEmpty()) {
                Text(stringResource(R.string.selection_empty))
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
                Text(stringResource(R.string.selection_show, selected.value.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
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
        title = { Text(stringResource(R.string.archive_dialog_title)) },
        text = {
            Column {
                Text(
                    text = if (files.size == 1) files.first().name else stringResource(R.string.archive_files_count, files.size),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (storageReady) {
                        if (files.size == 1) {
                            stringResource(R.string.archive_file_upload_single)
                        } else {
                            stringResource(R.string.archive_file_upload_plural)
                        }
                    } else {
                        stringResource(R.string.archive_not_configured)
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
                        Text(stringResource(R.string.archive_upload_delete))
                    }
                    TextButton(onClick = onUploadKeep) {
                        Text(stringResource(R.string.archive_upload_keep))
                    }
                } else {
                    TextButton(onClick = onConfigure) {
                        Text(stringResource(R.string.archive_configure))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel_button))
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

/** Открывает принятый файл во внешнем приложении через FileProvider. */
private fun openFile(context: Context, file: File) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (_: IllegalArgumentException) {
        Toast.makeText(context, context.getString(R.string.toast_open_file_error), Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, MimeTypes.forFileName(file.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    try {
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.toast_no_app_for_file), Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.toast_open_file_error), Toast.LENGTH_SHORT).show()
    }
}
