package Code_SyS.nas_server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Очередь отправки без Android API и без сети: транспорт подменяется фейком,
 * поэтому прогресс, отмена и повтор проверяются как обычная логика.
 */
class UploadQueueTest {

    private val target = ShareTarget("192.168.1.5", 8080)

    private fun files(vararg sizes: Long): List<UploadFile> =
        sizes.mapIndexed { index, size ->
            UploadFile("file$index.bin", size) { ByteArrayInputStream(ByteArray(0)) }
        }

    @Test
    fun `sends files in order and reports the shared progress`() = runBlocking {
        val sentNames = mutableListOf<String>()
        val queue = UploadQueueState(
            scope = this,
            transport = { _, task, onSent, _ ->
                sentNames += task.name
                onSent(task.size / 2)
                onSent(task.size)
            },
            clock = { 0L },
        )

        queue.enqueue(target, files(100, 200)).join()

        assertEquals(listOf("file0.bin", "file1.bin"), sentNames)
        assertEquals(2, queue.summary.total)
        assertEquals(2, queue.summary.finished)
        assertEquals(100, queue.summary.percent)
        assertEquals(300, queue.summary.sent)
        assertEquals(300, queue.summary.bytes)
        assertEquals(2, queue.summary.delivered)
        assertEquals(0, queue.summary.failed)
        assertTrue(queue.tasks.all { it.done })
        assertEquals(2, queue.completed?.total)
    }

    @Test
    fun `skips files with unknown size without calling the transport`() = runBlocking {
        val sentNames = mutableListOf<String>()
        val queue = UploadQueueState(
            scope = this,
            transport = { _, task, _, _ -> sentNames += task.name },
            clock = { 0L },
        )

        queue.enqueue(target, files(-1, 10)).join()

        assertEquals(listOf("file1.bin"), sentNames)
        assertEquals(SKIPPED_MESSAGE, queue.tasks.first().error)
        assertTrue(queue.tasks.first().skipped)
        assertEquals(1, queue.summary.skipped)
        assertEquals(2, queue.summary.finished)
    }

    @Test
    fun `cancel marks one running file and continues with the rest`() = runBlocking {
        val started = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val queue = UploadQueueState(
            scope = this,
            transport = { _, task, onSent, isCancelled ->
                started.complete(task.id)
                release.await()
                if (isCancelled()) throw IOException(CANCELLED_MESSAGE)
                onSent(task.size)
            },
            clock = { 0L },
        )

        val job = queue.enqueue(target, files(10, 10))
        val first = withTimeout(5_000) { started.await() }
        queue.cancel(first)
        release.complete(Unit)
        job.join()

        assertEquals(CANCELLED_MESSAGE, queue.task(first)?.error)
        assertTrue("второй файл дошёл", queue.tasks.last().done)
    }

    @Test
    fun `cancel all stops the queue and keeps every task cancelled`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queue = UploadQueueState(
            scope = this,
            transport = { _, _, _, _ ->
                started.complete(Unit)
                release.await()
            },
            clock = { 0L },
        )

        val job = queue.enqueue(target, files(10, 10, 10))
        withTimeout(5_000) { started.await() }
        queue.cancelAll()
        release.complete(Unit)
        job.join()

        assertTrue(queue.tasks.all { it.cancelled })
        assertEquals(3, queue.summary.finished)
    }

    @Test
    fun `retry repeats only failed files`() = runBlocking {
        var networkDown = true
        val attempts = mutableListOf<String>()
        val queue = UploadQueueState(
            scope = this,
            transport = { _, task, onSent, _ ->
                attempts += task.name
                if (networkDown && task.name == "file1.bin") throw IOException("Сеть недоступна")
                onSent(task.size)
            },
            clock = { 0L },
        )

        queue.enqueue(target, files(10, 10)).join()

        assertEquals("Сеть недоступна", queue.task(1)?.error)
        assertTrue(queue.task(0)?.done == true)

        networkDown = false
        queue.retryFailed()?.join()

        assertEquals(listOf("file0.bin", "file1.bin", "file1.bin"), attempts)
        assertTrue(queue.tasks.all { it.done })
    }

    @Test
    fun `summary counts failures and skips but not cancelled files`() {
        val summary = summarize(
            listOf(
                UploadTask(1, "a", 100, sent = 100, done = true),
                UploadTask(2, "b", 100, error = "Сеть"),
                UploadTask(3, "c", 100, error = CANCELLED_MESSAGE),
                UploadTask(4, "d", -1, error = SKIPPED_MESSAGE),
            )
        )

        assertEquals(4, summary.total)
        assertEquals(4, summary.finished)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.delivered)
        assertEquals(1, summary.cancelled)
        assertEquals(100, summary.sent)
        assertEquals(200, summary.bytes)
        assertEquals(50, summary.percent)
    }

    @Test
    fun `speed slides instead of averaging from the start`() {
        val meter = SpeedMeter(windowMs = 1_000)

        assertEquals(0, meter.sample(0, 0))
        assertEquals(1_000, meter.sample(1_000, 1_000))
        assertEquals(1_000, meter.sample(2_000, 2_000))
        // За секунду не пришло ничего нового — скорость падает до нуля.
        assertEquals(0, meter.sample(2_000, 3_000))
    }
}
