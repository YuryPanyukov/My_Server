package Code_SyS.nas_server

/**
 * Адрес телефона-хоста и ссылки для второго устройства.
 *
 * QR-код содержит обычный HTTP-адрес страницы-мостика [openUrl]: его поймёт
 * любой сканер. Страница пробует открыть приложение по [deepLink], а если
 * приложения нет — остаётся веб-интерфейс [browserUrl].
 */
data class ShareTarget(val host: String, val port: Int) {

    /** Обычный адрес веб-интерфейса. */
    val browserUrl: String get() = "http://$host:$port"

    /** Страница-мостик: то, что закодировано в QR. */
    val openUrl: String get() = "$browserUrl/open"

    /** Ссылка, которую обрабатывает [RemoteActivity]. */
    val deepLink: String get() = "$DEEP_SCHEME://$DEEP_HOST?host=$host&port=$port"

    companion object {
        const val DEEP_SCHEME = "landrop"
        const val DEEP_HOST = "connect"
        const val DEFAULT_PORT = Protocol.PORT
    }
}

/**
 * Разбор адресов из QR, deep link и заголовка `Host`.
 * Класс не зависит от Android API и проверяется JVM-тестами.
 */
object ShareLinks {

    /** Собирает deep link для приложения на сканирующем телефоне. */
    fun deepLink(host: String, port: Int = ShareTarget.DEFAULT_PORT): String =
        ShareTarget(host, port).deepLink

    /**
     * Разбирает то, что вернул сканер QR: страницу-мостик, deep link,
     * голый адрес `IP:порт` или просто IP.
     */
    fun parseScanResult(raw: String?): ShareTarget? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        if (value.startsWith("${ShareTarget.DEEP_SCHEME}://", ignoreCase = true)) {
            return parseDeepLink(value)
        }
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return parseHttpLink(value)
        }
        return parseAuthority(value)
    }

    /**
     * Разбирает `landrop://connect?host=192.168.1.42&port=8080`.
     * `null` — ссылка не наша или адрес некорректен.
     */
    fun parseDeepLink(raw: String?): ShareTarget? {
        val value = raw?.trim().orEmpty()
        val prefix = "${ShareTarget.DEEP_SCHEME}://${ShareTarget.DEEP_HOST}"
        if (!value.startsWith(prefix, ignoreCase = true)) return null

        val rest = value.substring(prefix.length)
        if (!rest.startsWith("?")) return null

        val query = rest.substring(1)
        val params = query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val index = pair.indexOf('=')
                val key = if (index >= 0) pair.substring(0, index) else pair
                val valuePart = if (index >= 0) pair.substring(index + 1) else ""
                percentDecode(key) to percentDecode(valuePart)
            }

        val host = params["host"]?.trim().orEmpty()
        val port = params["port"]?.toIntOrNull() ?: ShareTarget.DEFAULT_PORT
        return targetOf(host, port)
    }

    /**
     * Разбирает заголовок `Host` (`192.168.1.42:8080`, `[::1]:8080`).
     * Порт по умолчанию — [ShareTarget.DEFAULT_PORT].
     */
    fun parseAuthority(authority: String?): ShareTarget? {
        val value = authority?.trim().orEmpty()
        if (value.isEmpty()) return null

        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1) return null
            host = value.substring(0, end + 1)
            val tail = value.substring(end + 1)
            portText = if (tail.startsWith(":")) tail.substring(1) else ""
        } else {
            val colon = value.lastIndexOf(':')
            // У IPv4 без скобок двоеточие — только перед портом.
            if (colon < 0) {
                host = value
                portText = ""
            } else {
                host = value.substring(0, colon)
                portText = value.substring(colon + 1)
            }
        }

        val port = if (portText.isEmpty()) ShareTarget.DEFAULT_PORT else portText.toIntOrNull() ?: return null
        return targetOf(host, port)
    }

    private fun parseHttpLink(value: String): ShareTarget? {
        return try {
            val uri = java.net.URI(value)
            // Сервер LanDrop работает только по HTTP: https-ссылки из чужих QR не наши.
            if (!uri.scheme.equals("http", ignoreCase = true)) return null
            // Путь и параметры не важны: из QR нужен только адрес сервера.
            val host = uri.host ?: return null
            val port = if (uri.port in 1..65535) uri.port else ShareTarget.DEFAULT_PORT
            targetOf(host, port)
        } catch (_: Exception) {
            null
        }
    }

    private fun targetOf(host: String, port: Int): ShareTarget? {
        if (!isSafeHost(host)) return null
        if (port !in 1..65535) return null
        return ShareTarget(host, port)
    }

    /**
     * В адрес попадают только ASCII-символы IP и DNS-имён: никаких кириллицы,
     * путей и пробелов — иначе текст из чужого QR станет «хостом».
     */
    private fun isSafeHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 255) return false
        return host.all { c ->
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '.' || c == '-' || c == ':' || c == '[' || c == ']'
        }
    }

    /** Декодирует %XX (UTF-8); `+` остаётся плюсом, как и в [HttpRequestHandler]. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                bytes.write(literal.toString().toByteArray(Charsets.UTF_8))
                literal.clear()
            }
        }

        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val decoded = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (decoded != null) {
                    flushLiteral()
                    bytes.write(decoded)
                    i += 3
                    continue
                }
            }
            literal.append(c)
            i++
        }
        flushLiteral()
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
