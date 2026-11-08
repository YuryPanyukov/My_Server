package Code_SyS.nas_server

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    val context = LocalContext.current
    val resources = LocalResources.current

    var records by remember { mutableStateOf<List<VaultRecord>>(emptyList()) }
    var activeUpload by remember { mutableStateOf<Pair<String?, Long>>(null to 0L) }
    var urlText by remember { mutableStateOf(ArchiveManager.config(context).baseUrl) }
    var userText by remember { mutableStateOf(ArchiveManager.config(context).username) }
    var passwordText by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checkOk by remember { mutableStateOf(false) }
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
            title = { Text(stringResource(R.string.archive_dialog_title)) },
            text = {
                val hint = stringResource(R.string.archive_shared_hint)
                Text(
                    if (sharedFiles.size == 1) {
                        sharedFiles[0].name
                    } else {
                        stringResource(R.string.archive_files_count, sharedFiles.size)
                    } + "\n\n" + hint
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sharedFiles.forEach { ArchiveManager.queueShared(context, it) }
                    Toast.makeText(
                        context,
                        resources.getString(R.string.vault_queue_toast_shared, sharedFiles.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onSharedHandled()
                }) { Text(stringResource(R.string.send_button)) }
            },
            dismissButton = {
                TextButton(onClick = onSharedHandled) { Text(stringResource(R.string.cancel_button)) }
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
                    Text(stringResource(R.string.back_button), fontSize = 13.sp)
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
                checkOk = checkOk,
                onUrl = { urlText = it },
                onUser = { userText = it },
                onPassword = { passwordText = it; checkResult = null; checkOk = false },
                onTest = {
                    if (urlText.isBlank()) {
                        checkResult = resources.getString(R.string.check_needs_address)
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
                                onSuccess = {
                                    checkOk = true
                                    resources.getString(R.string.check_success)
                                },
                                onFailure = {
                                    checkOk = false
                                    VaultErrors.describe(context, it)
                                },
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
                        Toast.makeText(
                            context,
                            resources.getString(R.string.address_must_http),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        ArchiveManager.saveConfig(context, config)
                        Toast.makeText(
                            context,
                            resources.getString(R.string.vault_saved),
                            Toast.LENGTH_SHORT,
                        ).show()
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
                    text = stringResource(R.string.vault_uploads_header),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (records.any { it.status == VaultRecord.Status.DONE }) {
                    TextButton(onClick = { ArchiveManager.clearUploaded(context) }) {
                        Text(
                            stringResource(R.string.vault_clear),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (records.isEmpty()) {
            item {
                VaultEmpty(
                    title = stringResource(R.string.vault_empty_title),
                    hint = stringResource(R.string.vault_empty_hint),
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
    checkOk: Boolean,
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
                text = stringResource(R.string.vault_section),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.vault_desc_long),
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = urlText,
                onValueChange = onUrl,
                label = { Text(stringResource(R.string.folder_address), fontSize = 12.sp) },
                placeholder = { Text(stringResource(R.string.folder_address_example), fontSize = 13.sp) },
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
                label = { Text(stringResource(R.string.login_label), fontSize = 12.sp) },
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
                        stringResource(R.string.password_label) +
                            if (isComplete) stringResource(R.string.password_entered) else "",
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
                        if (checking) stringResource(R.string.checking) else stringResource(R.string.check_button),
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
                    Text(stringResource(R.string.save_button_vault), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                                if (checkOk) Color(0xFF3DDC97)
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
                        text = stringResource(R.string.auto_archive),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (!config.enabled) {
                            stringResource(R.string.auto_archive_disabled)
                        } else {
                            pluralStringResource(
                                R.plurals.auto_archive_enabled,
                                config.maxAgeDays,
                                config.maxAgeDays,
                            )
                        },
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
                    stringResource(R.string.vault_working)
                } else {
                    stringResource(R.string.auto_archive_desc_off)
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
            text = pluralStringResource(R.plurals.auto_age_days, choice, choice),
            fontSize = 12.sp,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clickable(enabled = !selected) { onConfigChange(AutoArchiveConfig(enabled = true, maxAgeDays = choice)) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun vaultStatusText(record: VaultRecord, context: Context): String = when (record.status) {
    VaultRecord.Status.QUEUED -> context.getString(R.string.vault_status_queued)
    VaultRecord.Status.UPLOADING -> context.getString(R.string.vault_status_uploading)
    VaultRecord.Status.DONE -> context.getString(R.string.vault_status_done)
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
    val context = LocalContext.current
    val resources = LocalResources.current
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
                val meta = " · ${FileFormat.size(resources, record.size)} · ${FileFormat.date(record.archivedAt)}"
                Text(
                    text = vaultStatusText(record, context),
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
                    Text(stringResource(R.string.retry), fontSize = 11.5.sp)
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
