package Code_SyS.nas_server

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Code_SyS.nas_server.ui.theme.LanDropTheme
import kotlinx.coroutines.isActive

/**
 * Экран «Хранилище»: WebDAV-папка в локальной сети для долгого хранения.
 *
 * Тут настраивается адрес хранилища, видна очередь выгрузок с статусами и
 * сюда же приходят файлы из системного «Поделиться» — минуя папку приёма.
 */
class VaultActivity : ComponentActivity() {

    private var pendingShare by mutableStateOf<List<SharedFile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            pendingShare = SharedFile.fromIntent(this, intent)
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
                    VaultScreen(
                        onBack = { finish() },
                        sharedFiles = pendingShare,
                        onSharedHandled = {
                            pendingShare = emptyList()
                            setIntent(Intent(this@VaultActivity, VaultActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val files = SharedFile.fromIntent(this, intent)
        if (files.isNotEmpty()) {
            pendingShare = files
        }
    }
}

@Composable
fun VaultScreen(
    onBack: () -> Unit,
    sharedFiles: List<SharedFile> = emptyList(),
    onSharedHandled: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var records by remember { mutableStateOf<List<VaultRecord>>(emptyList()) }
    var activeUpload by remember { mutableStateOf<Pair<String?, Long>>(null to 0L) }
    var urlText by remember { mutableStateOf(ArchiveManager.config(context).baseUrl) }
    var userText by remember { mutableStateOf(ArchiveManager.config(context).username) }
    var passwordText by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var autoConfig by remember { mutableStateOf(AutoArchiveSettings.load(context)) }

    LaunchedEffect(Unit) {
        ArchiveManager.resume(context)
        // Включение автоархивации должно сработать сразу, без ожидания троттлинга.
        ArchiveManager.runAutoArchive(
            context,
            FileSaver(context).listFiles(),
            force = true,
        )
    }

    // Статусы и прогресс обновляются опросом — как статус сервиса на главном экране.
    LaunchedEffect(Unit) {
        while (isActive) {
            records = ArchiveManager.records(context)
            activeUpload = ArchiveManager.activeUpload()
            kotlinx.coroutines.delay(1000)
        }
    }

    if (sharedFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onSharedHandled,
            title = { Text("Отправить в хранилище?") },
            text = {
                Text(
                    if (sharedFiles.size == 1) {
                        sharedFiles[0].name
                    } else {
                        "Файлов: ${sharedFiles.size}"
                    } + "\nФайлы выгрузятся на сервер, когда он доступен."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sharedFiles.forEach { ArchiveManager.queueShared(context, it) }
                    Toast.makeText(context, "В очереди: ${sharedFiles.size}", Toast.LENGTH_SHORT).show()
                    onSharedHandled()
                }) { Text("Отправить") }
            },
            dismissButton = {
                TextButton(onClick = onSharedHandled) { Text("Отмена") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                    Text("← Назад", fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(4.dp))

            VaultConfigCard(
                urlText = urlText,
                userText = userText,
                password = passwordText,
                isComplete = ArchiveManager.isConfigured(context),
                checking = checking,
                checkResult = checkResult,
                onUrl = { urlText = it },
                onUser = { userText = it },
                onPassword = { passwordText = it; checkResult = null },
                onTest = {
                    if (urlText.isBlank()) {
                        checkResult = "Сначала укажите адрес папки"
                    } else {
                        checking = true
                        checkResult = null
                        // Сеть нельзя трогать в потоке UI.
                        Thread {
                            val config = WebDavConfig(urlText, userText, passwordText.ifBlank {
                                ArchiveManager.config(context).password
                            })
                            val result = WebDavClient(config).testConnection()
                            checking = false
                            checkResult = result.fold(
                                onSuccess = { "Сервер отвечает" },
                                onFailure = { VaultErrors.describe(it) },
                            )
                        }.start()
                    }
                },
                onSave = {
                    val config = WebDavConfig(
                        baseUrl = urlText,
                        username = userText,
                        password = passwordText.takeIf { it.isNotBlank() }
                            ?: ArchiveManager.config(context).password,
                    )
                    if (!config.isComplete()) {
                        Toast.makeText(context, "Адрес должен начинаться с http://", Toast.LENGTH_SHORT).show()
                    } else {
                        ArchiveManager.saveConfig(context, config)
                        Toast.makeText(context, "Хранилище сохранено", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            AutoArchiveCard(
                config = autoConfig,
                storageReady = ArchiveManager.isConfigured(context),
                onConfigChange = { config ->
                    autoConfig = config
                    AutoArchiveSettings.save(context, config)
                    if (config.enabled) {
                        ArchiveManager.runAutoArchive(
                            context,
                            FileSaver(context).listFiles(),
                            force = true,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Выгрузки",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (records.any { it.status == VaultRecord.Status.DONE }) {
                    TextButton(onClick = { ArchiveManager.clearUploaded(context) }) {
                        Text("Очистить", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (records.isEmpty()) {
            item {
                VaultEmpty(
                    title = "Пока ничего не выгружалось",
                    hint = "Нажмите «В хранилище» у файла на главном экране или отправьте файл через «Поделиться» → LanDrop",
                )
            }
        } else {
            items(records, key = { "${it.name}|${it.archivedAt}" }) { record ->
                VaultRecordRow(
                    record = record,
                    progress = if (activeUpload.first == record.name) activeUpload.second else 0L,
                    onRetry = { ArchiveManager.retry(context, record.name) },
                    onDismiss = { ArchiveManager.dismiss(context, record.name) },
                )
            }
        }
    }
}

@Composable
private fun VaultConfigCard(
    urlText: String,
    userText: String,
    password: String,
    isComplete: Boolean,
    checking: Boolean,
    checkResult: String?,
    onUrl: (String) -> Unit,
    onUser: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
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
                text = "ДОЛГОЕ ХРАНЕНИЕ",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = "WebDAV-папка на вашем компьютере, NAS или роутере. Файлы копируются туда и остаются, даже если телефон и все его копии пропадут.",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = urlText,
                onValueChange = onUrl,
                label = { Text("Адрес папки", fontSize = 12.sp) },
                placeholder = { Text("http://192.168.1.10:5005/landrop", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.surface,
                    unfocusedContainerColor = scheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = userText,
                onValueChange = onUser,
                label = { Text("Логин", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.surface,
                    unfocusedContainerColor = scheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                label = {
                    Text(
                        "Пароль" + if (isComplete) " (введён)" else "",
                        fontSize = 12.sp,
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.surface,
                    unfocusedContainerColor = scheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    onClick = onTest,
                    enabled = !checking,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (checking) "Проверяем…" else "Проверить",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onSave,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = .6f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Сохранить", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (checkResult != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (checkResult == "Сервер отвечает") Color(0xFF3DDC97)
                                else Color(0xFFFF8A8A)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(checkResult, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Настройка автоархивации: по умолчанию выключена; возраст выбирается чипами. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AutoArchiveCard(
    config: AutoArchiveConfig,
    storageReady: Boolean,
    onConfigChange: (AutoArchiveConfig) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Автоархивация",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (!config.enabled) "Выключена" else "Старше ${config.maxAgeDays} дн.: выгрузить и удалить с телефона",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = scheme.onPrimary,
                        checkedTrackColor = scheme.primary,
                        uncheckedThumbColor = scheme.onSurfaceVariant,
                        uncheckedTrackColor = scheme.surfaceVariant,
                        uncheckedBorderColor = scheme.outline,
                    ),
                )
            }

            if (config.enabled) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AutoAgeChip(config.maxAgeDays, 1, onConfigChange)
                    AutoAgeChip(config.maxAgeDays, 3, onConfigChange)
                    AutoAgeChip(config.maxAgeDays, 7, onConfigChange)
                    AutoAgeChip(config.maxAgeDays, 30, onConfigChange)
                }
            }
            Text(
                text = if (storageReady) {
                    "Работает при запущенном приложении: старые файлы попадут в очередь"
                } else {
                    "Сначала настройте хранилище выше"
                },
                fontSize = 11.sp,
                color = if (storageReady) scheme.onSurfaceVariant else Color(0xFFD96A6A),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AutoAgeChip(current: Int, choice: Int, onConfigChange: (AutoArchiveConfig) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val selected = current == choice
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) scheme.primary.copy(alpha = .12f) else scheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary else scheme.outline.copy(alpha = .6f),
        ),
    ) {
        Text(
            text = "$choice дн.",
            fontSize = 12.sp,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clickable(enabled = !selected) { onConfigChange(AutoArchiveConfig(enabled = true, maxAgeDays = choice)) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun vaultStatusText(record: VaultRecord): String = when (record.status) {
    VaultRecord.Status.QUEUED -> "В очереди"
    VaultRecord.Status.UPLOADING -> "Выгружается…"
    VaultRecord.Status.DONE -> "На сервере"
    VaultRecord.Status.ERROR -> record.lastError
}

@Composable
private fun VaultRecordRow(
    record: VaultRecord,
    progress: Long,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = " · ${FileFormat.size(record.size)} · ${FileFormat.date(record.archivedAt)}"
                Text(
                    text = vaultStatusText(record),
                    fontSize = 11.5.sp,
                    color = when (record.status) {
                        VaultRecord.Status.DONE -> Color(0xFF2FA377)
                        VaultRecord.Status.ERROR -> Color(0xFFD96A6A)
                        else -> scheme.onSurfaceVariant
                    },
                )
                Text(
                    text = meta.removePrefix(" · "),
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (record.status == VaultRecord.Status.ERROR && record.sourcePath.isNotEmpty()) {
                TextButton(onClick = onRetry) {
                    Text("Повтор", fontSize = 11.5.sp)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("×", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
        }
        if (record.status == VaultRecord.Status.UPLOADING && progress > 0 && record.size > 0) {
            LinearProgressIndicator(
                progress = {
                    (progress.toFloat() / record.size.toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

@Composable
private fun VaultEmpty(title: String, hint: String) {
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
