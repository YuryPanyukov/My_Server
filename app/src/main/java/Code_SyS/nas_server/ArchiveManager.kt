package Code_SyS.nas_server

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Очередь выгрузки файлов в долгое хранение (WebDAV).
 *
 * Одна фоновая нить на процесс: выгрузки идут по одной, чтобы не забивать
 * Wi-Fi и не путаться в статусах. Состояние пишется в [VaultStore], поэтому
 * после перезапуска приложения недоставленные файлы остаются как QUEUED
 * или ERROR и могут быть повторены (файлы из приёма; файлы из «Поделиться»
 * живут только в памяти — их URI вне процесса недоступны).
 *
 * Выгрузка не зависит от включённого сервера: файлы можно складывать в
 * хранилище без приёма из сети. Экраны читают состояние опросом — так же,
 * как статус сервиса.
 */
object ArchiveManager {

    private const val TAG = "ArchiveManager"
    private const val PREFS = "landrop_vault"
    private const val KEY_URL = "webdav_url"
    private const val KEY_USER = "webdav_user"
    private const val KEY_PASSWORD = "webdav_password"

    /** Задача воркера: запись + живой источник для файлов из «Поделиться». */
    private class Job(val record: VaultRecord, val shared: SharedFile? = null)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "landrop-vault").apply { isDaemon = true }
    }
    private val workerBusy = AtomicBoolean(false)
    private val lock = Any()

    private var loaded = false
    private var records: List<VaultRecord> = emptyList()
    private val jobs = ArrayDeque<Job>()

    /** Прогресс активной выгрузки: имя файла и отправленные байты. */
    @Volatile
    private var activeName: String? = null

    @Volatile
    private var activeProgress: Long = 0L

    // --- Конфигурация хранилища ------------------------------------------

    fun config(context: Context): WebDavConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WebDavConfig(
            baseUrl = prefs.getString(KEY_URL, "").orEmpty(),
            username = prefs.getString(KEY_USER, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        )
    }

    fun saveConfig(context: Context, config: WebDavConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, config.baseUrl.trim())
            .putString(KEY_USER, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = config(context).isComplete()

    // --- Состояние ---------------------------------------------------------

    fun records(context: Context): List<VaultRecord> {
        synchronized(lock) {
            load(context)
            return records
        }
    }

    fun activeUpload(): Pair<String?, Long> = activeName to activeProgress

    // --- Автоархивация ------------------------------------------------------

    /**
     * Ставит в очередь файлы старше порога автоархивации с удалением после
     * выгрузки. Файлы, о которых уже есть запись в хранилище (любой статус),
     * не повторяются.
     *
     * Вызвается из опроса главного экрана с троттлингом по [AutoArchiveSettings];
     * [force] нужен при открытии экрана «Хранилище», чтобы настройка применилась
     * сразу после включения.
     */
    fun runAutoArchive(context: Context, files: List<File>, force: Boolean = false) {
        val config = AutoArchiveSettings.load(context)
        if (!config.enabled) return
        val now = System.currentTimeMillis()
        if (!force && !AutoArchiveSettings.scanDue(context, now)) return
        AutoArchiveSettings.markScan(context, now)

        val archivedNames = records(context).map { it.name }.toSet()
        AutoArchiveFilter.select(files, config, now, archivedNames).forEach { file ->
            queueFile(context, file, deleteAfter = true, auto = true)
            Log.i(TAG, "Автоархивация поставила в очередь ${file.name}")
        }
    }

    // --- Постановка в очередь ---------------------------------------------

    /** Файл из папки приёма; после успеха можно удалить его с телефона. */
    fun queueFile(context: Context, file: File, deleteAfter: Boolean, auto: Boolean = false) {
        val record = VaultRecord(
            name = file.name,
            size = file.length(),
            archivedAt = System.currentTimeMillis(),
            uploadedAt = 0L,
            status = VaultRecord.Status.QUEUED,
            lastError = "",
            sourcePath = file.absolutePath,
            deleteAfter = deleteAfter,
            auto = auto,
        )
        synchronized(lock) {
            load(context)
            // Дубликат в очереди не создаём: обновим правило удаления.
            records = if (records.any { it.name == record.name && !it.isFinished }) {
                records.map {
                    if (it.name == record.name) {
                        it.copy(deleteAfter = deleteAfter, auto = auto)
                    } else {
                        it
                    }
                }
            } else {
                (records + record).takeLast(VaultStore.MAX)
            }
            jobs.add(Job(record))
            persist(context)
        }
        kick(context)
    }

    /** Файл из системного «Поделиться»: читается по content:// в этом процессе. */
    fun queueShared(context: Context, shared: SharedFile) {
        val record = VaultRecord(
            name = shared.name,
            size = shared.size,
            archivedAt = System.currentTimeMillis(),
            uploadedAt = 0L,
            status = VaultRecord.Status.QUEUED,
            lastError = "",
            sourcePath = "",
            deleteAfter = false,
        )
        synchronized(lock) {
            load(context)
            records = (records + record).takeLast(VaultStore.MAX)
            jobs.add(Job(record, shared))
            persist(context)
        }
        kick(context)
    }

    /** Повтор неудавшейся выгрузки файла из приёма. */
    fun retry(context: Context, name: String) {
        synchronized(lock) {
            load(context)
            val record = records.firstOrNull { it.name == name } ?: return
            if (record.status != VaultRecord.Status.ERROR) return
            if (record.sourcePath.isEmpty() || !File(record.sourcePath).isFile) return
            records = records.map {
                if (it.name == name) it.copy(status = VaultRecord.Status.QUEUED, lastError = "") else it
            }
            jobs.add(Job(record.copy(status = VaultRecord.Status.QUEUED, lastError = "")))
            persist(context)
        }
        kick(context)
    }

    /** Убирает запись из истории; файл на телефоне и копия на сервере не трогаются. */
    fun dismiss(context: Context, name: String) {
        synchronized(lock) {
            load(context)
            records = records.filterNot { it.name == name }
            persist(context)
        }
    }

    /** Убирает успешно выгруженные записи из истории. */
    fun clearUploaded(context: Context) {
        synchronized(lock) {
            load(context)
            records = records.filterNot { it.status == VaultRecord.Status.DONE }
            persist(context)
        }
    }

    /**
     * Загрузка состояния и старт воркера: вызывается, когда открыт любой из
     * экранов, чтобы записи QUEUED из прошлого запуска доехали до сервера.
     */
    fun resume(context: Context) {
        synchronized(lock) { load(context) }
        kick(context)
    }

    // --- Воркер ------------------------------------------------------------

    private fun load(context: Context) {
        if (loaded) return
        records = VaultStore.load(context)
        loaded = true
        // Records из прошлого запуска без живых источников: файлы из приёма
        // читаются с диска, «Поделиться» — только если запись свежая.
        for (record in records) {
            if (record.status == VaultRecord.Status.QUEUED) {
                if (record.sourcePath.isNotEmpty()) {
                    jobs.add(Job(record))
                } else {
                    setStatusLocked(record.copy(status = VaultRecord.Status.ERROR, lastError = ""))
                }
            }
        }
        records = records.map {
            if (it.status == VaultRecord.Status.QUEUED && it.sourcePath.isEmpty()) {
                it.copy(status = VaultRecord.Status.ERROR, lastError = "Источник недоступен после перезапуска")
            } else {
                it
            }
        }
    }

    private fun setStatusLocked(record: VaultRecord) {
        records = records.map { if (it.name == record.name) record else it }
    }

    private fun kick(context: Context) {
        if (!workerBusy.compareAndSet(false, true)) return
        executor.execute {
            try {
                workerLoop(context.applicationContext)
            } finally {
                workerBusy.set(false)
            }
        }
    }

    private fun workerLoop(context: Context) {
        while (true) {
            val job: Job? = synchronized(lock) { jobs.removeFirstOrNull() }
            if (job == null) return
            val record = job.record

            val webDavConfig = config(context)
            if (!webDavConfig.isComplete()) {
                fail(context, record, "Хранилище не настроено: укажите адрес в разделе «Хранилище»")
                continue
            }

            val opener = openSource(context, job) ?: run {
                fail(context, record, "Файл недоступен: источник пропал")
                continue
            }

            activeName = record.name
            activeProgress = 0L
            val result = WebDavClient(webDavConfig).upload(
                fileName = record.name,
                size = record.size,
                openStream = opener,
                progress = { sent -> activeProgress = sent },
            )
            activeName = null
            activeProgress = 0L

            result.fold(
                onSuccess = { complete(context, record) },
                onFailure = { error -> fail(context, record, VaultErrors.describe(error)) },
            )
        }
    }

    /** Поток для выгрузки: файл из приёма или живой URI из «Поделиться». */
    private fun openSource(context: Context, job: Job): (() -> InputStream?)? {
        job.shared?.let { shared -> return { shared.openStream(context) } }
        val path = job.record.sourcePath
        if (path.isEmpty()) return null
        val file = File(path)
        if (!file.isFile) return null
        return { try { file.inputStream() } catch (_: Exception) { null } }
    }

    private fun complete(context: Context, record: VaultRecord) {
        synchronized(lock) {
            setStatusLocked(
                record.copy(
                    status = VaultRecord.Status.DONE,
                    uploadedAt = System.currentTimeMillis(),
                    lastError = "",
                )
            )
            persist(context)
        }
        // «Выгрузить и удалить» работает только для файлов из приёма.
        if (record.deleteAfter && record.sourcePath.isNotEmpty()) {
            val deleted = try {
                File(record.sourcePath).delete()
            } catch (_: Exception) {
                false
            }
            if (!deleted) {
                Log.w(TAG, "Не удалось удалить ${record.name} с телефона после выгрузки")
            }
        }
    }

    private fun fail(context: Context, record: VaultRecord, message: String) {
        Log.w(TAG, "Выгрузка ${record.name}: $message")
        synchronized(lock) {
            setStatusLocked(record.copy(status = VaultRecord.Status.ERROR, lastError = message))
            persist(context)
        }
    }

    private fun persist(context: Context) {
        VaultStore.save(context, records)
    }
}
