package top.colter.dynamic.qqbot

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal enum class QqBotOpcode(val code: Int) {
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    RESUME(6),
    RECONNECT(7),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    HTTP_CALLBACK_ACK(12),
    HTTP_CALLBACK_VERIFY(13),
    ;

    companion object {
        fun fromCode(code: Int): QqBotOpcode? = entries.firstOrNull { it.code == code }
    }
}

internal data class QqBotWebSocketFrame(
    val opcode: QqBotOpcode,
    val sequence: Long?,
    val type: String?,
    val data: JsonElement?,
    val rawPayload: String,
)

internal data class QqBotHello(
    val heartbeatIntervalMillis: Long,
)

internal data class QqBotDispatchEvent(
    val type: String,
    val sequence: Long?,
    val data: JsonElement,
    val rawPayload: String,
)

internal data class QqBotReadySession(
    val sessionId: String,
    val sequence: Long?,
)

internal object QqBotWebSocketProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun identifyPayload(
        accessToken: String,
        intentMask: Int,
        shard: Pair<Int, Int> = 0 to 1,
    ): String {
        val trimmedToken = accessToken.trim()
        require(trimmedToken.isNotBlank()) { "QQ 官方机器人访问令牌不能为空" }
        require(intentMask > 0) { "QQ 官方机器人 WebSocket 事件订阅掩码必须大于 0" }

        return json.encodeToString(
            buildJsonObject {
                put("op", JsonPrimitive(QqBotOpcode.IDENTIFY.code))
                put(
                    "d",
                    buildJsonObject {
                        put("token", JsonPrimitive("QQBot $trimmedToken"))
                        put("intents", JsonPrimitive(intentMask))
                        put(
                            "shard",
                            buildJsonArray {
                                add(JsonPrimitive(shard.first))
                                add(JsonPrimitive(shard.second))
                            },
                        )
                        put(
                            "properties",
                            buildJsonObject {
                                put("\$os", JsonPrimitive(systemProperty("os.name")))
                                put("\$browser", JsonPrimitive("dynamic-bot"))
                                put("\$device", JsonPrimitive("dynamic-bot"))
                            },
                        )
                    },
                )
            },
        )
    }

    fun heartbeatPayload(sequence: Long?): String {
        return json.encodeToString(
            buildJsonObject {
                put("op", JsonPrimitive(QqBotOpcode.HEARTBEAT.code))
                put("d", sequence?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    fun resumePayload(
        accessToken: String,
        sessionId: String,
        sequence: Long,
    ): String {
        val trimmedToken = accessToken.trim()
        val trimmedSessionId = sessionId.trim()
        require(trimmedToken.isNotBlank()) { "QQ 官方机器人访问令牌不能为空" }
        require(trimmedSessionId.isNotBlank()) { "QQ 官方机器人 session_id 不能为空" }

        return json.encodeToString(
            buildJsonObject {
                put("op", JsonPrimitive(QqBotOpcode.RESUME.code))
                put(
                    "d",
                    buildJsonObject {
                        put("token", JsonPrimitive("QQBot $trimmedToken"))
                        put("session_id", JsonPrimitive(trimmedSessionId))
                        put("seq", JsonPrimitive(sequence))
                    },
                )
            },
        )
    }

    fun parseFrame(payload: String): QqBotWebSocketFrame {
        val root = json.parseToJsonElement(payload).jsonObject
        val opcodeCode = root["op"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("QQ 官方机器人 WebSocket 包缺少数字 op")
        val opcode = QqBotOpcode.fromCode(opcodeCode)
            ?: throw IllegalArgumentException("QQ 官方机器人 WebSocket op 不受支持：$opcodeCode")
        return QqBotWebSocketFrame(
            opcode = opcode,
            sequence = root["s"]?.jsonPrimitive?.longOrNull,
            type = root["t"]?.jsonPrimitive?.contentOrNull,
            data = root["d"],
            rawPayload = payload,
        )
    }

    fun parseHello(payload: String): QqBotHello {
        val frame = parseFrame(payload)
        require(frame.opcode == QqBotOpcode.HELLO) { "QQ 官方机器人 WebSocket 包不是 Hello：op=${frame.opcode.code}" }
        val heartbeatInterval = frame.data
            ?.jsonObject
            ?.get("heartbeat_interval")
            ?.jsonPrimitive
            ?.longOrNull
            ?: throw IllegalArgumentException("QQ 官方机器人 Hello 包缺少 heartbeat_interval")
        return QqBotHello(heartbeatIntervalMillis = heartbeatInterval)
    }

    fun parseDispatch(payload: String): QqBotDispatchEvent {
        val frame = parseFrame(payload)
        require(frame.opcode == QqBotOpcode.DISPATCH) { "QQ 官方机器人 WebSocket 包不是 Dispatch：op=${frame.opcode.code}" }
        val type = frame.type?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("QQ 官方机器人 Dispatch 包缺少事件类型 t")
        return QqBotDispatchEvent(
            type = type,
            sequence = frame.sequence,
            data = frame.data ?: JsonNull,
            rawPayload = frame.rawPayload,
        )
    }

    fun parseReadySession(dispatch: QqBotDispatchEvent): QqBotReadySession {
        require(dispatch.type == QQBOT_READY_EVENT) { "QQ 官方机器人 Dispatch 包不是 READY：type=${dispatch.type}" }
        val sessionId = dispatch.data
            .jsonObject["session_id"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("QQ 官方机器人 READY 事件缺少 session_id")
        return QqBotReadySession(
            sessionId = sessionId,
            sequence = dispatch.sequence,
        )
    }

    fun incomingEventOrNull(payload: String, botAccountId: String? = null): QqBotIncomingEvent? {
        val frame = parseFrame(payload)
        if (frame.opcode != QqBotOpcode.DISPATCH) return null
        val type = frame.type?.takeIf { it.isNotBlank() } ?: return null
        return QqBotIncomingEvent(type = type, rawPayload = frame.rawPayload, botAccountId = botAccountId)
    }

    private fun systemProperty(name: String): String {
        return runCatching { System.getProperty(name).orEmpty() }
            .getOrDefault("")
            .ifBlank { "unknown" }
    }
}

internal const val QQBOT_READY_EVENT: String = "READY"
internal const val QQBOT_RESUMED_EVENT: String = "RESUMED"
