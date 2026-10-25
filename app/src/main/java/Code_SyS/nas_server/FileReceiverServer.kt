package Code_SyS.nas_server

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Сетевой цикл приёма файлов по протоколу [Protocol].
 *
 * На одном порту поддерживаются два способа передачи:
 *  - бинарный протокол [Protocol] (клиенты вроде `scripts/send_file.py`);
 *  - HTTP (страница загрузки в браузере, `POST/PUT /upload`) — обрабатывается
 *    [HttpRequestHandler]. Способ определяется по первым байтам запроса.
 *
 * Намеренно не зависит ни от одного Android API: класс целиком проверяется
 * JVM-тестами (см. `FileReceiverServerTest`, `HttpModeTest`), без эмулятора и устройства.
 *
 * Один экземпляр — одно открытие сокета. Для повторного запуска создавайте новый.
 *
 * @param port порт прослушивания; `0` — выбрать свободный (используется в тестах)
 * @param bindAddress адрес для bind; `null` — все интерфейсы
 * @param socketTimeoutMs таймаут чтения от клиента, чтобы зависший клиент не держал поток
 * @param logger приёмник диагностических сообщений
 * @param saver запись файла; возвращает `File` при успехе и `null` при ошибке/обрыве
 * @param fileLister файлы, доступные для просмотра через HTTP-страницу
 * @param mediaSources источники галереи: принятые файлы и медиатека телефона
 * @param fileThumbnailer превью принятого файла для списка на главной странице
 */
class FileReceiverServer(
    private val port: Int = Protocol.PORT,
    private val bindAddress: InetAddress? = null,
    private val socketTimeoutMs: Int = DEFAULT_SOCKET_TIMEOUT_MS,
    private val logger: (String) -> Unit = {},
    private val saver: (fileName: String, fileSize: Long, input: InputStream) -> File?,
    private val fileLister: () -> List<File> = { emptyList() },
    private val mediaSources: () -> List<MediaSource> = { emptyList() },
    private val fileThumbnailer: (File) -> ByteArray? = { null },
) : Closeable {

    companion object {
        const val DEFAULT_SOCKET_TIMEOUT_MS = 30_000

        /** Сколько байт запроса читается для определения протокола ("GET ", "POST"...). */
        private const val PROTOCOL_PREFIX_SIZE = 4

        private val HTTP_METHOD_PREFIXES = listOf("GET ", "POST", "PUT ", "HEAD", "OPTI", "DELE")
    }

    private val httpHandler = HttpRequestHandler(
        saver = saver,
        files = fileLister,
        mediaSources = mediaSources,
        fileThumbnailer = fileThumbnailer,
        logger = logger,
    )

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "file-receiver-client").apply { isDaemon = true }
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Реальный порт прослушивания; `-1`, если сервер не запущен. */
    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * Открывает сокет (синхронно, чтобы ошибки bind были видны вызывающему)
     * и запускает цикл приёма в отдельном потоке.
     */
    @Throws(IOException::class)
    fun start() {
        if (isRunning) return

        val socket = if (bindAddress != null) {
            ServerSocket(port, 0, bindAddress)
        } else {
            ServerSocket(port)
        }
        serverSocket = socket
        isRunning = true
        acceptThread = Thread({ acceptLoop(socket) }, "file-receiver-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        logger("Сервер слушает ${socket.inetAddress.hostAddress}:${socket.localPort}")
        try {
            while (isRunning) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break // сокет закрыт через close() или приём прерван
                }
                logger("Клиент подключился: ${client.inetAddress.hostAddress}")
                clients.execute { handleClient(client) }
            }
        } finally {
            isRunning = false
        }
    }

    private fun handleClient(socket: Socket) {
        var input: PushbackInputStream? = null
        try {
            socket.soTimeout = socketTimeoutMs
            val rawInput = PushbackInputStream(socket.inputStream, PROTOCOL_PREFIX_SIZE)
            input = rawInput
            val output = socket.outputStream

            // Определяем протокол по первым байтам. Бинарный заголовок начинается
            // с длины имени (0x00.. для реальных имён), HTTP — с метода и пробела.
            val prefix = ByteArray(PROTOCOL_PREFIX_SIZE)
            var prefixLength = 0
            while (prefixLength < PROTOCOL_PREFIX_SIZE) {
                val read = rawInput.read(prefix, prefixLength, PROTOCOL_PREFIX_SIZE - prefixLength)
                if (read < 0) break
                prefixLength += read
            }
            if (prefixLength > 0) rawInput.unread(prefix, 0, prefixLength)

            if (isHttpRequest(prefix, prefixLength)) {
                httpHandler.handle(rawInput, output)
            } else {
                handleBinary(rawInput, output)
            }
        } catch (e: Exception) {
            logger("Ошибка соединения: ${e.message}")
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun isHttpRequest(prefix: ByteArray, length: Int): Boolean {
        if (length < PROTOCOL_PREFIX_SIZE) return false
        val head = String(prefix, 0, PROTOCOL_PREFIX_SIZE, StandardCharsets.US_ASCII)
        return HTTP_METHOD_PREFIXES.any { head.startsWith(it) }
    }

    private fun handleBinary(input: InputStream, outputStream: OutputStream) {
        val dataIn = DataInputStream(input)
        val dataOut = DataOutputStream(outputStream)
        val success = try {
            val header = Protocol.readHeader(dataIn)
            logger("Приём: ${header.fileName} (${header.fileSize} байт)")
            val savedFile = saver(header.fileName, header.fileSize, dataIn)
            val ok = savedFile != null
            logger("Файл ${header.fileName}: ${if (ok) "OK" else "FAILED"}")
            ok
        } catch (e: Exception) {
            logger("Ошибка обработки клиента: ${e.message}")
            false
        }

        // Ответ отправляется и на ошибке, иначе клиент ждёт до закрытия сокета.
        dataOut.writeBoolean(success)
        dataOut.flush()
    }

    /** Останавливает приём и освобождает все ресурсы. */
    override fun close() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread?.join(1_000)
        acceptThread = null
        clients.shutdownNow()
        try {
            clients.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
