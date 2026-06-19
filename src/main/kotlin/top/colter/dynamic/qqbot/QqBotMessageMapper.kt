package top.colter.dynamic.qqbot

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

internal data class QqBotSendRequest(
    val content: String,
    val msgType: Int = 0,
    val replyToMessageId: String? = null,
    val msgSeq: Int = 1,
)

internal data class QqBotSendReceipt(
    val messageId: String,
    val rawPayload: String = "",
)

internal object QqBotMessageMapper {
    fun toTextRequest(
        batches: List<MessageBatch>,
        replyToMessageId: String? = null,
    ): QqBotSendRequest {
        val content = batches
            .flatMap { it.content }
            .joinToString(separator = "\n") { it.fallbackText }
            .trim()
        return QqBotSendRequest(
            content = content.ifBlank { QQBOT_EMPTY_MESSAGE_TEXT },
            msgType = 0,
            replyToMessageId = replyToMessageId?.trim()?.takeIf { it.isNotBlank() }
                ?: batches.firstReplyMessageId(),
        )
    }

    private fun List<MessageBatch>.firstReplyMessageId(): String? {
        return asSequence()
            .flatMap { it.content.asSequence() }
            .filterIsInstance<MessageContent.Reply>()
            .mapNotNull { it.messageId.trim().takeIf(String::isNotBlank) }
            .firstOrNull()
    }
}

internal object QqBotIncomingMessageMapper {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun toIncomingMessage(event: QqBotIncomingEvent): IncomingMessage? {
        val dispatch = runCatching { QqBotWebSocketProtocol.parseDispatch(event.rawPayload) }.getOrNull() ?: return null
        val data = dispatch.data.jsonObject
        return when (event.type) {
            QQBOT_C2C_MESSAGE_CREATE -> data.toC2cIncoming(event)
            QQBOT_GROUP_AT_MESSAGE_CREATE -> data.toGroupIncoming(event)
            else -> null
        }
    }

    private fun JsonObject.toC2cIncoming(event: QqBotIncomingEvent): IncomingMessage? {
        val senderId = jsonString("author", "user_openid") ?: jsonString("author", "openid") ?: return null
        val messageId = jsonString("id")
        val content = jsonString("content").orEmpty()
        val attachments = attachments()
        val segments = buildSegments(content, attachments)
        return IncomingMessage(
            platformId = QQBOT_PLATFORM_ID,
            target = TargetAddress.of(
                platformId = QQBOT_PLATFORM_ID.value,
                kind = TargetKind.USER,
                externalId = senderId,
                accountId = event.botAccountId,
            ),
            senderId = senderId,
            botAccountId = event.botAccountId,
            messageId = messageId.orEmpty(),
            timestamp = timestampSeconds(),
            text = content,
            segments = segments,
            rawFormat = QQBOT_RAW_FORMAT,
            rawPayload = event.rawPayload,
        )
    }

    private fun JsonObject.toGroupIncoming(event: QqBotIncomingEvent): IncomingMessage? {
        val groupOpenid = jsonString("group_openid") ?: return null
        val senderId = jsonString("author", "member_openid")
            ?: jsonString("author", "group_member_openid")
            ?: return null
        val messageId = jsonString("id")
        val content = jsonString("content").orEmpty()
        val attachments = attachments()
        val segments = buildSegments(content, attachments)
        return IncomingMessage(
            platformId = QQBOT_PLATFORM_ID,
            target = TargetAddress.of(
                platformId = QQBOT_PLATFORM_ID.value,
                kind = TargetKind.GROUP,
                externalId = groupOpenid,
                accountId = event.botAccountId,
            ),
            senderId = senderId,
            botAccountId = event.botAccountId,
            messageId = messageId.orEmpty(),
            timestamp = timestampSeconds(),
            text = content,
            segments = segments,
            rawFormat = QQBOT_RAW_FORMAT,
            rawPayload = event.rawPayload,
            mentions = event.botAccountId?.let { setOf(it) }.orEmpty(),
        )
    }

    private fun buildSegments(content: String, attachments: List<QqBotIncomingAttachment>): List<IncomingMessageSegment> {
        return buildList {
            if (content.isNotEmpty()) {
                add(IncomingMessageSegment.Text(content))
            }
            attachments.forEach { attachment ->
                add(attachment.toSegment())
            }
        }
    }

    private fun QqBotIncomingAttachment.toSegment(): IncomingMessageSegment {
        return when {
            contentType.startsWith("image/") -> IncomingMessageSegment.Image(
                file = filename,
                url = url,
                rawPayload = rawPayload,
            )
            contentType.startsWith("video/") -> IncomingMessageSegment.Video(
                file = filename,
                url = url,
                rawPayload = rawPayload,
            )
            contentType.startsWith("audio/") || contentType == "voice" -> IncomingMessageSegment.Audio(
                file = filename,
                url = url,
                rawPayload = rawPayload,
            )
            else -> IncomingMessageSegment.Unknown(
                segmentType = contentType.ifBlank { "attachment" },
                rawPayload = rawPayload,
            )
        }
    }

    private fun JsonObject.attachments(): List<QqBotIncomingAttachment> {
        return get("attachments")
            ?.jsonArray
            ?.mapNotNull { element ->
                val item = element.jsonObject
                QqBotIncomingAttachment(
                    contentType = item.jsonString("content_type").orEmpty(),
                    filename = item.jsonString("filename").orEmpty(),
                    url = item.jsonString("url"),
                    rawPayload = json.encodeToString(JsonElement.serializer(), element),
                )
            }
            .orEmpty()
    }

    private fun JsonObject.timestampSeconds(): Long {
        val raw = jsonString("timestamp") ?: return 0L
        return runCatching { Instant.parse(raw).epochSecond }.getOrDefault(0L)
    }

    private fun JsonObject.jsonString(name: String): String? {
        return get(name)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.jsonString(parent: String, name: String): String? {
        return get(parent)
            ?.jsonObject
            ?.jsonString(name)
    }
}

private data class QqBotIncomingAttachment(
    val contentType: String,
    val filename: String,
    val url: String?,
    val rawPayload: String,
)

internal const val QQBOT_RAW_FORMAT: String = "qqbot-api-v2-dispatch-json"
internal const val QQBOT_C2C_MESSAGE_CREATE: String = "C2C_MESSAGE_CREATE"
internal const val QQBOT_GROUP_AT_MESSAGE_CREATE: String = "GROUP_AT_MESSAGE_CREATE"
private const val QQBOT_EMPTY_MESSAGE_TEXT: String = "（空消息）"
