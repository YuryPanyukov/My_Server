package Code_SyS.nas_server

import android.content.Context
import androidx.core.content.edit

/**
 * Запись в хранилище (WebDAV): файл, поставленный в очередь выгрузки.
 *
 * Записи нужны, чтобы после перезапуска приложения было видно, какие файлы
 * уже ушли в долгое хранение, а какие ждут сети или упали с ошибкой.
 */
data class VaultRecord(
    val name: String,
    val size: Long,
    /** Когда файл поставлен в очередь. */
    val archivedAt: Long,
    /** Когда файл успешно выгружен; `0` — ещё в очереди/с ошибкой. */
    val uploadedAt: Long,
    val status: Status,
    /** Текст последней ошибки; пусто, если ошибок не было. */
    val lastError: String,
    /** Откуда читать файл. Пусто для файлов из «Поделиться» — их читает opener. */
    val sourcePath: String,
    /** Удалить исходный файл с телефона после успешной выгрузки. */
    val deleteAfter: Boolean,
    /** Запись поставлена автоархивацией, а не вручную. */
    val auto: Boolean = false,
) {
    enum class Status { QUEUED, UPLOADING, DONE, ERROR }

    val isFinished: Boolean
        get() = status == Status.DONE || status == Status.ERROR
}

/**
 * Список записей хранилища в `SharedPreferences` — не секреты, а состояние
 * очереди. Кодирование без Android API проверяется JVM-тестами.
 */
object VaultStore {

    private const val PREFS = "landrop_vault"
    private const val KEY = "records"

    /** Сколько записей помнить: очередь короткая, история — тоже. */
    const val MAX = 100

    fun load(context: Context): List<VaultRecord> =
        VaultCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    fun save(context: Context, records: List<VaultRecord>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, VaultCodec.encode(records.takeLast(MAX))) }
    }
}

/**
 * Табличное кодирование: поля через таб, записи через перевод строки,
 * внутри поля спецсимволы экранируются обратным слэшем.
 */
internal object VaultCodec {

    fun encode(records: List<VaultRecord>): String =
        records.joinToString("\n") { record ->
            listOf(
                record.name,
                record.size.toString(),
                record.archivedAt.toString(),
                record.uploadedAt.toString(),
                record.status.name,
                record.lastError,
                record.sourcePath,
                if (record.deleteAfter) "1" else "0",
                if (record.auto) "1" else "0",
            ).joinToString("\t") { escape(it) }
        }

    fun decode(raw: String?): List<VaultRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = unmarshal(line)
                if (fields.size < 8) return@mapNotNull null
                val status = fields[4].let { parsed ->
                    VaultRecord.Status.entries.firstOrNull { it.name == parsed }
                } ?: return@mapNotNull null
                VaultRecord(
                    name = fields[0],
                    size = fields[1].toLongOrNull() ?: 0L,
                    archivedAt = fields[2].toLongOrNull() ?: 0L,
                    uploadedAt = fields[3].toLongOrNull() ?: 0L,
                    status = status,
                    lastError = fields[5],
                    sourcePath = fields[6],
                    deleteAfter = fields[7] == "1",
                    // Поле добавлено позже: у старых записей его нет.
                    auto = fields.getOrNull(8) == "1",
                )
            }
            .toList()
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    /** Разбор строки с учётом экранирования; таб заменён на `\u0001` в полях. */
    private fun unmarshal(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                c == '\\' && index + 1 < line.length -> {
                    when (val next = line[index + 1]) {
                        't' -> field.append('\t')
                        'n' -> field.append('\n')
                        '\\' -> field.append('\\')
                        else -> {
                            field.append(c).append(next)
                        }
                    }
                    index += 2
                }
                c == '\t' -> {
                    fields.add(field.toString())
                    field.clear()
                    index++
                }
                else -> {
                    field.append(c)
                    index++
                }
            }
        }
        fields.add(field.toString())
        return fields
    }
}

/** Вид ошибки выгрузки: подпись для экрана выбирается по нему. */
internal enum class VaultFailure { AUTH, NOT_FOUND, TOO_LARGE, NO_SPACE, NO_CONNECTION, HTTP, UNKNOWN }

/**
 * Разобранная ошибка выгрузки: вид, HTTP-код и техническая деталь.
 * Деталь не переводится — это сообщение сети или сервера.
 */
internal data class VaultError(
    val failure: VaultFailure,
    val code: Int? = null,
    val detail: String? = null,
)

/**
 * Разбор ошибок выгрузки: [classify] — чистая функция (JVM-тесты),
 * подпись собирается из строковых ресурсов в [describe].
 */
internal object VaultErrors {

    fun classify(error: Throwable?): VaultError = when (error) {
        is WebDavHttpException -> when (error.code) {
            401, 403 -> VaultError(VaultFailure.AUTH)
            404, 409, 412, 423 -> VaultError(VaultFailure.NOT_FOUND)
            413 -> VaultError(VaultFailure.TOO_LARGE)
            507 -> VaultError(VaultFailure.NO_SPACE)
            else -> VaultError(VaultFailure.HTTP, code = error.code)
        }
        is java.io.IOException -> VaultError(VaultFailure.NO_CONNECTION, detail = error.message)
        else -> VaultError(VaultFailure.UNKNOWN, detail = error?.message)
    }

    fun describe(context: Context, error: Throwable?): String {
        val parsed = classify(error)
        return when (parsed.failure) {
            VaultFailure.AUTH -> context.getString(R.string.vault_error_auth)
            VaultFailure.NOT_FOUND -> context.getString(R.string.vault_error_not_found)
            VaultFailure.TOO_LARGE -> context.getString(R.string.vault_error_too_large)
            VaultFailure.NO_SPACE -> context.getString(R.string.vault_error_no_space)
            VaultFailure.HTTP -> context.getString(R.string.vault_error_http, parsed.code ?: 0)
            VaultFailure.NO_CONNECTION -> context.getString(
                R.string.vault_error_no_connection,
                parsed.detail?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.vault_error_network_broken),
            )
            VaultFailure.UNKNOWN -> parsed.detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.vault_error_unknown)
        }
    }
}
