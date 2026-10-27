package Code_SyS.nas_server

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * Режим частичного доступа: что видно подключённым клиентам, пока сервер включён.
 *
 * Режим относится и к принятой папке, и к галерее телефона: все маршруты
 * сервера (`/`, `/api/files`, `/files/…`, `/gallery`, `/api/media`, `/media`,
 * `/thumb`) работают от одних и тех же списков, поэтому фильтр применяется
 * к источникам, а не к отдельным маршрутам.
 */
sealed interface AccessMode {

    /** Всё видно, как раньше. */
    data object All : AccessMode

    /** Только галерея телефона: принятые файлы скрыты, выгрузка работает. */
    data object GalleryOnly : AccessMode

    /** Только отмеченные в приложении принятые файлы; галерея телефона скрыта. */
    data class Selected(val names: Set<String>) : AccessMode

    /** Принятые файлы и элементы галереи за один день. */
    sealed interface Period : AccessMode {
        data object Today : Period
        data object Yesterday : Period
        data class OnDate(val epochDay: Long) : Period
    }
}

/**
 * Текущий режим доступа, читаемый сервером из любого потока.
 *
 * Приложение и сервис живут в одном процессе, поэтому обычный volatile
 * объект безопасен: UI пишет, серверные потоки читают на каждый запрос.
 */
internal object AccessModeState {
    @Volatile
    var current: AccessMode = AccessMode.All
}

/**
 * Чистые функции фильтрации без Android API — проверяются JVM-тестами.
 */
internal object AccessModeFilter {

    /** Ключ источника принятых файлов; совпадает с [ReceivedMediaSource.key]. */
    const val RECEIVED_SOURCE_KEY = "received"

    /** Ключ источника медиатеки телефона; совпадает с `DeviceMediaSource.key`. */
    const val DEVICE_SOURCE_KEY = "device"

    /**
     * Принятые файлы, доступные сети в режиме [mode].
     * [nowMillis] — время запроса: границы «сегодня» и «вчера» считаются от него.
     */
    fun applyFiles(files: List<File>, mode: AccessMode, nowMillis: Long): List<File> {
        if (files.isEmpty()) return files
        return when (mode) {
            is AccessMode.All -> files

            // Принятые файлы скрыты целиком: страница загружает, но не отдаёт.
            is AccessMode.GalleryOnly -> emptyList()

            is AccessMode.Selected -> files.filter { it.name in mode.names }

            is AccessMode.Period -> files.filter { it.lastModified() in bounds(mode, nowMillis) }
        }
    }

    /**
     * Источники галереи, доступные сети в режиме [mode]. Каждый источник
     * оборачивается фильтром элементов, чтобы `/media`, `/thumb` и список
     * элементов показывали одно и то же подмножество.
     */
    fun applySources(sources: List<MediaSource>, mode: AccessMode, nowMillis: Long): List<MediaSource> {
        if (sources.isEmpty()) return sources
        return when (mode) {
            is AccessMode.All -> sources

            is AccessMode.GalleryOnly -> sources.filter { it.key == DEVICE_SOURCE_KEY }

            is AccessMode.Selected -> sources
                .filter { it.key == RECEIVED_SOURCE_KEY }
                .map { AccessFilteredSource(it, allowed = { it.name in mode.names }) }

            is AccessMode.Period -> {
                val range = bounds(mode, nowMillis)
                sources.map { source ->
                    AccessFilteredSource(source, allowed = { it.dateMillis in range })
                }
            }
        }
    }

    /** Границы дня (включительно) для периодного режима по местному времени. */
    fun bounds(mode: AccessMode.Period, nowMillis: Long): LongRange {
        val calendar = Calendar.getInstance()
        when (mode) {
            is AccessMode.Period.Today -> calendar.timeInMillis = nowMillis
            is AccessMode.Period.Yesterday -> {
                calendar.timeInMillis = nowMillis
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }
            is AccessMode.Period.OnDate -> {
                // epochDay отсчитывается от UTC. День календаря берём в UTC,
                // а ноль времени выставляем уже в местной зоне: иначе при
                // отрицательных смещениях «день X» превращался бы в X−1.
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                utc.clear()
                utc.timeInMillis = mode.epochDay * MILLIS_PER_DAY
                calendar.set(
                    utc.get(Calendar.YEAR),
                    utc.get(Calendar.MONTH),
                    utc.get(Calendar.DAY_OF_MONTH),
                    0,
                    0,
                    0,
                )
            }
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        return start..(start + MILLIS_PER_DAY - 1)
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}

/**
 * Источник-обёртка: скрывает элементы, не разрешённые текущим режимом.
 * Открываются только отфильтрованные элементы, поэтому ссылки на скрытые
 * файлы отвечают 404.
 */
internal class AccessFilteredSource(
    private val origin: MediaSource,
    private val allowed: (MediaItem) -> Boolean,
) : MediaSource {

    override val key: String = origin.key
    override val title: String = origin.title

    override fun items(): List<MediaItem> =
        try {
            origin.items().filter(allowed)
        } catch (_: Exception) {
            emptyList()
        }

    override fun open(item: MediaItem): InputStream? = origin.open(item)

    override fun thumbnail(item: MediaItem): ByteArray? = origin.thumbnail(item)

    /** Скрытый режимом доступа элемент удалить нельзя — только видимые. */
    override fun delete(item: MediaItem): MediaDeleteResult =
        if (allowed(item)) origin.delete(item) else MediaDeleteResult.NOT_FOUND
}

/**
 * Кодирование режима в строку для SharedPreferences — без Android API.
 */
internal object AccessModeCodec {

    fun encode(mode: AccessMode): String = when (mode) {
        is AccessMode.All -> "all"
        is AccessMode.GalleryOnly -> "gallery"
        is AccessMode.Period.Today -> "today"
        is AccessMode.Period.Yesterday -> "yesterday"
        is AccessMode.Period.OnDate -> "date:${mode.epochDay}"
        is AccessMode.Selected ->
            if (mode.names.isEmpty()) {
                "all"
            } else {
                mode.names.sorted().joinToString(NL_SEPARATOR) { it.replace("\n", " ") }
                    .let { "selected:$it" }
            }
    }

    fun decode(raw: String?): AccessMode {
        if (raw.isNullOrBlank()) return AccessMode.All
        return when {
            raw == "all" -> AccessMode.All
            raw == "gallery" -> AccessMode.GalleryOnly
            raw == "today" -> AccessMode.Period.Today
            raw == "yesterday" -> AccessMode.Period.Yesterday
            raw.startsWith("date:") -> raw.removePrefix("date:").toLongOrNull()
                ?.let { AccessMode.Period.OnDate(it) }
                ?: AccessMode.All
            raw.startsWith("selected:") -> {
                val names = raw.removePrefix("selected:")
                    .split(NL_SEPARATOR)
                    .filter { it.isNotBlank() }
                    .toSet()
                if (names.isEmpty()) AccessMode.All else AccessMode.Selected(names)
            }
            else -> AccessMode.All
        }
    }

    private const val NL_SEPARATOR = "\n"
}

/**
 * Хранение режима доступа: `SharedPreferences` + [AccessModeState] для
 * серверных потоков. Отдельный объект, чтобы и экран, и сервис меняли
 * состояние одним способом.
 */
object AccessModeStore {

    private const val PREFS = "landrop_access"
    private const val KEY = "mode"

    /** Читает сохранённый режим и выставляет его серверным потокам. */
    fun load(context: Context): AccessMode {
        val mode = AccessModeCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )
        AccessModeState.current = mode
        return mode
    }

    fun set(context: Context, mode: AccessMode) {
        AccessModeState.current = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, AccessModeCodec.encode(mode))
            .apply()
    }
}
