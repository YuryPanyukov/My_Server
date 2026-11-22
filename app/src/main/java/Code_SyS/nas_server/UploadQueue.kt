package Code_SyS.nas_server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Файл, который можно отправить на телефон-хост. */
internal class UploadFile(
    val name: String,
    val size: Long,
    val openStream: () -> InputStream?,
)

/**
 * Одна отправка в очереди.
 *
 * [size] меньше нуля — размер не удалось узнать: такой файл сервер принять не
 * может (`Content-Length`), поэтому задача сразу помечается [SKIPPED_MESSAGE].
 */
internal data class UploadTask(
    val id: Long,
    val name: String,
    val size: Long,
    val path: String = "",
    val sent: Long = 0,
    val done: Boolean = false,
    val error: String? = null,
    val speed: Long = 0,
    val openStream: () -> InputStream? = { null },
) {
    val skipped: Boolean get() = size < 0
    val cancelled: Boolean get() = error == CANCELLED_MESSAGE
    val finished: Boolean get() = done || error != null
    val percent: Int get() = if (size <= 0) 0 else (sent * 100 / size).toInt().coerceIn(0, 100)
}

/** Общий прогресс: «3 из 10 · 42% · 1,2 МБ/с». */
internal data class UploadSummary(
    val finished: Int = 0,
    val total: Int = 0,
    val sent: Long = 0,
    val bytes: Long = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val delivered: Int = 0,
    val cancelled: Int = 0,
    val speed: Long = 0,
) {
    val running: Boolean get() = total > 0 && finished < total
    val percent: Int
        get() = when {
            bytes > 0 -> (sent * 100 / bytes).toInt().coerceIn(0, 100)
            total > 0 && finished == total -> 100
            else -> 0
        }
}

internal const val CANCELLED_MESSAGE = "Отменено"
internal const val SKIPPED_MESSAGE = "Пропущен: неизвестный размер"

/** Складывает задачи в один прогресс. Чистая функция — проверяется JVM-тестом. */
internal fun summarize(tasks: List<UploadTask>): UploadSummary {
    var finished = 0
    var sent = 0L
    var bytes = 0L
    var failed = 0
    var skipped = 0
    var delivered = 0
    var cancelledCount = 0
    var speed = 0L

    for (task in tasks) {
        if (task.finished) finished++
        if (task.skipped) skipped++
        if (task.done) delivered++
        if (task.cancelled) cancelledCount++
        if (task.error != null && !task.skipped && !task.cancelled) failed++
        speed += task.speed
        if (!task.skipped && !task.cancelled) {
            sent += task.sent.coerceIn(0, task.size.coerceAtLeast(0))
            bytes += task.size.coerceAtLeast(0)
        }
    }

    return UploadSummary(
        finished = finished,
        total = tasks.size,
        sent = sent,
        bytes = bytes,
        failed = failed,
        skipped = skipped,
        delivered = delivered,
        cancelled = cancelledCount,
        speed = speed,
    )
}

/**
 * Скорость по скользящему окну: средняя с начала файла врёт на медленном
 * старте и не показывает обрыв. Хранит только последние ~2 секунды.
 */
internal class SpeedMeter(private val windowMs: Long = 2_000) {

    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Long>()

    fun sample(sent: Long, now: Long): Long {
        times.addLast(now)
        totals.addLast(sent)
        while (times.size > 2 && now - times.first() > windowMs) {
            times.removeFirst()
            totals.removeFirst()
        }
        if (times.size < 2) return 0

        val elapsed = now - times.first()
        val delta = sent - totals.first()
        if (elapsed <= 0 || delta <= 0) return 0
        return delta * 1000 / elapsed
    }
}

/** Доставка одного файла; в тестах подменяется фейком. */
internal fun interface UploadTransport {
    suspend fun send(
        target: ShareTarget,
        task: UploadTask,
        onSent: (Long) -> Unit,
        isCancelled: () -> Boolean,
    )
}

/** Настоящая отправка: `PUT /upload` на телефон-хост. */
internal object RemoteUploadTransport : UploadTransport {
    override suspend fun send(
        target: ShareTarget,
        task: UploadTask,
        onSent: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        RemoteClient(target.host, target.port).upload(
            name = task.name,
            size = task.size,
            openStream = task.openStream,
            onProgress = onSent,
            path = task.path,
            isCancelled = isCancelled,
        )
    }
}

/**
 * Очередь отправки на телефон: одна задача за другой, общий прогресс,
 * отмена файла и всей пачки, повтор упавших.
 *
 * Состояние не знает про Android и Compose-разметку; экран только рисует
 * [tasks]. Поэтому прогресс, отмена и повтор проверяются JVM-тестами.
 */
internal class UploadQueueState(
    private val scope: CoroutineScope,
    private val transport: UploadTransport = RemoteUploadTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Текст ошибки без сообщения от сервера; экран подставляет локализованный. */
    private val failedMessage: String = "",
) {
    var tasks: List<UploadTask> by mutableStateOf(emptyList())
        private set
    var target: ShareTarget? by mutableStateOf(null)
        private set
    var open: Boolean by mutableStateOf(false)
        private set

    /** Итог последней завершённой пачки; экран показывает тост по нему. */
    var completed: UploadSummary? by mutableStateOf(null)
        private set

    // Прогресс приходит из IO-потока, отмена — с главного, поэтому общий
    // список задач меняется под одним замком, а набор отмен — потокобезопасный.
    private val lock = Any()
    private val cancelled: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var nextId = 0L
    private var job: Job? = null

    val summary: UploadSummary get() = summarize(tasks)

    fun task(id: Long): UploadTask? = tasks.firstOrNull { it.id == id }

    /** Ставит пачку в очередь. Задачи идут по одной: так прогресс честный. */
    fun enqueue(target: ShareTarget, files: List<UploadFile>, path: String = ""): Job {
        this.target = target
        open = true
        completed = null
        cancelled.clear()
        tasks = files.map { file ->
            UploadTask(
                id = nextId++,
                name = file.name,
                size = file.size,
                path = path,
                error = if (file.size < 0) SKIPPED_MESSAGE else null,
                openStream = file.openStream,
            )
        }
        return start()
    }

    /** Отменяет один файл; уже завершённые задачи не трогаем. */
    fun cancel(id: Long) {
        cancelled += id
        update(id) { task ->
            if (task.finished) task else task.copy(error = CANCELLED_MESSAGE, speed = 0)
        }
    }

    /** Отменяет всё: ждущие помечаются сразу, текущий файл обрывается в цикле копирования. */
    fun cancelAll() {
        synchronized(lock) {
            cancelled += tasks.map { it.id }
            tasks = tasks.map { task ->
                if (task.finished) task else task.copy(error = CANCELLED_MESSAGE, speed = 0)
            }
        }
        job?.cancel()
    }

    /** Повторяет только настоящие ошибки: пропущенные и отменённые не трогаем. */
    fun retryFailed(): Job? {
        // Пока пачка идёт, повтор перезапустил бы и текущий файл с нуля.
        if (summary.running) return null
        if (tasks.none { it.retryable }) return null
        cancelled.clear()
        synchronized(lock) {
            tasks = tasks.map { task ->
                if (task.retryable) task.copy(error = null, sent = 0, done = false, speed = 0) else task
            }
        }
        return start()
    }

    /** Закрывает очередь и забывает задачи. */
    fun dismiss() {
        job?.cancel()
        job = null
        open = false
        tasks = emptyList()
        target = null
        completed = null
        cancelled.clear()
    }

    private fun start(): Job {
        job?.cancel()
        val host = target ?: return Job().apply { cancel() }
        val order = tasks.map { it.id }
        job = scope.launch {
            for (id in order) {
                val queued = task(id) ?: continue
                if (queued.finished) continue

                val meter = SpeedMeter()
                update(id) { it.copy(error = null, sent = 0, done = false, speed = 0) }

                try {
                    transport.send(
                        target = host,
                        task = queued,
                        onSent = { sent ->
                            val speed = meter.sample(sent, clock())
                            update(id) { task ->
                                if (task.done || task.error != null) task
                                else task.copy(sent = sent, speed = speed)
                            }
                        },
                        isCancelled = { id in cancelled },
                    )
                    update(id) { task -> task.copy(sent = task.size, done = true, speed = 0) }
                } catch (e: CancellationException) {
                    update(id) { task -> task.copy(error = CANCELLED_MESSAGE, speed = 0) }
                    throw e
                } catch (e: Exception) {
                    val message = e.message?.takeIf { it.isNotBlank() } ?: failedMessage
                    update(id) { task ->
                        if (task.cancelled) task else task.copy(error = message, speed = 0)
                    }
                }
            }

            if (tasks.isNotEmpty() && tasks.all { it.finished }) {
                completed = summarize(tasks)
            }
        }
        return job ?: Job()
    }

    private fun update(id: Long, change: (UploadTask) -> UploadTask) {
        synchronized(lock) {
            tasks = tasks.map { task -> if (task.id == id) change(task) else task }
        }
    }

    private val UploadTask.retryable: Boolean
        get() = error != null && !skipped && !cancelled && !done
}

/** Создаёт очередь, привязанную к экрану. */
@Composable
internal fun rememberUploadQueue(): UploadQueueState {
    val scope = rememberCoroutineScope()
    val failedMessage = androidx.compose.ui.res.stringResource(R.string.upload_failed)
    return remember { UploadQueueState(scope, failedMessage = failedMessage) }
}

/** Строка общего прогресса: «3 из 10 · 42% · 1,2 МБ/с». */
@Composable
internal fun uploadProgressLabel(summary: UploadSummary): String {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val base = resources.getString(
        R.string.upload_progress,
        summary.finished,
        summary.total,
        summary.percent,
    )
    return if (summary.speed > 0) {
        base + resources.getString(R.string.upload_progress_speed, FileFormat.size(resources, summary.speed))
    } else {
        base
    }
}

/** Строка файла в очереди: имя, процент или ошибка, отмена. */
@Composable
internal fun UploadRow(task: UploadTask, onCancel: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val failed = task.error != null
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (failed) scheme.error.copy(alpha = .1f) else scheme.surface,
        border = BorderStroke(
            1.dp,
            if (failed) scheme.error.copy(alpha = .4f) else scheme.outline.copy(alpha = .55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.name,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.stateLabel(context),
                    fontSize = 11.sp,
                    color = when {
                        failed -> scheme.error
                        task.done -> Color(0xFF3DDC97)
                        else -> scheme.onSurfaceVariant
                    },
                )
                if (onCancel != null && !task.finished) {
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text("×", fontSize = 15.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }
            if (!task.finished && !task.skipped) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { task.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = scheme.primary,
                    trackColor = scheme.outline.copy(alpha = .4f),
                )
            }
        }
    }
}

private fun UploadTask.stateLabel(context: android.content.Context): String = when {
    skipped -> context.getString(R.string.upload_status_queued)
    cancelled -> context.getString(R.string.upload_status_cancelled)
    error != null -> context.getString(R.string.upload_status_error)
    done -> context.getString(R.string.upload_status_done)
    speed > 0 -> context.getString(R.string.upload_speed, percent, FileFormat.size(context.resources, speed))
    else -> "${percent}%"
}

/**
 * Диалог очереди: общий прогресс, строки файлов, отмена и повтор.
 * Не закрывается, пока идёт отправка: иначе прогресс некуда вернуть.
 */
@Composable
internal fun UploadQueueDialog(queue: UploadQueueState, onClose: () -> Unit) {
    if (!queue.open) return
    val summary = queue.summary
    val scheme = MaterialTheme.colorScheme
    val target = queue.target

    AlertDialog(
        onDismissRequest = { if (!summary.running) onClose() },
        title = {
            Text(
                text = if (summary.running) {
                    androidx.compose.ui.res.stringResource(R.string.upload_sending)
                } else {
                    androidx.compose.ui.res.stringResource(R.string.upload_done)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                if (target != null) {
                    Text(
                        text = "${target.host}:${target.port}",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uploadProgressLabel(summary),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (task in queue.tasks) {
                        UploadRow(
                            task = task,
                            onCancel = { queue.cancel(task.id) },
                        )
                    }
                }
                if (!summary.running && summary.failed > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { queue.retryFailed() },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(
                                R.string.upload_retry_failed,
                                summary.failed,
                            ),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!summary.running) {
                TextButton(onClick = onClose) {
                    Text(androidx.compose.ui.res.stringResource(R.string.upload_close))
                }
            }
        },
        dismissButton = {
            if (summary.running) {
                TextButton(onClick = { queue.cancelAll() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.upload_cancel_all))
                }
            }
        },
    )
}
