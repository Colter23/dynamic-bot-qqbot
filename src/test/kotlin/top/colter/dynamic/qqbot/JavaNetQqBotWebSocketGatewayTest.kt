package top.colter.dynamic.qqbot

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class JavaNetQqBotWebSocketGatewayTest {
    @Test
    fun `connect fetches endpoint and opens websocket`() = runTest {
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
    fun `hello sends identify and schedules heartbeat`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val scheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, scheduler = scheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")

        val identifyPayload = connector.session.sentTexts.single()
        val identifyRoot = Json.parseToJsonElement(identifyPayload).jsonObject
        val identifyData = identifyRoot.getValue("d").jsonObject

        assertEquals(QqBotOpcode.IDENTIFY.code, identifyRoot.getValue("op").jsonPrimitive.int)
        assertEquals("QQBot token-a", identifyData.getValue("token").jsonPrimitive.content)
        assertEquals(1 shl 25, identifyData.getValue("intents").jsonPrimitive.int)
        assertEquals(listOf(0, 1), identifyData.getValue("shard").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(45_000, scheduler.scheduled.single().initialDelayMillis)
        assertEquals(45_000, scheduler.scheduled.single().intervalMillis)
        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)
    }

    @Test
    fun `heartbeat uses latest dispatch sequence`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val scheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, scheduler = scheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":123,"t":"C2C_MESSAGE_CREATE","d":{"id":"msg-1"}}""")
        scheduler.scheduled.single().task()

        val heartbeatPayload = connector.session.sentTexts.last()
        val heartbeatRoot = Json.parseToJsonElement(heartbeatPayload).jsonObject

        assertEquals(QqBotOpcode.HEARTBEAT.code, heartbeatRoot.getValue("op").jsonPrimitive.int)
        assertEquals(123, heartbeatRoot.getValue("d").jsonPrimitive.int)
    }

    @Test
    fun `dispatch payload is forwarded to incoming handler`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)
        val events = mutableListOf<QqBotIncomingEvent>()

        gateway.connect { events += it }
        connector.listener.onText("""{"op":0,"s":7,"t":"GROUP_AT_MESSAGE_CREATE","d":{"id":"msg-1"}}""")

        assertEquals(1, events.size)
        assertEquals("GROUP_AT_MESSAGE_CREATE", events.single().type)
    }

    @Test
    fun `close cancels heartbeat and closes websocket`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val scheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, scheduler = scheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        gateway.close()

        assertEquals(true, scheduler.scheduled.single().closed)
        assertEquals(1000, connector.session.closedStatusCode)
        assertEquals("shutdown", connector.session.closedReason)
        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
    }

    @Test
    fun `remote close marks account unavailable`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)

        connector.listener.onClosed(4009, "expired")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
    }

    private fun testGateway(
        connector: FakeQqBotWebSocketConnector = FakeQqBotWebSocketConnector(),
        scheduler: FakeQqBotHeartbeatScheduler = FakeQqBotHeartbeatScheduler(),
    ): JavaNetQqBotWebSocketGateway {
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.sgroup.qq.com/websocket","shards":1}""")),
            ),
        )
        val config = QqBotConfig(
            enabled = true,
            appId = "app-id",
            appSecret = "secret",
        )
        return JavaNetQqBotWebSocketGateway(
            config = config,
            openApiClient = QqBotOpenApiClient(config = config, transport = transport),
            connector = connector,
            heartbeatScheduler = scheduler,
        )
    }

    private class FakeQqBotWebSocketConnector : QqBotWebSocketConnector {
        val session = FakeQqBotWebSocketSession()
        lateinit var listener: QqBotWebSocketListener
        var connectedUrl: String? = null

        override fun connect(url: String, listener: QqBotWebSocketListener): QqBotWebSocketSession {
            connectedUrl = url
            this.listener = listener
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

    private data class ScheduledTask(
        val initialDelayMillis: Long,
        val intervalMillis: Long,
        val task: () -> Unit,
        var closed: Boolean = false,
    )

    private class FakeQqBotHeartbeatScheduler : QqBotHeartbeatScheduler {
        val scheduled: MutableList<ScheduledTask> = mutableListOf()

        override fun scheduleAtFixedRate(
            initialDelayMillis: Long,
            intervalMillis: Long,
            task: () -> Unit,
        ): AutoCloseable {
            val scheduledTask = ScheduledTask(
                initialDelayMillis = initialDelayMillis,
                intervalMillis = intervalMillis,
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
