package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

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
