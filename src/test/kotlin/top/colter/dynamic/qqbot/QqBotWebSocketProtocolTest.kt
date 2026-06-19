package top.colter.dynamic.qqbot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QqBotWebSocketProtocolTest {
    @Test
    fun `事件订阅掩码符合官方位定义`() {
        assertEquals(1 shl 25, QqBotConfig().enabledIntentMask())
        assertEquals(
            (1 shl 25) or (1 shl 26),
            QqBotConfig(
                intents = listOf(
                    QqBotIntent.GROUP_AND_C2C_EVENT.name,
                    QqBotIntent.INTERACTION.name,
                    QqBotIntent.GROUP_AND_C2C_EVENT.name,
                ),
            ).enabledIntentMask(),
        )
        assertEquals(
            (1 shl 25) or (1 shl 27),
            QqBotConfig(
                intents = listOf(
                    QqBotIntent.GROUP_AND_C2C_EVENT.name,
                    QqBotIntent.MESSAGE_AUDIT.name,
                ),
            ).enabledIntentMask(),
        )
    }

    @Test
    fun `Identify 负载符合官方 WebSocket 格式`() {
        val payload = QqBotWebSocketProtocol.identifyPayload(
            accessToken = " access-token ",
            intentMask = (1 shl 25) or (1 shl 26),
        )

        val root = Json.parseToJsonElement(payload).jsonObject
        val data = root.getValue("d").jsonObject

        assertEquals(QqBotOpcode.IDENTIFY.code, root.getValue("op").jsonPrimitive.int)
        assertEquals("QQBot access-token", data.getValue("token").jsonPrimitive.content)
        assertEquals((1 shl 25) or (1 shl 26), data.getValue("intents").jsonPrimitive.int)
        assertEquals(
            listOf(0, 1),
            data.getValue("shard").jsonArray.map { it.jsonPrimitive.int },
        )
        val properties = data.getValue("properties").jsonObject
        assertEquals("dynamic-bot", properties.getValue("\$browser").jsonPrimitive.content)
        assertEquals("dynamic-bot", properties.getValue("\$device").jsonPrimitive.content)
    }

    @Test
    fun `Resume 负载携带会话和最新序号`() {
        val payload = QqBotWebSocketProtocol.resumePayload(
            accessToken = " access-token ",
            sessionId = " session-a ",
            sequence = 123,
        )

        val root = Json.parseToJsonElement(payload).jsonObject
        val data = root.getValue("d").jsonObject

        assertEquals(QqBotOpcode.RESUME.code, root.getValue("op").jsonPrimitive.int)
        assertEquals("QQBot access-token", data.getValue("token").jsonPrimitive.content)
        assertEquals("session-a", data.getValue("session_id").jsonPrimitive.content)
        assertEquals(123, data.getValue("seq").jsonPrimitive.long)
    }

    @Test
    fun `心跳负载携带最新序号或 null`() {
        val withSequence = Json.parseToJsonElement(QqBotWebSocketProtocol.heartbeatPayload(42)).jsonObject
        val withoutSequence = Json.parseToJsonElement(QqBotWebSocketProtocol.heartbeatPayload(null)).jsonObject

        assertEquals(QqBotOpcode.HEARTBEAT.code, withSequence.getValue("op").jsonPrimitive.int)
        assertEquals(42, withSequence.getValue("d").jsonPrimitive.long)
        assertEquals(QqBotOpcode.HEARTBEAT.code, withoutSequence.getValue("op").jsonPrimitive.int)
        assertNull(withoutSequence.getValue("d").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `Hello 负载暴露心跳间隔`() {
        val hello = QqBotWebSocketProtocol.parseHello(
            """{"op":10,"d":{"heartbeat_interval":45000}}""",
        )

        assertEquals(45_000, hello.heartbeatIntervalMillis)
    }

    @Test
    fun `Dispatch 负载暴露类型序号和数据`() {
        val dispatch = QqBotWebSocketProtocol.parseDispatch(
            """{"op":0,"s":123,"t":"C2C_MESSAGE_CREATE","d":{"id":"msg-1","content":"hi"}}""",
        )

        assertEquals("C2C_MESSAGE_CREATE", dispatch.type)
        assertEquals(123, dispatch.sequence)
        assertEquals("hi", dispatch.data.jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `READY 事件暴露恢复会话信息`() {
        val dispatch = QqBotWebSocketProtocol.parseDispatch(
            """{"op":0,"s":123,"t":"READY","d":{"session_id":"session-a"}}""",
        )

        val ready = QqBotWebSocketProtocol.parseReadySession(dispatch)

        assertEquals("session-a", ready.sessionId)
        assertEquals(123, ready.sequence)
    }

    @Test
    fun `Dispatch 负载可以转换为网关入站事件`() {
        val event = QqBotWebSocketProtocol.incomingEventOrNull(
            """{"op":0,"s":123,"t":"GROUP_AT_MESSAGE_CREATE","d":{"id":"msg-1"}}""",
        )

        assertNotNull(event)
        assertEquals("GROUP_AT_MESSAGE_CREATE", event.type)
    }

    @Test
    fun `非 Dispatch 负载在入站事件转换时被忽略`() {
        assertNull(QqBotWebSocketProtocol.incomingEventOrNull("""{"op":11,"d":null}"""))
    }

    @Test
    fun `无效事件订阅在配置校验时被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(QqBotConfig(intents = listOf("GUILD_MESSAGES")))
        }
    }
}
