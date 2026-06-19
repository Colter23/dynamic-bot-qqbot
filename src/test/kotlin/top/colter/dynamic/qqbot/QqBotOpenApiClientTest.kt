package top.colter.dynamic.qqbot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QqBotOpenApiClientTest {
    @Test
    fun `访问令牌请求符合官方应用凭据格式`() {
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
        assertEquals("https://bots.qq.com/app/getAppAccessToken", transport.posts.single().url)
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
                              "url":"wss://api.sgroup.qq.com/websocket",
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

        assertEquals("wss://api.sgroup.qq.com/websocket", endpoint.url)
        assertEquals(1, endpoint.shards)
        assertEquals(1000, endpoint.sessionStartLimit?.total)
        assertEquals(999, endpoint.sessionStartLimit?.remaining)
        assertEquals(86_400_000L, endpoint.sessionStartLimit?.resetAfterMillis)
        assertEquals(1, endpoint.sessionStartLimit?.maxConcurrency)
        assertEquals("https://api.sgroup.qq.com/gateway/bot", transport.gets.single().url)
        assertEquals("QQBot token-a", transport.gets.single().headers.getValue("Authorization"))
    }

    @Test
    fun `OpenAPI 错误包含状态码业务码和消息`() {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(401, """{"code":11243,"message":"token invalid"}""")),
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

        val receipt = client.sendUserMessage(
            openid = "user/open id",
            request = QqBotSendRequest(
                content = "hello",
                replyToMessageId = "source-msg",
                msgSeq = 2,
            ),
        )

        assertEquals("send-1", receipt.messageId)
        val sent = transport.posts.last()
        assertEquals("https://api.sgroup.qq.com/v2/users/user%2Fopen%20id/messages", sent.url)
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

        val receipt = client.sendGroupMessage(
            groupOpenid = "group-openid",
            request = QqBotSendRequest(content = "group hello"),
        )

        assertEquals("group-send-1", receipt.messageId)
        val sent = transport.posts.last()
        assertEquals("https://api.sgroup.qq.com/v2/groups/group-openid/messages", sent.url)
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("group hello", body.getValue("content").jsonPrimitive.content)
        assertEquals(false, "msg_id" in body)
        assertEquals(false, "msg_seq" in body)
    }

    @Test
    fun `单聊富媒体资源登记提交官方文件负载`() {
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

        val resource = client.prepareUserMedia(
            openid = "user/open id",
            request = QqBotMediaPrepareRequest(
                fileType = QqBotMediaFileType.IMAGE,
                url = " https://example.com/a.png ",
            ),
        )

        assertEquals("file-info-1", resource.fileInfo)
        assertEquals("uuid-1", resource.fileUuid)
        assertEquals(3600L, resource.ttlSeconds)
        val sent = transport.posts.last()
        assertEquals("https://api.sgroup.qq.com/v2/users/user%2Fopen%20id/files", sent.url)
        assertEquals("QQBot token-a", sent.headers.getValue("Authorization"))
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals(1, body.getValue("file_type").jsonPrimitive.int)
        assertEquals("https://example.com/a.png", body.getValue("url").jsonPrimitive.content)
        assertEquals(false, body.getValue("srv_send_msg").jsonPrimitive.boolean)
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

        val receipt = client.sendGroupMessage(
            groupOpenid = "group-openid",
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

        client.recallUserMessage(openid = "user/open id", messageId = "msg/1")

        val deleted = transport.deletes.single()
        assertEquals("https://api.sgroup.qq.com/v2/users/user%2Fopen%20id/messages/msg%2F1", deleted.url)
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

        client.recallGroupMessage(groupOpenid = "group-openid", messageId = "group-msg-1")

        val deleted = transport.deletes.single()
        assertEquals("https://api.sgroup.qq.com/v2/groups/group-openid/messages/group-msg-1", deleted.url)
        assertEquals("QQBot token-a", deleted.headers.getValue("Authorization"))
    }

    private data class RecordedPost(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class RecordedGet(
        val url: String,
        val headers: Map<String, String>,
    )

    private data class RecordedDelete(
        val url: String,
        val headers: Map<String, String>,
    )

    private class FakeQqBotHttpTransport(
        private val postResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
        private val getResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
        private val deleteResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
    ) : QqBotHttpTransport {
        val posts: MutableList<RecordedPost> = mutableListOf()
        val gets: MutableList<RecordedGet> = mutableListOf()
        val deletes: MutableList<RecordedDelete> = mutableListOf()

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
    }
}
