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
        status = "Сохраняем файлы…"
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
                error = "Не удалось сохранить файлы"
                status = null
                return@launch
            }
            Toast.makeText(
                context,
                if (saved.size == 1) "Сохранено: ${saved.first().name}" else "Сохранено файлов: ${saved.size}",
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
                if (sent == 1) "Отправлено на ${target.host}" else "Отправлено файлов: $sent",
                Toast.LENGTH_SHORT,
            ).show()
            queue.dismiss()
            onDismiss()
        } else {
            error = "Не удалось отправить: неизвестен размер файлов"
            queue.dismiss()
        }
    }

    val sending = queue.open

    AlertDialog(
        onDismissRequest = { if (!busy && !queue.summary.running) onDismiss() },
        title = {
            Text(
                text = if (files.size == 1) "Отправить файл" else "Отправить файлы",
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
                        "Файлов: ${files.size} · ${files.first().name} и ещё ${files.size - 1}"
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
                            text = queue.summary.label,
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
                                Text("Повторить упавшие (${queue.summary.failed})", fontSize = 12.sp)
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
                                text = status ?: "Сохраняем…",
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
                            title = "В папку LanDrop",
                            subtitle = "Файлы появятся на компьютере в браузере",
                            onClick = ::saveLocally,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        ShareOption(
                            title = "На недавний телефон",
                            subtitle = if (hosts.isEmpty()) {
                                "Появится после первого подключения по QR"
                            } else {
                                "Отправить по Wi-Fi"
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
                Text(if (busy || queue.summary.running) "Отправка…" else "Отмена")
            }
        },
    )

    if (showHosts) {
        HostPickerDialog(
            title = "Отправить на телефон",
            hosts = hosts,
            onPick = ::sendTo,
            onDismiss = { showHosts = false },
        )
    }
}
