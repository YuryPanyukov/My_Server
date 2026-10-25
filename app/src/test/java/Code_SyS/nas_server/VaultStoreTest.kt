package Code_SyS.nas_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Тесты кодека записей хранилища и WebDAV-клиента. Клиент проверяется
 * против заглушки-сервера на loopback-сокете — как остальные
 * сетевые тесты проекта.
 */
class VaultStoreTest {

    private fun record(
        name: String = "photo.jpg",
        status: VaultRecord.Status = VaultRecord.Status.QUEUED,
        sourcePath: String = "/data/photo.jpg",
        deleteAfter: Boolean = false,
        auto: Boolean = false,
        lastError: String = "",
    ) = VaultRecord(
        name = name,
        size = 2048,
        archivedAt = 1_700_000_000_000,
        uploadedAt = if (status == VaultRecord.Status.DONE) 1_700_000_100_000 else 0L,
        status = status,
        lastError = lastError,
        sourcePath = sourcePath,
        deleteAfter = deleteAfter,
        auto = auto,
    )

    @Test
    fun `records survive encode-decode roundtrip`() {
        val records = listOf(
            record(),
            record(
                name = "отчёт годовой.zip",
                status = VaultRecord.Status.DONE,
                deleteAfter = true,
            ),
            record(
                name = "кино 2024 (1).mkv",
                status = VaultRecord.Status.ERROR,
                lastError = "Нет связи с хранилищем: обрыв сети",
                sourcePath = "",
            ),
            record(status = VaultRecord.Status.UPLOADING),
            record(auto = true, deleteAfter = true),
            record(name = "spravka.pdf", status = VaultRecord.Status.DONE, auto = true),
        )

        assertEquals(records, VaultCodec.decode(VaultCodec.encode(records)))
    }

    @Test
    fun `records without auto field decode as manual`() {
        // Строка без девятого поля: старые записи из прошлого запуска приложения.
        val legacy = "photo.jpg" + "\t2048\t1700000000000\t0\tQUEUED\t\t/data/photo.jpg\t0"

        assertEquals(1, VaultCodec.decode(legacy).size)
        assertEquals(false, VaultCodec.decode(legacy).single().auto)
    }

    @Test
    fun `special characters in fields are escaped and restored`() {
        val tricky = VaultRecord(
            name = "bad name\"';<>",
            size = 7,
            archivedAt = 5,
            uploadedAt = 0,
            status = VaultRecord.Status.ERROR,
            lastError = "таб[\t] и перенос[\n] и слэш[\\]",
            sourcePath = "путь\\с\\таб\tуляцией",
            deleteAfter = true,
        )

        assertEquals(tricky, VaultCodec.decode(VaultCodec.encode(listOf(tricky))).single())
    }

    @Test
    fun `broken lines and unknown statuses are dropped safely`() {
        assertEquals(emptyList<VaultRecord>(), VaultCodec.decode(null))
        assertEquals(emptyList<VaultRecord>(), VaultCodec.decode(""))
        // Строка с чужим статусом не должна превращаться в запись.
        assertEquals(
            emptyList<VaultRecord>(),
            VaultCodec.decode("photo.jpg" + "\t1\t0\t0\tNOT_A_STATUS\t\t\t0"),
        )
        assertEquals(
            emptyList<VaultRecord>(),
            VaultCodec.decode("случайный мусор"),
        )
    }

    @Test
    fun `vault errors map to readable hints`() {
        assertTrue(
            VaultErrors.describe(WebDavHttpException(401, "http://lan"))
                .startsWith("Хранилище не пустило")
        )
        assertTrue(VaultErrors.describe(WebDavHttpException(507, "http://lan")).contains("места"))
        assertTrue(VaultErrors.describe(java.io.IOException("reset")).startsWith("Нет связи"))
        assertEquals(
            "Неизвестная ошибка",
            VaultErrors.describe(IllegalStateException(null as Throwable?)),
        )
    }
}

class WebDavClientTest {

    /** Заглушка-сервер: читает запрос целиком и отвечает заданной строкой. */
    private class CapturingServer(
        val response: String = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n",
    ) : AutoCloseable {
        val socket = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val thread = Thread {
            socket.accept().use { client ->
                val reader = client.getInputStream()
                val header = StringBuilder()
                val buffer = ByteArray(1)
                while (!header.toString().contains("\r\n\r\n")) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    header.appendCodePoint(buffer[0].toInt())
                }
                // По Content-Length дочитываем тело целиком.
                val length = Regex("(?i)content-length: (\\d+)").find(header.toString())
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    filled += reader.read(body, filled, length - filled)
                }
                receivedRequest = header.toString()
                receivedBody = body
                client.outputStream.write(response.toByteArray())
                client.outputStream.flush()
            }
        }

        @Volatile var receivedRequest: String = ""
        @Volatile var receivedBody: ByteArray = ByteArray(0)

        init {
            thread.isDaemon = true
            thread.start()
        }

        override fun close() {
            // Заглушка обслуживает одно соединение; даём нити завершиться.
            thread.join(3000)
            socket.close()
        }
    }

    @Test
    fun `upload sends put with basic auth and full body`() {
        CapturingServer().use { server ->
            val port = server.socket.localPort
            val client = WebDavClient(
                WebDavConfig(
                    baseUrl = "http://127.0.0.1:$port/landrop",
                    username = "lana",
                    password = "secretpass",
                )
            )
            val payload = "hello webdav".toByteArray(StandardCharsets.UTF_8)

            val result = client.upload(
                fileName = "заметка.txt",
                size = payload.size.toLong(),
                openStream = { ByteArrayInputStream(payload) },
            )

            assertTrue("ожидался успех, ошибка: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue("PUT" in server.receivedRequest)
            assertTrue("/landrop/${WebDavClient.encodeSegment("заметка.txt")}" in server.receivedRequest)
            val expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("lana:secretpass".toByteArray(StandardCharsets.UTF_8))
            assertTrue(expectedAuth in server.receivedRequest)
            assertEquals(
                "тело должно доехать целиком",
                payload.contentToString(),
                server.receivedBody.contentToString(),
            )
        }
    }

    @Test
    fun `upload reports http errors as failure`() {
        // Заглушка читает запрос целиком, иначе разрыв придёт раньше ответа.
        CapturingServer(
            response = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n",
        ).use { server ->
            val client = WebDavClient(
                WebDavConfig(
                    baseUrl = "http://127.0.0.1:${server.socket.localPort}/folder",
                    username = "u",
                    password = "p",
                )
            )
            val result = client.upload(
                fileName = "f.txt",
                size = 4,
                openStream = { ByteArrayInputStream("data".toByteArray()) },
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue("ожидались WebDavHttpException, а не $error", error is WebDavHttpException)
            assertEquals(401, (error as WebDavHttpException).code)
        }
    }

    @Test
    fun `incomplete config is rejected before the network`() {
        val client = WebDavClient(WebDavConfig.EMPTY)

        val result = client.upload("x.txt", 1, { ByteArrayInputStream(ByteArray(0)) })

        assertTrue(result.isFailure)
    }

    @Test
    fun `url building normalizes trailing slash`() {
        val client = WebDavClient(
            WebDavConfig(baseUrl = "http://nas/landrop/", username = "u", password = "p")
        )

        assertEquals("http://nas/landrop/photo.jpg", client.buildTarget("photo.jpg"))
        assertEquals(
            // Кодирование сегмента делает вызов open(), buildTarget лишь конкатенирует.
            "http://nas/landrop/a%20b.jpg",
            WebDavClient(WebDavConfig("http://nas/landrop", "u", "p")).buildTarget("a%20b.jpg"),
        )
    }

    @Test
    fun `segment encoding escapes spaces and cyrillic`() {
        assertEquals("a%20b.jpg", WebDavClient.encodeSegment("a b.jpg"))
        assertEquals(
            "%D1%84%D0%BE%D1%82%D0%BE.jpg",
            WebDavClient.encodeSegment("фото.jpg"),
        )
    }
}

class ConfigNormalizationTest {

    @Test
    fun `config is complete only with http or https prefix`() {
        assertTrue(WebDavConfig("http://nas:5005/", "u", "p").isComplete())
        assertTrue(WebDavConfig("  https://nas/landrop  ", "u", "p").isComplete())
        assertEquals(false, WebDavConfig("nas/landrop", "u", "p").isComplete())
        assertEquals(false, WebDavConfig("", "", "").isComplete())
        assertEquals(false, WebDavConfig.EMPTY.isComplete())
    }

    @Test
    fun `normalized url always ends with slash`() {
        assertEquals("http://nas/landrop/", WebDavConfig("http://nas/landrop", "u", "p").normalizedUrl())
        assertEquals("http://nas/landrop/", WebDavConfig("http://nas/landrop/", "u", "p").normalizedUrl())
    }
}
