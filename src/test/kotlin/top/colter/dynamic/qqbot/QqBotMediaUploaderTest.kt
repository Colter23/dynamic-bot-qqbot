package top.colter.dynamic.qqbot

import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QqBotMediaUploaderTest {
    @Test
    fun `文件校验值覆盖整文件与前 10MB`() {
        val payload = ByteArray(1024) { index -> (index % 251).toByte() }
        val file = createTempFile("small.bin", payload)

        val hashes = QqBotFileHasher.hash(file)

        assertEquals(1024L, hashes.sizeBytes)
        assertEquals(payload.md5(), hashes.md5)
        assertEquals(payload.sha1(), hashes.sha1)
        assertEquals(payload.md5(), hashes.md5Prefix)
    }

    @Test
    fun `文件校验值的前缀只覆盖 10002432 字节`() {
        val prefixBytes = QqBotFileHasher.PREFIX_BYTES.toInt()
        val payload = ByteArray(prefixBytes + 16) { index -> (index % 253).toByte() }
        val file = createTempFile("large.bin", payload)

        val hashes = QqBotFileHasher.hash(file)

        assertEquals(payload.size.toLong(), hashes.sizeBytes)
        assertEquals(payload.md5(), hashes.md5)
        assertEquals(payload.copyOf(prefixBytes).md5(), hashes.md5Prefix)
        assertEquals(false, hashes.md5Prefix == hashes.md5)
    }

    @Test
    fun `分片上传按分片大小逐片 PUT 并合并`() {
        val payload = ByteArray(20) { index -> index.toByte() }
        val file = createTempFile("image.png", payload)
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(
                        200,
                        """
                            {
                              "upload_id":"upload_1",
                              "block_size":"8",
                              "parts":[
                                {"index":0,"presigned_url":"https://cos.example.com/p0","block_size":"8"},
                                {"index":1,"presigned_url":"https://cos.example.com/p1","block_size":"8"},
                                {"index":2,"presigned_url":"https://cos.example.com/p2","block_size":"8"}
                              ],
                              "upload_config":{"concurrency":1,"retry_timeout":300,"retry_delay":1}
                            }
                        """.trimIndent(),
                    ),
                    QqBotHttpResponse(200, ""),
                    QqBotHttpResponse(200, ""),
                    QqBotHttpResponse(200, ""),
                    QqBotHttpResponse(200, """{"file_info":"file-info-1","ttl":3600}"""),
                ),
            ),
            putResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, ""),
                    QqBotHttpResponse(200, ""),
                    QqBotHttpResponse(200, ""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val resource = QqBotMediaUploader(client).upload(
            scope = QqBotOpenApiScope.Group("group-openid"),
            fileType = QqBotMediaFileType.IMAGE,
            path = file,
            fileName = "image.png",
        )

        assertEquals("file-info-1", resource.fileInfo)
        assertEquals(listOf(8, 8, 4), transport.puts.map { it.bytes.size })
        assertEquals(
            listOf(
                "https://cos.example.com/p0",
                "https://cos.example.com/p1",
                "https://cos.example.com/p2",
            ),
            transport.puts.map { it.url },
        )
        assertEquals(payload.toList(), transport.puts.flatMap { it.bytes.toList() })
        assertEquals(
            listOf(
                "https://api.bot.qq.com/v2/groups/group-openid/upload_prepare",
                "https://api.bot.qq.com/v2/groups/group-openid/upload_part_finish",
                "https://api.bot.qq.com/v2/groups/group-openid/upload_part_finish",
                "https://api.bot.qq.com/v2/groups/group-openid/upload_part_finish",
                "https://api.bot.qq.com/v2/groups/group-openid/files",
            ),
            transport.posts.drop(1).map { it.url },
        )
    }

    @Test
    fun `分片大小超出支持范围时直接失败`() {
        val file = createTempFile("image.png", ByteArray(20) { index -> index.toByte() })
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(
                        200,
                        """
                            {
                              "upload_id":"upload_1",
                              "block_size":"99999999999",
                              "parts":[{"index":0,"presigned_url":"https://cos.example.com/p0","block_size":"99999999999"}],
                              "upload_config":{"concurrency":1,"retry_timeout":300,"retry_delay":1}
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
            putResponses = ArrayDeque(),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            QqBotMediaUploader(client).upload(
                scope = QqBotOpenApiScope.Group("group-openid"),
                fileType = QqBotMediaFileType.IMAGE,
                path = file,
                fileName = "image.png",
            )
        }

        assertEquals(true, error.message.orEmpty().contains("分片大小超出支持范围"))
        assertEquals(0, transport.puts.size)
    }

    @Test
    fun `分片未覆盖整个文件时直接失败`() {
        val file = createTempFile("image.png", ByteArray(20) { index -> index.toByte() })
        // 服务端只下发了一个 8 字节分片，剩余 12 字节无分片覆盖
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(
                        200,
                        """
                            {
                              "upload_id":"upload_1",
                              "block_size":"8",
                              "parts":[{"index":0,"presigned_url":"https://cos.example.com/p0","block_size":"8"}],
                              "upload_config":{"concurrency":1,"retry_timeout":300,"retry_delay":1}
                            }
                        """.trimIndent(),
                    ),
                    QqBotHttpResponse(200, ""),
                    QqBotHttpResponse(200, """{"file_info":"file-info-1"}"""),
                ),
            ),
            putResponses = ArrayDeque(listOf(QqBotHttpResponse(200, ""))),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            QqBotMediaUploader(client).upload(
                scope = QqBotOpenApiScope.Group("group-openid"),
                fileType = QqBotMediaFileType.IMAGE,
                path = file,
                fileName = "image.png",
            )
        }

        assertEquals(true, error.message.orEmpty().contains("分片未覆盖整个文件"))
    }

    @Test
    fun `上传超时按分片大小计算且限制最大等待时间`() {
        assertEquals(Duration.ofSeconds(60), qqBotUploadPartTimeout(20))
        assertEquals(Duration.ofSeconds(110), qqBotUploadPartTimeout(5L * 1024 * 1024))
        assertEquals(Duration.ofSeconds(190), qqBotUploadPartTimeout(10L * 1024 * 1024))
        assertEquals(Duration.ofSeconds(300), qqBotUploadPartTimeout(32L * 1024 * 1024))
    }

    @Test
    fun `确认超时重试确认步骤且不重复 PUT 已上传的分片`() {
        val clock = AtomicLong()
        val transport = UploadTransport(
            onFinish = { attempt ->
                if (attempt == 1) throw HttpTimeoutException("确认超时")
            },
        )

        val result = uploader(transport, clock).uploadTestFile()

        assertEquals("file-info", result.fileInfo)
        assertEquals(1, transport.putTimeouts.size)
        assertEquals(listOf(Duration.ofSeconds(30), Duration.ofSeconds(30)), transport.finishTimeouts)
        assertEquals(TimeUnit.SECONDS.toNanos(1), clock.get())
    }

    @Test
    fun `上传超时重试使用剩余时间并保留正确分片内容`() {
        val clock = AtomicLong()
        val transport = UploadTransport(
            retryTimeoutSeconds = 5,
            onPut = { attempt ->
                if (attempt == 1) {
                    clock.addAndGet(TimeUnit.SECONDS.toNanos(3))
                    throw HttpTimeoutException("上传超时")
                }
            },
        )

        val result = uploader(transport, clock).uploadTestFile()

        assertEquals("file-info", result.fileInfo)
        assertEquals(listOf(Duration.ofSeconds(5), Duration.ofSeconds(1)), transport.putTimeouts)
        assertEquals(listOf(Duration.ofSeconds(1)), transport.finishTimeouts)
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(1, 2, 3)), transport.payloads)
    }

    @Test
    fun `重试等待后窗口耗尽时不再上传`() {
        val clock = AtomicLong()
        val transport = UploadTransport(
            retryTimeoutSeconds = 2,
            onPut = { throw HttpTimeoutException("上传超时") },
        )
        val uploader = QqBotMediaUploader(
            client(transport),
            nowNanos = clock::get,
            sleepMillis = { clock.addAndGet(TimeUnit.SECONDS.toNanos(2)) },
        )

        assertFailsWith<HttpTimeoutException> { uploader.uploadTestFile() }

        assertEquals(1, transport.putTimeouts.size)
        assertEquals(0, transport.finishTimeouts.size)
    }

    @Test
    fun `禁止重试或永久失败时只尝试一次`() {
        val clock = AtomicLong()
        val noRetry = UploadTransport(
            retryTimeoutSeconds = 0,
            onPut = { throw HttpTimeoutException("上传超时") },
        )
        assertFailsWith<HttpTimeoutException> { uploader(noRetry, clock).uploadTestFile() }
        assertEquals(listOf(Duration.ofSeconds(60)), noRetry.putTimeouts)

        val forbidden = UploadTransport(
            onPut = {
                throw QqBotOpenApiException("上传", 403, message = "签名无效", body = "")
            },
        )
        assertFailsWith<QqBotOpenApiException> { uploader(forbidden, clock).uploadTestFile() }
        assertEquals(1, forbidden.putTimeouts.size)
        assertEquals(0L, clock.get())
    }

    @Test
    fun `多目标上传共享并发上限`() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val transport = UploadTransport(onPut = {
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            started.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
            } finally {
                active.decrementAndGet()
            }
        })
        val uploader = QqBotMediaUploader(client(transport))
        val file = createTempFile("image.png", byteArrayOf(1, 2, 3))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { index ->
                executor.submit<QqBotMediaResource> {
                    uploader.upload(QqBotOpenApiScope.Group("group-$index"), QqBotMediaFileType.IMAGE, file, "image.png")
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(2, active.get())
            release.countDown()
            futures.forEach { assertEquals("file-info", it.get(5, TimeUnit.SECONDS).fileInfo) }
            assertEquals(2, maximum.get())
            assertEquals(4, transport.putTimeouts.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `确认请求等待期间其他目标仍可上传`() {
        assertConfirmationDoesNotBlockOtherTargets(waitForRetry = false)
    }

    @Test
    fun `确认失败重试等待期间其他目标仍可上传`() {
        assertConfirmationDoesNotBlockOtherTargets(waitForRetry = true)
    }

    private fun assertConfirmationDoesNotBlockOtherTargets(waitForRetry: Boolean) {
        val waiting = CountDownLatch(2)
        val release = CountDownLatch(1)
        fun awaitRelease() {
            waiting.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val transport = UploadTransport(onFinish = { attempt ->
            if (attempt <= 2) {
                if (waitForRetry) throw HttpTimeoutException("确认超时")
                awaitRelease()
            }
        })
        val uploader = QqBotMediaUploader(client(transport), sleepMillis = { awaitRelease() })
        val file = createTempFile("image.png", byteArrayOf(1, 2, 3))
        val executor = Executors.newFixedThreadPool(3)
        try {
            val pending = (1..2).map { index ->
                executor.submit<QqBotMediaResource> {
                    uploader.upload(QqBotOpenApiScope.Group("group-$index"), QqBotMediaFileType.IMAGE, file, "image.png")
                }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            val healthy = executor.submit<QqBotMediaResource> {
                uploader.upload(QqBotOpenApiScope.Group("healthy-group"), QqBotMediaFileType.IMAGE, file, "image.png")
            }

            // 前两个任务仍在等待时，正常目标应能完成 PUT、确认和合并。
            assertEquals("file-info", healthy.get(5, TimeUnit.SECONDS).fileInfo)
            assertEquals(1L, release.count)
            assertEquals(3, transport.putTimeouts.size)

            release.countDown()
            pending.forEach { assertEquals("file-info", it.get(5, TimeUnit.SECONDS).fileInfo) }
            assertEquals(3, transport.putTimeouts.size)
            assertEquals(if (waitForRetry) 5 else 3, transport.finishTimeouts.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun client(transport: QqBotHttpTransport): QqBotOpenApiClient = QqBotOpenApiClient(
        config = QqBotConfig(appId = "app-id", appSecret = "secret"),
        transport = transport,
    )

    private fun uploader(transport: QqBotHttpTransport, clock: AtomicLong): QqBotMediaUploader = QqBotMediaUploader(
        client(transport),
        nowNanos = clock::get,
        sleepMillis = { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(it)) },
    )

    private fun QqBotMediaUploader.uploadTestFile(): QqBotMediaResource = upload(
        scope = QqBotOpenApiScope.Group("group"),
        fileType = QqBotMediaFileType.IMAGE,
        path = createTempFile("image.png", byteArrayOf(1, 2, 3)),
        fileName = "image.png",
    )

    private class UploadTransport(
        private val retryTimeoutSeconds: Long = 300,
        private val onPut: (Int) -> Unit = {},
        private val onFinish: (Int) -> Unit = {},
    ) : QqBotHttpTransport by FakeQqBotHttpTransport() {
        val putTimeouts = CopyOnWriteArrayList<Duration>()
        val finishTimeouts = CopyOnWriteArrayList<Duration>()
        val payloads = CopyOnWriteArrayList<List<Byte>>()
        private val puts = AtomicInteger()
        private val finishes = AtomicInteger()

        override fun postJson(url: String, headers: Map<String, String>, body: String): QqBotHttpResponse = when {
            url.endsWith("/getAppAccessToken") -> QqBotHttpResponse(200, """{"access_token":"token","expires_in":7200}""")
            url.endsWith("/upload_prepare") -> QqBotHttpResponse(
                200,
                """{"upload_id":"upload-1","parts":[{"index":0,"presigned_url":"https://cos.example.com/p0","block_size":"8"}],"upload_config":{"retry_timeout":$retryTimeoutSeconds}}""",
            )
            url.endsWith("/upload_part_finish") -> {
                onFinish(finishes.incrementAndGet())
                QqBotHttpResponse(200, "")
            }
            url.endsWith("/files") -> QqBotHttpResponse(200, """{"file_info":"file-info"}""")
            else -> error("意外的请求：$url")
        }

        override fun postJsonWithTimeout(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeout: Duration,
        ): QqBotHttpResponse {
            if (url.endsWith("/upload_part_finish")) finishTimeouts += timeout
            return postJson(url, headers, body)
        }

        override fun putBytesWithTimeout(url: String, bytes: ByteArray, timeout: Duration): QqBotHttpResponse {
            putTimeouts += timeout
            payloads += bytes.toList()
            onPut(puts.incrementAndGet())
            return QqBotHttpResponse(200, "")
        }
    }

    private fun createTempFile(name: String, payload: ByteArray): Path {
        val dir = Files.createTempDirectory("qqbot-uploader-test")
        val file = dir.resolve(name)
        Files.write(file, payload)
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        return file
    }
}

private fun ByteArray.md5(): String = digest("MD5")

private fun ByteArray.sha1(): String = digest("SHA-1")

private fun ByteArray.digest(algorithm: String): String {
    return MessageDigest.getInstance(algorithm).digest(this).joinToString("") { byte -> "%02x".format(byte) }
}
