package Code_SyS.nas_server

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import Code_SyS.nas_server.ui.theme.LanDropTheme
import Code_SyS.nas_server.ui.theme.tint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Окно удалённого просмотра: открывается по QR и работает как браузерная
 * страница — список принятых файлов, галерея с просмотром и отправка файлов
 * на телефон-хост.
 */
class RemoteActivity : ComponentActivity() {

    private var target by mutableStateOf<ShareTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        target = ShareLinks.parseDeepLink(intent?.dataString)
        if (target == null) {
            Toast.makeText(this, getString(R.string.remote_bad_link), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            LanDropTheme {
                val current = target
                if (current != null) {
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
                        RemoteScreen(
                            target = current,
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareLinks.parseDeepLink(intent.dataString)?.let { target = it }
    }
}

private enum class RemoteTab { FILES, GALLERY }

private data class ViewerState(val items: List<RemoteMediaItem>, val index: Int)

@Composable
private fun RemoteScreen(target: ShareTarget, onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scheme = MaterialTheme.colorScheme
    val client = remember(target.host, target.port) { RemoteClient(target.host, target.port) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(RemoteTab.FILES) }
    var files by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var galleries by remember { mutableStateOf<List<RemoteGallery>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    val uploadQueue = rememberUploadQueue()
    var filesToDelete by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    var joined by remember { mutableStateOf(false) }
    /** Телефоны, на которые можно переслать файлы с хоста; сам хост не в списке. */
    var otherHosts by remember(target) { mutableStateOf<List<ShareTarget>>(emptyList()) }
    var sendTargets by remember { mutableStateOf<List<RemoteFile>?>(null) }
    /** Текущая папка на хосте; пустая строка — корень. */
    var currentPath by remember { mutableStateOf("") }
    var showCreateFolder by remember { mutableStateOf(false) }
    var entryToRename by remember { mutableStateOf<RemoteFile?>(null) }
    var entriesToMove by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var moveFolders by remember { mutableStateOf<List<String>?>(null) }
    // Путь фиксируем в момент выбора файлов: за время диалога можно уйти в другую папку.
    var uploadTarget by remember { mutableStateOf("") }

    // Ручное и автоматическое обновление не должны идти одновременно.
    val refreshLock = remember { Mutex() }

    suspend fun refresh(showSpinner: Boolean) {
        if (!refreshLock.tryLock()) return
        if (showSpinner) refreshing = true
        try {
            val path = currentPath
            val (loadedFiles, loadedGalleries) = withContext(Dispatchers.IO) {
                client.files(path) to client.galleries()
            }
            files = loadedFiles
            galleries = loadedGalleries
            // Ошибку убираем только после успеха: иначе баннер мигал бы
            // при каждом автоматическом опросе недоступного сервера.
            loadError = null
            if (!joined) {
                joined = true
                RecentHosts.remember(context, target)
            }
        } catch (e: Exception) {
            loadError = e.message ?: resources.getString(R.string.remote_server_unavailable)
        } finally {
            refreshing = false
            refreshLock.unlock()
        }
    }

    // Живое обновление: новые файлы с хоста появляются сами. Пока экран
    // на паузе (приложение свёрнуто), сеть не дёргаем.
    var screenResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> screenResumed = true
                Lifecycle.Event.ON_PAUSE -> screenResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(client) {
        refresh(showSpinner = true)
        while (isActive) {
            delay(AUTO_REFRESH_MS)
            if (screenResumed) refresh(showSpinner = false)
        }
    }

    fun openFolder(path: String) {
        currentPath = path
        files = null
        query = ""
        scope.launch { refresh(showSpinner = false) }
    }

    // Список недавних телефонов появляется после первого успешного подключения.
    LaunchedEffect(joined) {
        if (joined) otherHosts = RecentHosts.list(context).filter { it != target }
    }

    // Пачка ушла: обновляем список и убираем строки очереди.
    LaunchedEffect(uploadQueue.completed) {
        if (uploadQueue.completed == null) return@LaunchedEffect
        refresh(showSpinner = false)
        delay(3_000)
        uploadQueue.dismiss()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val destination = uploadTarget

        scope.launch {
            val sharedFiles = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> SharedFile.read(context, uri) }
            }
            if (sharedFiles.isEmpty()) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.remote_read_error),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            // Файл без известного размера сервер не примет — очередь покажет
            // его как «пропущен», а остальные отправит.
            uploadQueue.enqueue(
                target = target,
                path = destination,
                files = sharedFiles.map { shared ->
                    UploadFile(shared.name, shared.size) { shared.openStream(context) }
                },
            )
        }
    }

    sendTargets?.let { chosen ->
        HostPickerDialog(
            title = androidx.compose.ui.res.stringResource(R.string.send_to_phone_title),
            hosts = otherHosts,
            onPick = { destination ->
                sendTargets = null
                // Файлы читаются с текущего хоста и сразу уходят на выбранный
                // телефон: промежуточная копия на устройстве не нужна.
                uploadQueue.enqueue(
                    target = destination,
                    files = chosen.map { file ->
                        UploadFile(file.name, file.size) { client.openFile(file.path) }
                    },
                )
            },
            onDismiss = { sendTargets = null },
        )
    }

    if (filesToDelete.isNotEmpty()) {
        val targets = filesToDelete
        AlertDialog(
            onDismissRequest = { filesToDelete = emptyList() },
            title = {
                Text(
                    when {
                        targets.size == 1 && targets.first().isDirectory ->
                            androidx.compose.ui.res.stringResource(R.string.remote_delete_folder_title)
                        targets.size == 1 ->
                            androidx.compose.ui.res.stringResource(R.string.remote_delete_file_title)
                        else -> androidx.compose.ui.res.pluralStringResource(
                            R.plurals.remote_delete_items_title,
                            targets.size,
                            targets.size,
                        )
                    }
                )
            },
            text = {
                Text(
                    when {
                        targets.size == 1 -> targets.first().name
                        targets.any { it.isDirectory } ->
                            androidx.compose.ui.res.stringResource(R.string.remote_delete_folder_body)
                        else -> androidx.compose.ui.res.stringResource(R.string.remote_delete_files_body)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    filesToDelete = emptyList()
                    scope.launch {
                        var failed = 0
                        var firstError: String? = null
                        withContext(Dispatchers.IO) {
                            for (target in targets) {
                                try {
                                    client.deleteFile(target.path, recursive = target.isDirectory)
                                } catch (e: Exception) {
                                    failed++
                                    if (firstError == null) firstError = e.message
                                }
                            }
                        }
                        refresh(showSpinner = false)
                        if (failed > 0) {
                            val message = if (failed == 1) {
                                firstError ?: resources.getString(R.string.delete_error_single)
                            } else {
                                resources.getQuantityString(R.plurals.delete_error_plural, failed, failed)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(androidx.compose.ui.res.stringResource(R.string.delete_action)) }
            },
            dismissButton = {
                TextButton(onClick = { filesToDelete = emptyList() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.cancel_button))
                }
            },
        )
    }

    if (showCreateFolder) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.create_folder_example),
                            fontSize = 14.sp,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = folderName.trim()
                        if (name.isEmpty()) return@TextButton
                        showCreateFolder = false
                        scope.launch {
                            val created = runCatching {
                                withContext(Dispatchers.IO) { client.createFolder(currentPath, name) }
                            }.isSuccess
                            if (created) {
                                refresh(showSpinner = false)
                            } else {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.create_error),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text(androidx.compose.ui.res.stringResource(R.string.create_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolder = false }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.cancel_button))
                }
            },
        )
    }

    entryToRename?.let { entry ->
        RemoteRenameDialog(
            currentName = entry.name,
            isDirectory = entry.isDirectory,
            onDismiss = { entryToRename = null },
            onRename = { newName ->
                entryToRename = null
                scope.launch {
                    val renamed = runCatching {
                        withContext(Dispatchers.IO) { client.rename(entry.path, newName) }
                    }.isSuccess
                    refresh(showSpinner = false)
                    if (!renamed) {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.rename_error),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    if (entriesToMove.isNotEmpty()) {
        val folders = moveFolders
        if (folders != null) {
            val moving = entriesToMove
            RemoteMoveDialog(
                folders = folders,
                count = moving.size,
                onDismiss = {
                    entriesToMove = emptyList()
                    moveFolders = null
                },
                onMove = { target ->
                    entriesToMove = emptyList()
                    moveFolders = null
                    scope.launch {
                        var failed = 0
                        withContext(Dispatchers.IO) {
                            for (entry in moving) {
                                try {
                                    client.move(entry.path, target)
                                } catch (_: Exception) {
                                    failed++
                                }
                            }
                        }
                        refresh(showSpinner = false)
                        if (failed > 0) {
                            Toast.makeText(
                                context,
                                if (failed == 1) {
                                    resources.getString(R.string.toast_move_error_single)
                                } else {
                                    resources.getString(R.string.toast_move_error_plural, failed)
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }
    }

    // Внутри папки системный «назад» поднимается на уровень выше.
    BackHandler(enabled = currentPath.isNotEmpty()) {
        openFolder(currentPath.substringBeforeLast('/', ""))
    }

    viewer?.let { state ->
        RemoteViewer(
            client = client,
            state = state,
            onClose = { viewer = null },
            onExternal = { item -> openUrl(context, client.browserUrl(item.url)) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(scheme.surface)
                    .border(1.dp, scheme.outline.copy(alpha = .55f), CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("←", fontSize = 19.sp, color = scheme.onSurface)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.remote_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.remote_subtitle,
                        "${target.host}:${target.port}",
                    ),
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(scheme.surface)
                    .border(1.dp, scheme.outline.copy(alpha = .55f), CircleShape)
                    .clickable { scope.launch { refresh(showSpinner = true) } },
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                } else {
                    Text("↻", fontSize = 18.sp, color = scheme.onSurface)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        TabSwitcher(
            tab = tab,
            filesCount = files?.size ?: 0,
            onSelect = { tab = it },
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (loadError != null) {
            ErrorCard(message = loadError!!, onRetry = { scope.launch { refresh(showSpinner = true) } })
            Spacer(modifier = Modifier.height(10.dp))
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                RemoteTab.FILES -> FilesTab(
                    client = client,
                    files = files,
                    currentPath = currentPath,
                    query = query,
                    onQueryChange = { query = it },
                    uploadQueue = uploadQueue,
                    canSend = otherHosts.isNotEmpty() && !uploadQueue.summary.running,
                    onSendMany = { chosen -> sendTargets = chosen },
                    onOpen = { file ->
                        if (file.isDirectory) {
                            openFolder(file.path)
                        } else {
                            openUrl(context, client.browserUrl(client.filePath(file.path)))
                        }
                    },
                    onNavigateUp = {
                        openFolder(currentPath.substringBeforeLast('/', ""))
                    },
                    onCreateFolder = { showCreateFolder = true },
                    onRename = { entryToRename = it },
                    onDelete = { filesToDelete = listOf(it) },
                    onDeleteMany = { filesToDelete = it },
                    onMoveMany = { chosen ->
                        scope.launch {
                            val folders = runCatching {
                                withContext(Dispatchers.IO) { client.folders() }
                            }.getOrNull()
                            if (folders == null) {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.remote_folders_error),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                // Внутрь самой себя или своей вложенной папки перемещать нельзя.
                                moveFolders = folders.filterNot { folder ->
                                    chosen.any { entry ->
                                        entry.isDirectory &&
                                            (folder == entry.path || folder.startsWith(entry.path + "/"))
                                    }
                                }
                                entriesToMove = chosen
                            }
                        }
                    },
                )

                RemoteTab.GALLERY -> GalleryTab(
                    client = client,
                    galleries = galleries,
                    selectedSource = selectedSource,
                    onSelectSource = { selectedSource = it },
                    onOpen = { items, index -> viewer = ViewerState(items, index) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { openUrl(context, client.browserUrl()) },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                modifier = Modifier.weight(1f),
            ) {
                Text(androidx.compose.ui.res.stringResource(R.string.remote_open_browser), fontSize = 13.sp)
            }
            Button(
                onClick = {
                    try {
                        uploadTarget = currentPath
                        picker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.remote_no_picker),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
                modifier = Modifier.weight(1.4f),
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.send_files),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun TabSwitcher(tab: RemoteTab, filesCount: Int, onSelect: (RemoteTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = .55f))
            .padding(4.dp)
    ) {
        TabPill(
            text = androidx.compose.ui.res.stringResource(R.string.remote_tab_files) +
                if (filesCount > 0) " · $filesCount" else "",
            selected = tab == RemoteTab.FILES,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(RemoteTab.FILES) },
        )
        TabPill(
            text = androidx.compose.ui.res.stringResource(R.string.gallery),
            selected = tab == RemoteTab.GALLERY,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(RemoteTab.GALLERY) },
        )
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) scheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilesTab(
    client: RemoteClient,
    files: List<RemoteFile>?,
    currentPath: String,
    query: String,
    onQueryChange: (String) -> Unit,
    uploadQueue: UploadQueueState,
    canSend: Boolean,
    onSendMany: (List<RemoteFile>) -> Unit,
    onOpen: (RemoteFile) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateFolder: () -> Unit,
    onRename: (RemoteFile) -> Unit,
    onDelete: (RemoteFile) -> Unit,
    onDeleteMany: (List<RemoteFile>) -> Unit,
    onMoveMany: (List<RemoteFile>) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val visible = remember(files, query) {
        val needle = query.trim()
        val source = files.orEmpty()
        if (needle.isEmpty()) source else source.filter { it.name.contains(needle, ignoreCase = true) }
    }

    // Выделение держим путями: список с хоста обновляется сам, а выбор
    // не должен зависеть от этих обновлений. При смене папки выбор сбрасывается.
    var selectionMode by remember(currentPath) { mutableStateOf(false) }
    var selected by remember(currentPath) { mutableStateOf<Set<String>>(emptySet()) }
    val selectedFiles = remember(files, selected) {
        files.orEmpty().filter { it.path in selected }
    }
    val allSelected = visible.isNotEmpty() && visible.all { it.path in selected }

    fun toggleSelection(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun clearSelection() {
        selectionMode = false
        selected = emptySet()
    }

    // В режиме выделения «назад» выходит из него, а не закрывает окно.
    BackHandler(enabled = selectionMode) { clearSelection() }

    Column(modifier = Modifier.fillMaxSize()) {
        for (task in uploadQueue.tasks) {
            UploadRow(task = task, onCancel = { uploadQueue.cancel(task.id) })
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (selectionMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.selected_count,
                        selectedFiles.size,
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    selected = if (allSelected) emptySet() else visible.map { it.path }.toSet()
                }) {
                    Text(
                        if (allSelected) {
                            androidx.compose.ui.res.stringResource(R.string.deselect_all)
                        } else {
                            androidx.compose.ui.res.stringResource(R.string.select_all)
                        },
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { clearSelection() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.cancel_button), fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val chosen = selectedFiles
                        clearSelection()
                        onMoveMany(chosen)
                    },
                    enabled = selectedFiles.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.move_action), fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val chosen = selectedFiles
                        clearSelection()
                        onDeleteMany(chosen)
                    },
                    enabled = selectedFiles.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.onError,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (selectedFiles.size == 1) {
                            androidx.compose.ui.res.stringResource(R.string.remote_delete_file)
                        } else {
                            androidx.compose.ui.res.stringResource(R.string.remote_delete_selected)
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // Пересылка на другой телефон — отдельной строкой: в тесном ряду
            // с «Переместить» и «Удалить» подпись не помещается.
            if (canSend) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val chosen = selectedFiles
                        clearSelection()
                        onSendMany(chosen)
                    },
                    enabled = selectedFiles.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.send_to_phone_action),
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentPath.isNotEmpty()) {
                    TextButton(
                        onClick = onNavigateUp,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.back_button),
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        text = currentPath,
                        fontSize = 11.5.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onCreateFolder,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.add_folder),
                        fontSize = 12.sp,
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.search_placeholder),
                        fontSize = 14.sp,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.surface,
                    unfocusedContainerColor = scheme.surface,
                    focusedBorderColor = scheme.primary,
                    unfocusedBorderColor = scheme.outline.copy(alpha = .6f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (files == null) {
            LoadingBox()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (visible.isEmpty()) {
                    item {
                        EmptyCard(
                            title = when {
                                files.isEmpty() && currentPath.isEmpty() ->
                                    androidx.compose.ui.res.stringResource(R.string.remote_no_files)
                                files.isEmpty() ->
                                    androidx.compose.ui.res.stringResource(R.string.folder_empty)
                                else -> androidx.compose.ui.res.stringResource(R.string.nothing_found)
                            },
                            hint = when {
                                files.isEmpty() && currentPath.isEmpty() ->
                                    androidx.compose.ui.res.stringResource(R.string.remote_drop_hint)
                                files.isEmpty() ->
                                    androidx.compose.ui.res.stringResource(R.string.remote_upload_hint)
                                else -> androidx.compose.ui.res.stringResource(R.string.change_query)
                            },
                        )
                    }
                }
                items(visible, key = { it.path }) { file ->
                    RemoteFileRow(
                        client = client,
                        file = file,
                        selected = file.path in selected,
                        selectionMode = selectionMode,
                        onOpen = { onOpen(file) },
                        onLongPress = {
                            selectionMode = true
                            selected = selected + file.path
                        },
                        onToggle = { toggleSelection(file.path) },
                        onRename = { onRename(file) },
                        onDelete = { onDelete(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteFileRow(
    client: RemoteClient,
    file: RemoteFile,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val badge = if (file.isDirectory) FileKind.FOLDER.badge to FileKind.FOLDER.tint else fileBadge(file.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { if (selectionMode) onToggle() else onOpen() },
                onLongClick = { if (selected) onToggle() else onLongPress() },
            )
            .background(if (selected) scheme.primary.copy(alpha = .1f) else Color.Transparent)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Для картинок и видео — превью с хоста, как в браузерном списке.
        val isMedia = !file.isDirectory && (badge.first == "IMG" || badge.first == "VID")
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(badge.second.copy(alpha = .14f))
                .border(1.dp, badge.second.copy(alpha = .28f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (isMedia) {
                RemoteThumb(
                    client = client,
                    path = client.fileThumbPath(file.path),
                    maxSize = 128,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholderColor = Color.Transparent,
                )
                if (badge.first == "VID") {
                    Text("▶", fontSize = 10.sp, color = Color.White)
                }
            } else {
                Text(
                    text = badge.first,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = badge.second,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (file.isDirectory) {
                    androidx.compose.ui.res.stringResource(R.string.folder_label)
                } else {
                    "${FileFormat.size(androidx.compose.ui.platform.LocalResources.current, file.size)} · ${FileFormat.date(file.modified)}"
                },
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) scheme.primary else Color.Transparent)
                    .border(1.dp, if (selected) scheme.primary else scheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Text("✓", fontSize = 12.sp, color = scheme.onPrimary)
                }
            }
        } else {
            TextButton(
                onClick = onRename,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("✎", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.delete_action),
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
    androidx.compose.material3.HorizontalDivider(color = scheme.outlineVariant)
}

/** Диалог переименования файла или папки на хосте. */
@Composable
private fun RemoteRenameDialog(
    currentName: String,
    isDirectory: Boolean,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                androidx.compose.ui.res.stringResource(
                    if (isDirectory) R.string.rename_folder else R.string.rename_file
                )
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.name_label),
                        fontSize = 12.sp,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(trimmed) },
                enabled = trimmed.isNotEmpty() && trimmed != currentName,
            ) { Text(androidx.compose.ui.res.stringResource(R.string.save_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.cancel_button))
            }
        },
    )
}

/** Диалог перемещения выбранных файлов и папок в папку на хосте. */
@Composable
private fun RemoteMoveDialog(
    folders: List<String>,
    count: Int,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.move_title)) },
        text = {
            Column {
                Text(
                    text = if (count == 1) {
                        androidx.compose.ui.res.stringResource(R.string.move_item_single)
                    } else {
                        androidx.compose.ui.res.stringResource(R.string.move_items_plural, count)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    item {
                        RemoteMoveTargetRow(
                            label = androidx.compose.ui.res.stringResource(R.string.move_root),
                            selected = target.isEmpty(),
                            onClick = { target = "" },
                        )
                    }
                    items(folders, key = { it }) { folder ->
                        RemoteMoveTargetRow(
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
                Text(androidx.compose.ui.res.stringResource(R.string.move_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.cancel_button))
            }
        },
    )
}

@Composable
private fun RemoteMoveTargetRow(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun GalleryTab(
    client: RemoteClient,
    galleries: List<RemoteGallery>?,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    onOpen: (List<RemoteMediaItem>, Int) -> Unit,
) {
    if (galleries == null) {
        LoadingBox()
        return
    }

    val items = remember(galleries, selectedSource) {
        if (selectedSource == null) {
            galleries.flatMap { it.items }
        } else {
            galleries.firstOrNull { it.key == selectedSource }?.items.orEmpty()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (galleries.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceChip(
                    androidx.compose.ui.res.stringResource(R.string.remote_source_all),
                    selectedSource == null,
                ) { onSelectSource(null) }
                for (source in galleries) {
                    // У известных источников подпись из ресурсов: язык выбирает
                    // зритель, а не телефон-хост. Незнакомый источник — как прислали.
                    val label = when (source.key) {
                        "received" -> androidx.compose.ui.res.stringResource(R.string.media_source_received)
                        AccessModeFilter.DEVICE_SOURCE_KEY ->
                            androidx.compose.ui.res.stringResource(R.string.media_source_device)
                        else -> source.title
                    }
                    SourceChip(label, selectedSource == source.key) { onSelectSource(source.key) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (items.isEmpty()) {
            EmptyCard(
                title = androidx.compose.ui.res.stringResource(R.string.remote_gallery_empty),
                hint = androidx.compose.ui.res.stringResource(R.string.remote_gallery_hint),
            )
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(items, key = { it.url.ifEmpty { it.id } }) { item ->
                val index = items.indexOf(item)
                GalleryCard(
                    client = client,
                    item = item,
                    onClick = { onOpen(items, index) },
                )
            }
        }
    }
}

@Composable
private fun SourceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) scheme.primary else scheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary else scheme.outline.copy(alpha = .6f),
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun GalleryCard(client: RemoteClient, item: RemoteMediaItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceVariant)
        ) {
            RemoteThumb(
                client = client,
                path = item.thumb,
                maxSize = 320,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (item.kind == "video") {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC06080C)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶", fontSize = 11.sp, color = Color.White)
                }
            }
        }
        Text(
            text = item.name,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun RemoteViewer(
    client: RemoteClient,
    state: ViewerState,
    onClose: () -> Unit,
    onExternal: (RemoteMediaItem) -> Unit,
) {
    if (state.items.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = state.index.coerceIn(0, state.items.lastIndex)
    ) { state.items.size }
    val scope = rememberCoroutineScope()
    val current = state.items.getOrNull(pagerState.currentPage) ?: state.items.first()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current

    // Сохранение идёт в системные «Загрузки»: на Android 10+ через MediaStore
    // без разрешений, на старых версиях — после запроса WRITE_EXTERNAL_STORAGE.
    var savingId by remember { mutableStateOf<String?>(null) }
    var saveProgress by remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    var pendingSave by remember { mutableStateOf<RemoteMediaItem?>(null) }

    fun save(item: RemoteMediaItem) {
        if (savingId != null) return
        savingId = item.id
        saveProgress = -1f
        scope.launch {
            val saved = HostDownloader.save(
                context = context,
                url = client.browserUrl(item.url),
                name = item.name,
            ) { fraction -> saveProgress = fraction }
            savingId = null
            Toast.makeText(
                context,
                if (saved != null) {
                    resources.getString(R.string.remote_saved_downloads)
                } else {
                    resources.getString(R.string.remote_save_error)
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val item = pendingSave
        pendingSave = null
        if (granted && item != null) {
            save(item)
        } else if (item != null) {
            Toast.makeText(
                context,
                resources.getString(R.string.remote_storage_permission),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun requestSave(item: RemoteMediaItem) {
        if (HostDownloader.hasPermission(context)) {
            save(item)
        } else {
            pendingSave = item
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = state.items[page]
                if (item.kind == "image") {
                    RemoteThumb(
                        client = client,
                        path = item.url,
                        maxSize = 2048,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 70.dp, bottom = 24.dp),
                        contentScale = ContentScale.Fit,
                        placeholderColor = Color(0xFF0B0D12),
                    )
                } else if (item.kind == "video") {
                    RemoteVideoPlayer(
                        url = client.browserUrl(item.url),
                        active = pagerState.currentPage == page,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp, bottom = 24.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "▶",
                            fontSize = 34.sp,
                            color = Color.White.copy(alpha = .85f),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = item.name,
                            fontSize = 13.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { onExternal(item) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF0A0C11),
                            ),
                        ) {
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.remote_open_in_browser),
                                fontSize = 12.5.sp,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .border(1.dp, Color.White.copy(alpha = .2f), CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", fontSize = 20.sp, color = Color.White)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current.name,
                        fontSize = 12.5.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} / ${state.items.size}",
                        fontSize = 10.5.sp,
                        color = Color.White.copy(alpha = .6f),
                    )
                }
                if (savingId == null) {
                    TextButton(
                        onClick = { requestSave(current) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.save_button),
                            fontSize = 11.5.sp,
                            color = Color.White,
                        )
                    }
                } else {
                    // Пока файл едет, кнопку заменяет прогресс.
                    Text(
                        text = if (saveProgress < 0f) {
                            androidx.compose.ui.res.stringResource(R.string.remote_saving)
                        } else {
                            androidx.compose.ui.res.stringResource(
                                R.string.remote_saving_percent,
                                (saveProgress * 100).toInt(),
                            )
                        },
                        fontSize = 11.5.sp,
                        color = Color.White.copy(alpha = .8f),
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                if (current.kind != "image") {
                    TextButton(onClick = { onExternal(current) }) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.remote_in_browser),
                            fontSize = 11.5.sp,
                            color = Color.White,
                        )
                    }
                }
            }

            if (pagerState.currentPage > 0) {
                ViewerArrow("‹", Modifier.align(Alignment.CenterStart)) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }
            }
            if (pagerState.currentPage < state.items.lastIndex) {
                ViewerArrow("›", Modifier.align(Alignment.CenterEnd)) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            }
        }
    }
}

// PlayerView.setShowBuffering помечен в Media3 как UnstableApi — используем осознанно.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun RemoteVideoPlayer(
    url: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Свёрнутое приложение не должно продолжать озвучивать видео.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Соседняя страница пейджера остаётся в памяти — глушим её звук.
    LaunchedEffect(active) {
        if (!active) player.pause()
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view -> view.player = player },
        modifier = modifier,
    )
}

@Composable
private fun ViewerArrow(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(horizontal = 10.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0x88000000))
            .border(1.dp, Color.White.copy(alpha = .2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 24.sp, color = Color.White)
    }
}

/** Картинка с хоста: грузится в фоне, кешируется между кадрами сетки и просмотром. */
@Composable
private fun RemoteThumb(
    client: RemoteClient,
    path: String,
    maxSize: Int,
    modifier: Modifier,
    contentScale: ContentScale,
    placeholderColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path, maxSize, client) {
        value = withContext(Dispatchers.IO) { client.image(path, maxSize) }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = modifier.background(placeholderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "▤",
                fontSize = 18.sp,
                color = Color.White.copy(alpha = .28f),
            )
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.error.copy(alpha = .08f),
        border = BorderStroke(1.dp, scheme.error.copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.remote_no_connection),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.error,
            )
            Text(
                text = message,
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.retry), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, hint: String) {
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
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun fileBadge(name: String): Pair<String, Color> {
    val kind = FileKind.ofFileName(name)
    return kind.badge to kind.tint
}


/** Как часто обновлять удалённый список без действий пользователя. */
private const val AUTO_REFRESH_MS = 4_000L

