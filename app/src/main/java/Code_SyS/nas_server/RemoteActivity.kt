package Code_SyS.nas_server

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale

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
            Toast.makeText(this, "Ссылка не содержит адрес LanDrop", Toast.LENGTH_SHORT).show()
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

private data class UploadTask(
    val id: Long,
    val name: String,
    val size: Long,
    val sent: Long = 0,
    val done: Boolean = false,
    val error: String? = null,
)

@Composable
private fun RemoteScreen(target: ShareTarget, onClose: () -> Unit) {
    val context = LocalContext.current
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
    var uploads by remember { mutableStateOf<List<UploadTask>>(emptyList()) }
    var fileToDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    var joined by remember { mutableStateOf(false) }

    // Ручное и автоматическое обновление не должны идти одновременно.
    val refreshLock = remember { Mutex() }

    suspend fun refresh(showSpinner: Boolean) {
        if (!refreshLock.tryLock()) return
        if (showSpinner) refreshing = true
        try {
            val (loadedFiles, loadedGalleries) = withContext(Dispatchers.IO) {
                client.files() to client.galleries()
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
            loadError = e.message ?: "Сервер недоступен"
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

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult

        scope.launch {
            for (uri in uris) {
                val shared = withContext(Dispatchers.IO) { SharedFile.read(context, uri) }
                if (shared == null || shared.size < 0) {
                    Toast.makeText(
                        context,
                        "Не удалось определить размер файла",
                        Toast.LENGTH_SHORT,
                    ).show()
                    continue
                }

                val id = System.nanoTime()
                uploads = uploads + UploadTask(id = id, name = shared.name, size = shared.size)
                try {
                    withContext(Dispatchers.IO) {
                        client.upload(
                            name = shared.name,
                            size = shared.size,
                            openStream = { shared.openStream(context) },
                        ) { sent ->
                            uploads = uploads.map { task ->
                                if (task.id == id) task.copy(sent = sent) else task
                            }
                        }
                    }
                    uploads = uploads.map { task ->
                        if (task.id == id) {
                            task.copy(sent = shared.size, done = true)
                        } else {
                            task
                        }
                    }
                } catch (e: Exception) {
                    uploads = uploads.map { task ->
                        if (task.id == id) {
                            task.copy(error = e.message ?: "Не удалось отправить")
                        } else {
                            task
                        }
                    }
                }
            }

            refresh(showSpinner = false)
            delay(3_000)
            uploads = uploads.filter { it.error != null }
        }
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Удалить файл на телефоне?") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    fileToDelete = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.deleteFile(file.name) }
                            refresh(showSpinner = false)
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                e.message ?: "Не удалось удалить файл",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Отмена") }
            },
        )
    }

    viewer?.let { state ->
        RemoteViewer(
            client = client,
            state = state,
            onClose = { viewer = null },
            onExternal = { item -> openExternal(context, client.browserUrl(item.url)) },
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
                    text = "Просмотр по Wi-Fi",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${target.host}:${target.port} · удалённый телефон",
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
                    query = query,
                    onQueryChange = { query = it },
                    uploads = uploads,
                    onOpen = { file -> openExternal(context, client.browserUrl(client.filePath(file.name))) },
                    onDelete = { fileToDelete = it },
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
                onClick = { openExternal(context, client.browserUrl()) },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = .7f)),
                modifier = Modifier.weight(1f),
            ) {
                Text("Браузер", fontSize = 13.sp)
            }
            Button(
                onClick = {
                    try {
                        picker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "Нет выбора файлов", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
                modifier = Modifier.weight(1.4f),
            ) {
                Text("Отправить файлы", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            text = "Файлы" + if (filesCount > 0) " · $filesCount" else "",
            selected = tab == RemoteTab.FILES,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(RemoteTab.FILES) },
        )
        TabPill(
            text = "Галерея",
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
    query: String,
    onQueryChange: (String) -> Unit,
    uploads: List<UploadTask>,
    onOpen: (RemoteFile) -> Unit,
    onDelete: (RemoteFile) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val visible = remember(files, query) {
        val needle = query.trim()
        val source = files.orEmpty()
        if (needle.isEmpty()) source else source.filter { it.name.contains(needle, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        for (upload in uploads) {
            UploadRow(upload)
            Spacer(modifier = Modifier.height(6.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Поиск по файлам", fontSize = 14.sp) },
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
                            title = if (files.isEmpty()) "На телефоне пока нет файлов" else "Ничего не найдено",
                            hint = if (files.isEmpty()) {
                                "Отправьте файл кнопкой ниже — он появится в этом списке"
                            } else {
                                "Измените запрос в поиске"
                            },
                        )
                    }
                }
                items(visible, key = { it.name }) { file ->
                    RemoteFileRow(
                        client = client,
                        file = file,
                        onOpen = { onOpen(file) },
                        onDelete = { onDelete(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadRow(upload: UploadTask) {
    val scheme = MaterialTheme.colorScheme
    val failed = upload.error != null
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (failed) scheme.error.copy(alpha = .1f) else scheme.surface,
        border = BorderStroke(1.dp, if (failed) scheme.error.copy(alpha = .4f) else scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = upload.name,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        failed -> "ошибка"
                        upload.done -> "готово"
                        else -> "${(upload.sent * 100 / upload.size.coerceAtLeast(1))}%"
                    },
                    fontSize = 11.sp,
                    color = when {
                        failed -> scheme.error
                        upload.done -> Color(0xFF3DDC97)
                        else -> scheme.onSurfaceVariant
                    },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = {
                    if (failed) 1f else (upload.sent.toFloat() / upload.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = if (failed) scheme.error else scheme.primary,
                trackColor = scheme.outlineVariant,
            )
            if (failed) {
                Text(
                    text = upload.error.orEmpty(),
                    fontSize = 10.5.sp,
                    color = scheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoteFileRow(
    client: RemoteClient,
    file: RemoteFile,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val badge = fileBadge(file.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Для картинок и видео — превью с хоста, как в браузерном списке.
        val isMedia = badge.first == "IMG" || badge.first == "VID"
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
                    path = client.fileThumbPath(file.name),
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
                text = "${FileFormat.size(file.size)} · ${FileFormat.date(file.modified)}",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) {
            Text("Удалить", fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
        }
    }
    androidx.compose.material3.HorizontalDivider(color = scheme.outlineVariant)
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
                SourceChip("Все", selectedSource == null) { onSelectSource(null) }
                for (source in galleries) {
                    SourceChip(source.title, selectedSource == source.key) { onSelectSource(source.key) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (items.isEmpty()) {
            EmptyCard(
                title = "Галерея пуста",
                hint = "Разрешите на том телефоне доступ к фото и видео или добавьте файлы",
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
                            Text("Открыть в браузере", fontSize = 12.5.sp)
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
                if (current.kind != "image") {
                    TextButton(onClick = { onExternal(current) }) {
                        Text("В браузере", fontSize = 11.5.sp, color = Color.White)
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
                text = "Нет связи с телефоном",
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
                Text("Повторить", fontSize = 12.sp)
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
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg", "avif" ->
            "IMG" to Color(0xFFF5A623)

        "mp4", "mkv", "mov", "avi", "webm", "m4v" ->
            "VID" to Color(0xFFB98CFF)

        "mp3", "wav", "flac", "aac", "ogg", "m4a" ->
            "AUD" to Color(0xFF38D6C4)

        "pdf", "doc", "docx", "txt", "md", "rtf", "xls", "xlsx", "ppt", "pptx" ->
            "DOC" to Color(0xFF6C9CFF)

        "zip", "rar", "7z", "tar", "gz" ->
            "ZIP" to Color(0xFFFF8A8A)

        else -> "FILE" to Color(0xFF98A2B3)
    }
}


/** Как часто обновлять удалённый список без действий пользователя. */
private const val AUTO_REFRESH_MS = 4_000L

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "Нет приложения для ссылки", Toast.LENGTH_SHORT).show()
    }
}
