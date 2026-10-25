package Code_SyS.nas_server

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * или сразу отправить на недавний телефон.
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

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
        if (busy) return
        busy = true
        error = null
        val client = RemoteClient(target.host, target.port)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    files.forEachIndexed { index, file ->
                        status = "Отправка ${index + 1} из ${files.size}: ${file.name}"
                        if (file.size < 0) {
                            throw IllegalStateException("Не удалось определить размер ${file.name}")
                        }
                        client.upload(
                            name = file.name,
                            size = file.size,
                            openStream = { file.openStream(context) },
                        )
                    }
                }
                RecentHosts.remember(context, target)
                Toast.makeText(
                    context,
                    if (files.size == 1) "Отправлено на ${target.host}" else "Отправлено файлов: ${files.size}",
                    Toast.LENGTH_SHORT,
                ).show()
                busy = false
                onDismiss()
            } catch (e: Exception) {
                busy = false
                status = null
                error = e.message ?: "Не удалось отправить файлы"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
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

                if (busy) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = scheme.primary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = status ?: "Отправляем…",
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(14.dp))
                    ShareOption(
                        title = "В папку LanDrop",
                        subtitle = "Файлы появятся на компьютере в браузере",
                        onClick = ::saveLocally,
                    )

                    if (hosts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "НЕДАВНИЕ ТЕЛЕФОНЫ",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        hosts.forEach { host ->
                            ShareOption(
                                title = "${host.host}:${host.port}",
                                subtitle = "Отправить по Wi-Fi",
                                onClick = { sendTo(host) },
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    } else {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Телефон-получатель появится здесь после первого подключения по QR.",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                error?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = message,
                        fontSize = 11.5.sp,
                        color = scheme.error,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) {
                Text(if (busy) "Отправка…" else "Отмена")
            }
        },
    )
}

@Composable
private fun ShareOption(title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = scheme.surfaceVariant.copy(alpha = .45f),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = .55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(scheme.primary.copy(alpha = .14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", fontSize = 14.sp, color = scheme.primary)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
            }
            Text("›", fontSize = 18.sp, color = scheme.onSurfaceVariant)
        }
    }
}

