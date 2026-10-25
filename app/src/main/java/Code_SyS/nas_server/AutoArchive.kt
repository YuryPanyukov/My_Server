package Code_SyS.nas_server

import android.content.Context
import java.io.File

/**
 * Автоархивация: принятые файлы старше заданного возраста выгружаются в
 * долгое хранение и удаляются с телефона.
 *
 * По умолчанию отключена; включается на экране «Хранилище». Работает только
 * при запущенном приложении — фонового процесса у LanDrop нет, серверные
 * выгрузки и так идут одной очередью [ArchiveManager].
 */

/** Настройка автоархивации; [maxAgeDays] — сколько дней держать файл на телефоне. */
data class AutoArchiveConfig(
    val enabled: Boolean = false,
    val maxAgeDays: Int = 7,
) {
    companion object {
        /** Варианты возраста для выбора в настройках. */
        val AGE_CHOICES = intArrayOf(1, 3, 7, 30)
    }
}

/**
 * Чистый отбор кандидатов без Android API — проверяется JVM-тестами:
 * старше порога, ещё в папке и без записи в очереди хранилища.
 */
internal object AutoArchiveFilter {

    /** Файлы, которые пора выгрузить и удалить. */
    fun select(
        files: List<File>,
        config: AutoArchiveConfig,
        nowMillis: Long,
        archivedNames: Set<String>,
    ): List<File> {
        if (!config.enabled) return emptyList()
        val cutoff = nowMillis - config.maxAgeDays * MILLIS_PER_DAY
        return files
            .filter { it.isFile && it.lastModified() < cutoff && it.name !in archivedNames }
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}

/** Хранение настройки в `SharedPreferences`. */
object AutoArchiveSettings {

    private const val PREFS = "landrop_auto"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DAYS = "max_age_days"
    private const val KEY_LAST_SCAN = "last_scan"

    private const val SCAN_INTERVAL_MS = 15L * 60 * 1000

    fun load(context: Context): AutoArchiveConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AutoArchiveConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            maxAgeDays = prefs.getInt(KEY_DAYS, AutoArchiveConfig().maxAgeDays),
        )
    }

    fun save(context: Context, config: AutoArchiveConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_DAYS, config.maxAgeDays)
            .apply()
    }

    /** Прошла ли холодная отсечка с прошлого скана: скан не должен тикать в секунду. */
    fun scanDue(context: Context, nowMillis: Long): Boolean =
        nowMillis - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SCAN, 0L) >= SCAN_INTERVAL_MS

    fun markScan(context: Context, nowMillis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SCAN, nowMillis)
            .apply()
    }
}
