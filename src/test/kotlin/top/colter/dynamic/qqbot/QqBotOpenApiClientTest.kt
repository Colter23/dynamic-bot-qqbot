package top.colter.dynamic.qqbot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QqBotOpenApiClientTest {
    @Test
    fun `访问令牌请求使用统一域名与官方应用凭据格式`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = " app-id ", appSecret = " secret "),
            transport = transport,
            nowMillis = { 1_000L },
        )

        val token = client.accessToken()

        assertEquals("token-a", token.value)
        assertEquals(7_201_000L, token.expiresAtMillis)
        assertEquals("https://api.bot.qq.com/app/getAppAccessToken", transport.posts.single().url)
        val body = Json.parseToJsonElement(transport.posts.single().body).jsonObject
        assertEquals("app-id", body.getValue("appId").jsonPrimitive.content)
        assertEquals("secret", body.getValue("clientSecret").jsonPrimitive.content)
    }

    @Test
    fun `访问令牌在刷新提前量前保持缓存`() {
        var now = 10_000L
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":120}"""),
                    QqBotHttpResponse(200, """{"access_token":"token-b","expires_in":120}"""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
            nowMillis = { now },
        )

        assertEquals("token-a", client.accessToken().value)
        assertEquals("token-a", client.accessToken().value)
        assertEquals(1, transport.posts.size)

        now = 80_001L

        assertEquals("token-b", client.accessToken().value)
        assertEquals(2, transport.posts.size)
    }

    @Test
    fun `网关接入点使用 QQBot 授权并解析限制信息`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(
                        200,
                        """
                            {
                              "url":"wss://api.bot.qq.com/websocket/",
                              "shards":1,
                              "session_start_limit":{
                                "total":1000,
                                "remaining":999,
                                "reset_after":86400000,
                                "max_concurrency":1
                              }
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val endpoint = client.gatewayBotEndpoint()

        assertEquals("wss://api.bot.qq.com/websocket/", endpoint.url)
        assertEquals(1, endpoint.shards)
        assertEquals(1000, endpoint.sessionStartLimit?.total)
        assertEquals(999, endpoint.sessionStartLimit?.remaining)
        assertEquals(86_400_000L, endpoint.sessionStartLimit?.resetAfterMillis)
        assertEquals(1, endpoint.sessionStartLimit?.maxConcurrency)
        assertEquals("https://api.bot.qq.com/gateway/bot", transport.gets.single().url)
        assertEquals("QQBot token-a", transport.gets.single().headers.getValue("Authorization"))
    }

    @Test
    fun `OpenAPI 错误包含状态码业务码和消息`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(401, """{"err_code":11243,"message":"token invalid"}""")),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val error = assertFailsWith<QqBotOpenApiException> {
            client.accessToken()
        }

        assertEquals(401, error.statusCode)
        assertEquals("11243", error.code)
        assertTrue(error.message.orEmpty().contains("token invalid"))
    }

    @Test
    fun `回复窗口过期等业务码判定为不可重试`() {
        val nonRetryableCodes = listOf(
            "304103", "40034005", "40034024", "40034025", "40034026", "40034027", "40034128",
            "22006", "304061", "304080", "40054007", "40054010", "40054018", "50059",
            "40034006", "40054002", "40054003", "40054013", "40034101",
            "40054004", "40054005",
            "850019", "850031",
        )
        val retryableCodes = listOf("11243", "40034100", "40093001", null, "")

        nonRetryableCodes.forEach { code ->
            val error = openApiException(code)
            assertEquals(false, error.retryable, "业务码 $code 应判定为不可重试")
        }
        retryableCodes.forEach { code ->
            val error = openApiException(code)
            assertEquals(true, error.retryable, "业务码 $code 应保持可重试")
        }
    }

    @Test
    fun `发送异常类业务码判定为状态未知`() {
        val unknownCodes = listOf("50055001", "50055002", "50055006")
        val knownCodes = listOf("40034005", "40034100", null)

        unknownCodes.forEach { code ->
            assertEquals(true, openApiException(code).outcomeUnknown, "业务码 $code 应判定为状态未知")
        }
        knownCodes.forEach { code ->
            assertEquals(false, openApiException(code).outcomeUnknown, "业务码 $code 不应判定为状态未知")
        }
    }

    @Test
    fun `上传通道临时故障码判定为可重试瞬态`() {
        val transientCodes = listOf("40093001", "850026", "850027")
        transientCodes.forEach { code ->
            val error = openApiException(code)
            assertEquals(true, error.transientUploadFailure, "业务码 $code 应判定为上传瞬态故障")
            assertEquals(true, error.retryable, "上传瞬态故障应保持可重试")
        }
    }

    private fun openApiException(code: String?): QqBotOpenApiException {
        return QqBotOpenApiException(
            action = "发送 QQ 官方机器人消息",
            statusCode = 400,
            code = code,
            message = "业务错误",
            body = """{"err_code":"$code"}""",
        )
    }

    @Test
    fun `网关接入点拒绝缺失 url 的响应`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"shards":1}""")),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val error = assertFailsWith<QqBotOpenApiException> {
            client.gatewayBotEndpoint()
        }

        assertEquals("响应缺少 url", error.message)
    }

    @Test
    fun `单聊消息发送提交官方文本负载`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"id":"send-1","timestamp":"2026-06-19T04:00:00Z"}"""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val receipt = client.sendMessage(
            scope = QqBotOpenApiScope.User("user/open id"),
            request = QqBotSendRequest(
                content = "hello",
                replyToMessageId = "source-msg",
                msgSeq = 2,
            ),
        )

        assertEquals("send-1", receipt.messageId)
        val sent = transport.posts.last()
        assertEquals("https://api.bot.qq.com/v2/users/user%2Fopen%20id/messages", sent.url)
        assertEquals("QQBot token-a", sent.headers.getValue("Authorization"))
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("hello", body.getValue("content").jsonPrimitive.content)
        assertEquals(0, body.getValue("msg_type").jsonPrimitive.int)
        assertEquals("source-msg", body.getValue("msg_id").jsonPrimitive.content)
        assertEquals(2, body.getValue("msg_seq").jsonPrimitive.int)
    }

    @Test
    fun `群聊消息发送可以提交无回复 id 的纯文本`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"id":"group-send-1"}"""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val receipt = client.sendMessage(
            scope = QqBotOpenApiScope.Group("group-openid"),
            request = QqBotSendRequest(content = "group hello"),
        )

        assertEquals("group-send-1", receipt.messageId)
        val sent = transport.posts.last()
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/messages", sent.url)
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("group hello", body.getValue("content").jsonPrimitive.content)
        assertEquals(false, "msg_id" in body)
        assertEquals(false, "msg_seq" in body)
    }

    @Test
    fun `群聊被动回复提交 msg_id 和 msg_seq`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"id":"group-reply-1"}"""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val receipt = client.sendMessage(
            scope = QqBotOpenApiScope.Group("group-openid"),
            request = QqBotSendRequest(
                content = "连通性正常",
                replyToMessageId = "group-msg-2",
                msgSeq = 1,
            ),
        )

        assertEquals("group-reply-1", receipt.messageId)
        val body = Json.parseToJsonElement(transport.posts.last().body).jsonObject
        assertEquals("连通性正常", body.getValue("content").jsonPrimitive.content)
        assertEquals(0, body.getValue("msg_type").jsonPrimitive.int)
        assertEquals("group-msg-2", body.getValue("msg_id").jsonPrimitive.content)
        assertEquals(1, body.getValue("msg_seq").jsonPrimitive.int)
    }

    @Test
    fun `单聊富媒体 URL 上传提交官方文件负载`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"file_uuid":"uuid-1","file_info":"file-info-1","ttl":3600}"""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val resource = client.prepareMedia(
            scope = QqBotOpenApiScope.User("user/open id"),
            request = QqBotMediaPrepareRequest(
                fileType = QqBotMediaFileType.IMAGE,
                url = " https://example.com/a.png ",
            ),
        )

        assertEquals("file-info-1", resource.fileInfo)
        assertEquals("uuid-1", resource.fileUuid)
        assertEquals(3600L, resource.ttlSeconds)
        val sent = transport.posts.last()
        assertEquals("https://api.bot.qq.com/v2/users/user%2Fopen%20id/files", sent.url)
        assertEquals("QQBot token-a", sent.headers.getValue("Authorization"))
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals(1, body.getValue("file_type").jsonPrimitive.int)
        assertEquals("https://example.com/a.png", body.getValue("url").jsonPrimitive.content)
        assertEquals(false, body.getValue("srv_send_msg").jsonPrimitive.boolean)
        assertEquals(false, "file_data" in body)
    }

    @Test
    fun `富媒体分片上传合并提交 upload_id 负载`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"file_info":"file-info-1"}"""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val resource = client.prepareMedia(
            scope = QqBotOpenApiScope.Group("group-openid"),
            request = QqBotMediaPrepareRequest(
                fileType = QqBotMediaFileType.VIDEO,
                uploadId = " upload_1 ",
                fileName = "demo.mp4",
            ),
        )

        assertEquals("file-info-1", resource.fileInfo)
        val body = Json.parseToJsonElement(transport.posts.last().body).jsonObject
        assertEquals(2, body.getValue("file_type").jsonPrimitive.int)
        assertEquals("upload_1", body.getValue("upload_id").jsonPrimitive.content)
        assertEquals("demo.mp4", body.getValue("file_name").jsonPrimitive.content)
        assertEquals(null, body["url"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body.getValue("srv_send_msg").jsonPrimitive.boolean)
    }

    @Test
    fun `富媒体资源登记拒绝空资源数据`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            client.prepareMedia(
                scope = QqBotOpenApiScope.User("user-openid"),
                request = QqBotMediaPrepareRequest(fileType = QqBotMediaFileType.IMAGE),
            )
        }

        assertEquals("QQ 官方机器人富媒体资源 URL 或 upload_id 不能同时为空", error.message)
    }

    @Test
    fun `分片上传预上传解析 upload_id 与分片列表`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(
                        200,
                        """
                            {
                              "upload_id":"upload_1",
                              "block_size":"10485760",
                              "parts":[
                                {"index":0,"presigned_url":"https://cos.example.com/p1","block_size":"10485760"},
                                {"index":1,"presigned_url":"https://cos.example.com/p2","block_size":"10485760"}
                              ],
                              "upload_config":{"concurrency":2,"retry_timeout":300,"retry_delay":1}
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val ticket = client.prepareUpload(
            scope = QqBotOpenApiScope.User("user-openid"),
            request = QqBotUploadPrepareRequest(
                fileType = QqBotMediaFileType.VIDEO,
                fileSizeBytes = 15_000_000L,
                fileName = "demo.mp4",
                md5 = "md5-value",
                sha1 = "sha1-value",
                md5Prefix = "md5-prefix",
            ),
        )

        assertEquals("upload_1", ticket.uploadId)
        assertEquals(10_485_760L, ticket.blockSizeBytes)
        assertEquals(2, ticket.parts.size)
        assertEquals(2, ticket.concurrency)
        val sent = transport.posts.last()
        assertEquals("https://api.bot.qq.com/v2/users/user-openid/upload_prepare", sent.url)
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals(2, body.getValue("file_type").jsonPrimitive.int)
        assertEquals("15000000", body.getValue("file_size").jsonPrimitive.content)
        assertEquals("demo.mp4", body.getValue("file_name").jsonPrimitive.content)
        assertEquals("md5-value", body.getValue("md5").jsonPrimitive.content)
        assertEquals("sha1-value", body.getValue("sha1").jsonPrimitive.content)
        assertEquals("md5-prefix", body.getValue("md5_10m").jsonPrimitive.content)
    }

    @Test
    fun `分片上传分片 PUT 到预签名 URL 且不携带鉴权头`() {
        val transport = FakeQqBotHttpTransport(
            putResponses = ArrayDeque(listOf(QqBotHttpResponse(200, ""))),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        client.putUploadPart("https://cos.example.com/p1", byteArrayOf(1, 2, 3))

        val put = transport.puts.single()
        assertEquals("https://cos.example.com/p1", put.url)
        assertEquals(listOf<Byte>(1, 2, 3), put.bytes.toList())
    }

    @Test
    fun `分片上传分片完成提交 upload_id 与分片信息`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, ""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        client.finishUploadPart(
            scope = QqBotOpenApiScope.Group("group-openid"),
            request = QqBotUploadPartFinishRequest(
                uploadId = "upload_1",
                partIndex = 0,
                blockSizeBytes = 1_048_576L,
                md5 = "part-md5",
            ),
        )

        val sent = transport.posts.last()
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/upload_part_finish", sent.url)
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("upload_1", body.getValue("upload_id").jsonPrimitive.content)
        assertEquals(0, body.getValue("part_index").jsonPrimitive.int)
        assertEquals("1048576", body.getValue("block_size").jsonPrimitive.content)
        assertEquals("part-md5", body.getValue("md5").jsonPrimitive.content)
    }

    @Test
    fun `群聊富媒体消息发送提交 media file_info`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"id":"media-send-1"}"""),
                ),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        val receipt = client.sendMessage(
            scope = QqBotOpenApiScope.Group("group-openid"),
            request = QqBotSendRequest(
                content = "",
                msgType = QQBOT_MEDIA_MSG_TYPE,
                mediaFileInfo = " file-info-1 ",
                replyToMessageId = "source-msg",
                msgSeq = 2,
            ),
        )

        assertEquals("media-send-1", receipt.messageId)
        val body = Json.parseToJsonElement(transport.posts.last().body).jsonObject
        assertEquals(" ", body.getValue("content").jsonPrimitive.content)
        assertEquals(7, body.getValue("msg_type").jsonPrimitive.int)
        assertEquals("file-info-1", body.getValue("media").jsonObject.getValue("file_info").jsonPrimitive.content)
        assertEquals("source-msg", body.getValue("msg_id").jsonPrimitive.content)
        assertEquals(2, body.getValue("msg_seq").jsonPrimitive.int)
    }

    @Test
    fun `单聊消息撤回提交官方 DELETE 请求`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            deleteResponses = ArrayDeque(
                listOf(QqBotHttpResponse(204, "")),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        client.recallMessage(scope = QqBotOpenApiScope.User("user/open id"), messageId = "msg/1")

        val deleted = transport.deletes.single()
        assertEquals("https://api.bot.qq.com/v2/users/user%2Fopen%20id/messages/msg%2F1", deleted.url)
        assertEquals("QQBot token-a", deleted.headers.getValue("Authorization"))
    }

    @Test
    fun `群聊消息撤回提交官方 DELETE 请求`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            deleteResponses = ArrayDeque(
                listOf(QqBotHttpResponse(204, "")),
            ),
        )
        val client = QqBotOpenApiClient(
            config = QqBotConfig(appId = "app-id", appSecret = "secret"),
            transport = transport,
        )

        client.recallMessage(scope = QqBotOpenApiScope.Group("group-openid"), messageId = "group-msg-1")

        val deleted = transport.deletes.single()
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/messages/group-msg-1", deleted.url)
        assertEquals("QQBot token-a", deleted.headers.getValue("Authorization"))
    }
}

internal data class RecordedPost(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class RecordedGet(
    val url: String,
    val headers: Map<String, String>,
)

internal data class RecordedDelete(
    val url: String,
    val headers: Map<String, String>,
)

internal data class RecordedPut(
    val url: String,
    val bytes: ByteArray,
)

internal class FakeQqBotHttpTransport(
    private val postResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
    private val getResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
    private val deleteResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
    private val putResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
) : QqBotHttpTransport {
    val posts: MutableList<RecordedPost> = mutableListOf()
    val gets: MutableList<RecordedGet> = mutableListOf()
    val deletes: MutableList<RecordedDelete> = mutableListOf()
    val puts: MutableList<RecordedPut> = mutableListOf()

    override fun postJson(url: String, headers: Map<String, String>, body: String): QqBotHttpResponse {
        posts += RecordedPost(url = url, headers = headers, body = body)
        return postResponses.removeFirst()
    }

    override fun get(url: String, headers: Map<String, String>): QqBotHttpResponse {
        gets += RecordedGet(url = url, headers = headers)
        return getResponses.removeFirst()
    }

    override fun delete(url: String, headers: Map<String, String>): QqBotHttpResponse {
        deletes += RecordedDelete(url = url, headers = headers)
        return deleteResponses.removeFirst()
    }

    override fun putBytes(url: String, bytes: ByteArray): QqBotHttpResponse {
        puts += RecordedPut(url = url, bytes = bytes)
        return putResponses.removeFirst()
    }
}
