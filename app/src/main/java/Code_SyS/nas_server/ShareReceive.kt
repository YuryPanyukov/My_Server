package Code_SyS.nas_server

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Диалог после «Поделиться»: файлы можно положить в свою папку LanDrop
 * или отправить на недавний телефон одной очередью с прогрессом.
 */
@Composable
internal fun SharedFilesDialog(
    files: List<SharedFile>,
    saver: FileSaver,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val hosts = remember { RecentHosts.list(context) }
    val queue = rememberUploadQueue()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHosts by remember { mutableStateOf(false) }

    fun saveLocally() {
        if (busy) return
        busy = true
        error = null
        status = resources.getString(R.string.saving_files)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                files.mapNotNull { it.saveTo(context, saver) }
            }
            if (GallerySaver.isEnabled(context)) {
                // Копия в Галерею нужна только для фото и видео.
                withContext(Dispatchers.IO) {
                    saved.forEach { file -> GallerySaver.publish(context, file) }
                }
            }
            busy = false
            if (saved.isEmpty()) {
                error = resources.getString(R.string.save_files_error)
                status = null
                return@launch
            }
            Toast.makeText(
                context,
                if (saved.size == 1) {
                    resources.getString(R.string.saved_single, saved.first().name)
                } else {
                    resources.getQuantityString(R.plurals.saved_plural, saved.size, saved.size)
                },
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    }

    fun sendTo(target: ShareTarget) {
        showHosts = false
        error = null
        queue.enqueue(
            target = target,
            files = files.map { file ->
                UploadFile(file.name, file.size) { file.openStream(context) }
            },
        )
    }

    // Отправка закончилась: запоминаем телефон, говорим результат и закрываемся.
    LaunchedEffect(queue.completed) {
        val done = queue.completed ?: return@LaunchedEffect
        val target = queue.target ?: return@LaunchedEffect
        val sent = done.delivered
        if (sent > 0) {
            RecentHosts.remember(context, target)
            Toast.makeText(
                context,
                if (sent == 1) {
                    resources.getString(R.string.send_single, target.host)
                } else {
                    resources.getQuantityString(R.plurals.send_plural, sent, sent)
                },
                Toast.LENGTH_SHORT,
            ).show()
            queue.dismiss()
            onDismiss()
        } else {
            error = resources.getString(R.string.send_error_unknown_size)
            queue.dismiss()
        }
    }

    val sending = queue.open

    AlertDialog(
        onDismissRequest = { if (!busy && !queue.summary.running) onDismiss() },
        title = {
            Text(
                text = if (files.size == 1) {
                    stringResource(R.string.send_file)
                } else {
                    stringResource(R.string.send_files)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    text = if (files.size == 1) {
                        files.first().name
                    } else {
                        pluralStringResource(R.plurals.files_count, files.size, files.size) +
                            " · " + files.first().name + " " +
                            pluralStringResource(R.plurals.and_more, files.size - 1, files.size - 1)
                    },
                    fontSize = 12.5.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                when {
                    sending -> {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = uploadProgressLabel(queue.summary),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            for (task in queue.tasks) {
                                UploadRow(task = task, onCancel = { queue.cancel(task.id) })
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                        if (!queue.summary.running && queue.summary.failed > 0) {
                            TextButton(
                                onClick = { queue.retryFailed() },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.upload_retry_failed, queue.summary.failed),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    busy -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = scheme.primary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = status ?: stringResource(R.string.saving),
                                fontSize = 12.sp,
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    else -> {
                        Spacer(modifier = Modifier.height(14.dp))
                        ShareOption(
                            title = stringResource(R.string.share_save_title),
                            subtitle = stringResource(R.string.share_save_desc),
                            onClick = ::saveLocally,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        ShareOption(
                            title = stringResource(R.string.send_to_phone),
                            subtitle = if (hosts.isEmpty()) {
                                stringResource(R.string.send_to_phone_desc)
                            } else {
                                stringResource(R.string.send_wifi)
                            },
                            onClick = { if (hosts.isNotEmpty()) showHosts = true },
                        )
                    }
                }

                error?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = message, fontSize = 11.5.sp, color = scheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy && !queue.summary.running,
            ) {
                Text(
                    if (busy || queue.summary.running) {
                        stringResource(R.string.sending)
                    } else {
                        stringResource(R.string.cancel_button)
                    }
                )
            }
        },
    )

    if (showHosts) {
        HostPickerDialog(
            title = stringResource(R.string.send_to_phone_title),
            hosts = hosts,
            onPick = ::sendTo,
            onDismiss = { showHosts = false },
        )
    }
}
