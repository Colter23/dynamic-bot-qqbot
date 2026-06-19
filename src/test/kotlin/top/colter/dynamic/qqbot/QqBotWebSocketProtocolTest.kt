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
    fun `intent mask matches official bit definitions`() {
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
    fun `identify payload follows official websocket shape`() {
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
        assertEquals("dynamic-bot", data.getValue("properties").jsonObject.getValue("browser").jsonPrimitive.content)
    }

    @Test
    fun `heartbeat payload carries latest sequence or null`() {
        val withSequence = Json.parseToJsonElement(QqBotWebSocketProtocol.heartbeatPayload(42)).jsonObject
        val withoutSequence = Json.parseToJsonElement(QqBotWebSocketProtocol.heartbeatPayload(null)).jsonObject

        assertEquals(QqBotOpcode.HEARTBEAT.code, withSequence.getValue("op").jsonPrimitive.int)
        assertEquals(42, withSequence.getValue("d").jsonPrimitive.long)
        assertEquals(QqBotOpcode.HEARTBEAT.code, withoutSequence.getValue("op").jsonPrimitive.int)
        assertNull(withoutSequence.getValue("d").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `hello payload exposes heartbeat interval`() {
        val hello = QqBotWebSocketProtocol.parseHello(
            """{"op":10,"d":{"heartbeat_interval":45000}}""",
        )

        assertEquals(45_000, hello.heartbeatIntervalMillis)
    }

    @Test
    fun `dispatch payload exposes type sequence and data`() {
        val dispatch = QqBotWebSocketProtocol.parseDispatch(
            """{"op":0,"s":123,"t":"C2C_MESSAGE_CREATE","d":{"id":"msg-1","content":"hi"}}""",
        )

        assertEquals("C2C_MESSAGE_CREATE", dispatch.type)
        assertEquals(123, dispatch.sequence)
        assertEquals("hi", dispatch.data.jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `dispatch payload can become gateway incoming event`() {
        val event = QqBotWebSocketProtocol.incomingEventOrNull(
            """{"op":0,"s":123,"t":"GROUP_AT_MESSAGE_CREATE","d":{"id":"msg-1"}}""",
        )

        assertNotNull(event)
        assertEquals("GROUP_AT_MESSAGE_CREATE", event.type)
    }

    @Test
    fun `non dispatch payload is ignored by incoming event conversion`() {
        assertNull(QqBotWebSocketProtocol.incomingEventOrNull("""{"op":11,"d":null}"""))
    }

    @Test
    fun `invalid intent is rejected during config validation`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(QqBotConfig(intents = listOf("GUILD_MESSAGES")))
        }
    }
}
