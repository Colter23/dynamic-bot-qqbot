package top.colter.dynamic.qqbot

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class JavaNetQqBotWebSocketGatewayTest {
    @Test
    fun `连接时获取接入点并打开 websocket`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)

        gateway.connect { }

        assertEquals("wss://api.sgroup.qq.com/websocket", connector.connectedUrl)
        assertEquals(
            QqBotRuntimeAccount(
                accountId = "app-id",
                name = "QQ 官方机器人 app-id",
                state = MessageSinkRouteState.UNAVAILABLE,
            ),
            gateway.availableAccounts().single(),
        )
    }

    @Test
    fun `收到 hello 后发送 identify 并安排心跳`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, heartbeatScheduler = heartbeatScheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")

        val identifyPayload = connector.session.sentTexts.single()
        val identifyRoot = Json.parseToJsonElement(identifyPayload).jsonObject
        val identifyData = identifyRoot.getValue("d").jsonObject

        assertEquals(QqBotOpcode.IDENTIFY.code, identifyRoot.getValue("op").jsonPrimitive.int)
        assertEquals("QQBot token-a", identifyData.getValue("token").jsonPrimitive.content)
        assertEquals(1 shl 25, identifyData.getValue("intents").jsonPrimitive.int)
        assertEquals(listOf(0, 1), identifyData.getValue("shard").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(45_000, heartbeatScheduler.scheduled.single().initialDelayMillis)
        assertEquals(45_000, heartbeatScheduler.scheduled.single().intervalMillis)
        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)
    }

    @Test
    fun `心跳使用最新的 dispatch 序号`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, heartbeatScheduler = heartbeatScheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":123,"t":"C2C_MESSAGE_CREATE","d":{"id":"msg-1"}}""")
        heartbeatScheduler.scheduled.single().task()

        val heartbeatPayload = connector.session.sentTexts.last()
        val heartbeatRoot = Json.parseToJsonElement(heartbeatPayload).jsonObject

        assertEquals(QqBotOpcode.HEARTBEAT.code, heartbeatRoot.getValue("op").jsonPrimitive.int)
        assertEquals(123, heartbeatRoot.getValue("d").jsonPrimitive.int)
    }

    @Test
    fun `dispatch 负载会转发给入站处理器`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)
        val events = mutableListOf<QqBotIncomingEvent>()

        gateway.connect { events += it }
        connector.listener.onText("""{"op":0,"s":7,"t":"GROUP_AT_MESSAGE_CREATE","d":{"id":"msg-1"}}""")

        assertEquals(1, events.size)
        assertEquals("GROUP_AT_MESSAGE_CREATE", events.single().type)
    }

    @Test
    fun `关闭时取消心跳并关闭 websocket`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, heartbeatScheduler = heartbeatScheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        gateway.close()

        assertEquals(true, heartbeatScheduler.scheduled.single().closed)
        assertEquals(1000, connector.session.closedStatusCode)
        assertEquals("插件停止", connector.session.closedReason)
        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
    }

    @Test
    fun `远端关闭后标记账号不可用`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)

        connector.listener.onClosed(4009, "expired")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
    }

    @Test
    fun `远端关闭后安排重连并打开新 websocket`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val transport = reconnectTransport()
        val gateway = testGateway(
            connector = connector,
            heartbeatScheduler = heartbeatScheduler,
            reconnectScheduler = reconnectScheduler,
            transport = transport,
        )

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onClosed(4009, "expired")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
        assertEquals(true, heartbeatScheduler.scheduled.single().closed)
        assertEquals(1_000L, reconnectScheduler.scheduled.single().delayMillis)

        reconnectScheduler.scheduled.single().task()
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":30000}}""")

        assertEquals(
            listOf("wss://api.sgroup.qq.com/websocket", "wss://api.sgroup.qq.com/websocket-reconnect"),
            connector.connectedUrls,
        )
        assertEquals(2, connector.sessions.size)
        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)
        assertEquals(30_000, heartbeatScheduler.scheduled.last().intervalMillis)
        val identifyRoot = Json.parseToJsonElement(connector.session.sentTexts.single()).jsonObject
        assertEquals(QqBotOpcode.IDENTIFY.code, identifyRoot.getValue("op").jsonPrimitive.int)
    }

    @Test
    fun `网关重连 opcode 会关闭当前 websocket 并安排重连`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val gateway = testGateway(
            connector = connector,
            heartbeatScheduler = heartbeatScheduler,
            reconnectScheduler = reconnectScheduler,
        )

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":7,"d":null}""")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
        assertEquals(true, heartbeatScheduler.scheduled.single().closed)
        assertEquals(4000, connector.session.closedStatusCode)
        assertEquals("准备重连", connector.session.closedReason)
        assertEquals(1_000L, reconnectScheduler.scheduled.single().delayMillis)
    }

    @Test
    fun `关闭时取消等待中的重连`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val gateway = testGateway(connector = connector, reconnectScheduler = reconnectScheduler)

        gateway.connect { }
        connector.listener.onClosed(4009, "expired")
        gateway.close()

        assertEquals(true, reconnectScheduler.scheduled.single().closed)
    }

    @Test
    fun `禁用重连时远端关闭不会重连`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val gateway = testGateway(
            connector = connector,
            reconnectScheduler = reconnectScheduler,
            reconnect = false,
        )

        gateway.connect { }
        connector.listener.onClosed(4009, "expired")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
        assertEquals(0, reconnectScheduler.scheduled.size)
    }

    @Test
    fun `就绪网关通过 openapi 发送单聊和群聊消息`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"id":"user-send-1"}"""),
                    QqBotHttpResponse(200, """{"id":"group-send-1"}"""),
                ),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.sgroup.qq.com/websocket","shards":1}""")),
            ),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")

        val userReceipt = gateway.sendUserMessage(
            accountId = "app-id",
            openid = "user-openid",
            request = QqBotSendRequest(content = "hello"),
        )
        val groupReceipt = gateway.sendGroupMessage(
            accountId = "app-id",
            groupOpenid = "group-openid",
            request = QqBotSendRequest(content = "group hello"),
        )

        assertEquals("user-send-1", userReceipt.messageId)
        assertEquals("group-send-1", groupReceipt.messageId)
        assertEquals("https://api.sgroup.qq.com/v2/users/user-openid/messages", transport.posts[1].url)
        assertEquals("https://api.sgroup.qq.com/v2/groups/group-openid/messages", transport.posts[2].url)
    }

    private fun testGateway(
        connector: FakeQqBotWebSocketConnector = FakeQqBotWebSocketConnector(),
        heartbeatScheduler: FakeQqBotHeartbeatScheduler = FakeQqBotHeartbeatScheduler(),
        reconnectScheduler: FakeQqBotReconnectScheduler = FakeQqBotReconnectScheduler(),
        reconnect: Boolean = true,
        transport: FakeQqBotHttpTransport = defaultTransport(),
    ): JavaNetQqBotWebSocketGateway {
        val config = QqBotConfig(
            enabled = true,
            appId = "app-id",
            appSecret = "secret",
            reconnect = reconnect,
        )
        return JavaNetQqBotWebSocketGateway(
            config = config,
            openApiClient = QqBotOpenApiClient(config = config, transport = transport),
            connector = connector,
            heartbeatScheduler = heartbeatScheduler,
            reconnectScheduler = reconnectScheduler,
        )
    }

    private fun defaultTransport(): FakeQqBotHttpTransport {
        return FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.sgroup.qq.com/websocket","shards":1}""")),
            ),
        )
    }

    private fun reconnectTransport(): FakeQqBotHttpTransport {
        return FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"url":"wss://api.sgroup.qq.com/websocket","shards":1}"""),
                    QqBotHttpResponse(200, """{"url":"wss://api.sgroup.qq.com/websocket-reconnect","shards":1}"""),
                ),
            ),
        )
    }

    private class FakeQqBotWebSocketConnector : QqBotWebSocketConnector {
        val sessions: MutableList<FakeQqBotWebSocketSession> = mutableListOf()
        val listeners: MutableList<QqBotWebSocketListener> = mutableListOf()
        val connectedUrls: MutableList<String> = mutableListOf()
        val session: FakeQqBotWebSocketSession
            get() = sessions.last()
        val listener: QqBotWebSocketListener
            get() = listeners.last()
        val connectedUrl: String?
            get() = connectedUrls.lastOrNull()

        override fun connect(url: String, listener: QqBotWebSocketListener): QqBotWebSocketSession {
            val session = FakeQqBotWebSocketSession()
            sessions += session
            listeners += listener
            connectedUrls += url
            listener.onOpen()
            return session
        }
    }

    private class FakeQqBotWebSocketSession : QqBotWebSocketSession {
        val sentTexts: MutableList<String> = mutableListOf()
        var closedStatusCode: Int? = null
        var closedReason: String? = null

        override fun sendText(payload: String) {
            sentTexts += payload
        }

        override fun close(statusCode: Int, reason: String) {
            closedStatusCode = statusCode
            closedReason = reason
        }
    }

    private data class ScheduledHeartbeat(
        val initialDelayMillis: Long,
        val intervalMillis: Long,
        val task: () -> Unit,
        var closed: Boolean = false,
    )

    private class FakeQqBotHeartbeatScheduler : QqBotHeartbeatScheduler {
        val scheduled: MutableList<ScheduledHeartbeat> = mutableListOf()

        override fun scheduleAtFixedRate(
            initialDelayMillis: Long,
            intervalMillis: Long,
            task: () -> Unit,
        ): AutoCloseable {
            val scheduledTask = ScheduledHeartbeat(
                initialDelayMillis = initialDelayMillis,
                intervalMillis = intervalMillis,
                task = task,
            )
            scheduled += scheduledTask
            return AutoCloseable { scheduledTask.closed = true }
        }
    }

    private data class ScheduledReconnect(
        val delayMillis: Long,
        val task: () -> Unit,
        var closed: Boolean = false,
    )

    private class FakeQqBotReconnectScheduler : QqBotReconnectScheduler {
        val scheduled: MutableList<ScheduledReconnect> = mutableListOf()

        override fun schedule(delayMillis: Long, task: () -> Unit): AutoCloseable {
            val scheduledTask = ScheduledReconnect(
                delayMillis = delayMillis,
                task = task,
            )
            scheduled += scheduledTask
            return AutoCloseable { scheduledTask.closed = true }
        }
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
