package Code_SyS.nas_server

import android.content.Context

/**
 * Недавние телефоны, к которым подключались по QR.
 *
 * Хранятся в `SharedPreferences`: это не секреты, а удобные ярлыки, чтобы
 * не сканировать код при каждом подключении. Список короткий и без истории.
 */
object RecentHosts {

    /** Сколько адресов держать: больше на главном экране не помещается. */
    const val MAX = 5

    private const val PREFS = "landrop_recent"
    private const val KEY = "hosts"

    fun list(context: Context): List<ShareTarget> =
        RecentHostsCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    /** Ставит адрес в начало списка; старые дубликаты убираются. */
    fun remember(context: Context, target: ShareTarget) {
        val updated = (listOf(target) + list(context).filter { it != target }).take(MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, RecentHostsCodec.encode(updated))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}

/** Кодирование списка без Android API — проверяется JVM-тестами. */
internal object RecentHostsCodec {

    fun encode(targets: List<ShareTarget>): String =
        targets.take(RecentHosts.MAX)
            .joinToString("\n") { "${it.host}|${it.port}" }

    fun decode(raw: String?): List<ShareTarget> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.lastIndexOf('|')
                if (separator <= 0) return@mapNotNull null
                val host = line.substring(0, separator)
                val port = line.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
                ShareLinks.parseAuthority("$host:$port")
            }
            .distinct()
            .take(RecentHosts.MAX)
            .toList()
    }
}
