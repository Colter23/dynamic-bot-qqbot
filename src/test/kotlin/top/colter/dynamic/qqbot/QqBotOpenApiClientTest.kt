package top.colter.dynamic.qqbot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QqBotOpenApiClientTest {
    @Test
    fun `access token request follows official app credential shape`() {
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
    fun `access token is cached until refresh skew`() {
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
    fun `gateway bot endpoint uses QQBot authorization and parses limits`() {
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
    fun `openapi error includes status code business code and message`() {
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
    fun `gateway endpoint rejects missing url`() {
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

    private data class RecordedPost(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class RecordedGet(
        val url: String,
        val headers: Map<String, String>,
    )

    private class FakeQqBotHttpTransport(
        private val postResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
        private val getResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
    ) : QqBotHttpTransport {
        val posts: MutableList<RecordedPost> = mutableListOf()
        val gets: MutableList<RecordedGet> = mutableListOf()

        override fun postJson(url: String, headers: Map<String, String>, body: String): QqBotHttpResponse {
            posts += RecordedPost(url = url, headers = headers, body = body)
            return postResponses.removeFirst()
        }

        override fun get(url: String, headers: Map<String, String>): QqBotHttpResponse {
            gets += RecordedGet(url = url, headers = headers)
            return getResponses.removeFirst()
        }
    }
}
