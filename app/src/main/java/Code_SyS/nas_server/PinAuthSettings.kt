package Code_SyS.nas_server

import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import android.content.Context

/**
 * Статичный серверный PIN из 4 цифр.
 *
 * Основан на модели угрозы Smudge (Schneier, 2008): на сенсорных устройствах
 * или при совместном использовании PIN часто выводится по следам жирных отпечатков,
 * поэтому «секретность» четырёх цифр — это фильтр от случайных прохожих, а не
 * криптография. Для локальной Wi-Fi сети этого достаточно, чтобы исключить
 * случайные подключения соседей и глючные клиенты.
 *
 * PIN живёт в `SharedPreferences` сервера (Android). Класс написан без Android API:
 * чистая логика проверки и кодирования, поэтому покрывается JVM-тестами.
 */
object PinAuthSettings {

    private const val PREFS = "landrop_pin"
    private const val KEY_ENABLED = "pin_enabled"
    private const val KEY_CODE = "pin_code"
    const val PIN_LENGTH = 4

    private val random = SecureRandom()

    /** Случайный код из [PIN_LENGTH] цифр — для кнопки «Сгенерировать» в диалоге. */
    fun generateCode(): String = buildString(PIN_LENGTH) {
        repeat(PIN_LENGTH) { append(random.nextInt(10)) }
    }

    /** Код, которым помечается PIN-сообщение в бинарном потоке. */
    const val PIN_MAGIC = 0x50 // 'P'

    /** Политика PIN по умолчанию: включён, код ещё не задан (сервер работать не будет). */
    fun defaultConfig(): PinAuthConfig = PinAuthConfig(
        enabled = true,
        code = null,
    )

    /** Чтение настроек из `SharedPreferences`. */
    fun load(context: Context): PinAuthConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, defaultConfig().enabled)
        val code = prefs.getString(KEY_CODE, null)
        return PinAuthConfig(
            enabled = enabled,
            code = code?.takeIf { it.length == PIN_LENGTH },
        )
    }

    /** Сохранение настроек. `code` может быть `null` — тогда PIN выключен. */
    fun save(context: Context, config: PinAuthConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            config.code?.let { putString(KEY_CODE, it) }
                ?: remove(KEY_CODE)
            apply()
        }
    }

    /** Включить или выключить PIN, не меняя код. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val current = load(context)
        save(context, current.copy(enabled = enabled))
    }

    /**
     * Попытка авторизации через `PushbackInputStream`.
     *
     * Если PIN выключен или не задан — `authenticated = true`.
     * Если включён — клиент обязан первым байтом прислать `PIN_MAGIC`,
     * затем 4 байта-цифры. Клиент без метки не авторизуется: иначе защиту
     * обходил бы любой, кто просто её не присылает.
     */
    fun authorizeFromPushback(
        input: PushbackInputStream,
        logger: (String) -> Unit,
    ): PinAuthResult {
        val config = PinAuthCodec.currentConfig()
        if (!config.enabled || config.code == null) {
            return PinAuthResult(authenticated = true, reason = null)
        }

        val firstByte = input.read()
        if (firstByte != PIN_MAGIC) {
            if (firstByte >= 0) input.unread(firstByte)
            logger("PIN-клиент не прислал метку PIN")
            return PinAuthResult(authenticated = false, reason = "клиент не прислал PIN")
        }

        val codeBytes = ByteArray(PIN_LENGTH)
        var filled = 0
        while (filled < PIN_LENGTH) {
            val n = input.read(codeBytes, filled, PIN_LENGTH - filled)
            if (n < 0) return PinAuthResult(authenticated = false, reason = "отмена клиентом")
            filled += n
        }
        val clientCode = String(codeBytes, StandardCharsets.US_ASCII)

        return when (val attempt = PinAuthCodec.verify(clientCode)) {
            is PinAttemptResult.Match -> {
                logger("PIN-клиент прошёл авторизацию")
                PinAuthResult(authenticated = true, reason = null)
            }
            is PinAttemptResult.Wrong -> {
                logger("PIN-клиент отправил неверный PIN (осталось попыток: ${attempt.remaining})")
                PinAuthResult(authenticated = false, reason = "неверный PIN")
            }
            is PinAttemptResult.Blocked -> {
                logger("PIN-клиент заблокирован на ${attempt.retryAfterMs / 1000} с")
                PinAuthResult(authenticated = false, reason = "слишком много попыток")
            }
        }
    }

}

/** Конфигурация PIN: включён ли и какой код. */
data class PinAuthConfig(
    val enabled: Boolean = false,
    val code: String? = null,
) {
    val hasCode: Boolean get() = code != null && code.length == PinAuthSettings.PIN_LENGTH

    /** Защита реально включена: PIN включён и код задан. */
    val protectionActive: Boolean get() = enabled && hasCode

    /** Пути, доступные без PIN: проверка состояния и сама страница входа. */
    fun isPublicPath(path: String): Boolean = when (path) {
        "/health", "/pin", "/pin-check", "/pin-verify" -> true
        else -> false
    }
}

/** Результат попытки авторизации PIN. */
data class PinAuthResult(
    val authenticated: Boolean,
    val reason: String? = null,
)

internal sealed interface PinAttemptResult {
    data object Match : PinAttemptResult
    data class Wrong(val remaining: Int) : PinAttemptResult
    data class Blocked(val retryAfterMs: Long) : PinAttemptResult
}

/**
 * Кодек, который хранит config в статичном объекте, чтобы [PinAuthSettings.authorizeFromPushback]
 * не лез к SharedPreferences на каждый байт. В реальном приложении это обновляется
 * при загрузке сервиса.
 */
internal object PinAuthCodec {
    /** Сколько неверных PIN подряд допускается до временной блокировки. */
    private const val MAX_ATTEMPTS = 5

    /** Длительность блокировки после исчерпания попыток. */
    private const val BLOCK_MS = 60_000L

    @Volatile
    internal var current: PinAuthConfig = PinAuthSettings.defaultConfig()

    private val lock = Any()
    private var failedAttempts = 0
    private var blockedUntil = 0L

    fun currentConfig(): PinAuthConfig = current

    /**
     * Проверяет присланный код с ограничением числа попыток. Общий счётчик для
     * HTTP и бинарного протокола: перебор с любой стороны упирается в блокировку.
     */
    fun verify(clientCode: String): PinAttemptResult = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return PinAttemptResult.Blocked(blockedUntil - now)

        val expected = current.code
        if (!current.enabled || expected == null) {
            failedAttempts = 0
            return PinAttemptResult.Match
        }
        if (clientCode == expected) {
            failedAttempts = 0
            return PinAttemptResult.Match
        }

        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0
            blockedUntil = now + BLOCK_MS
            return PinAttemptResult.Blocked(BLOCK_MS)
        }
        return PinAttemptResult.Wrong(MAX_ATTEMPTS - failedAttempts)
    }

    /** Новый PIN сбрасывает и счётчик попыток, и накопленную блокировку. */
    fun update(config: PinAuthConfig) = synchronized(lock) {
        current = config
        failedAttempts = 0
        blockedUntil = 0L
    }
}

