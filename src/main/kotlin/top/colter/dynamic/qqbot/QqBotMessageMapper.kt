package top.colter.dynamic.qqbot

import java.net.URI
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
    val mediaFileInfo: String? = null,
)

internal data class QqBotSendReceipt(
    val messageId: String,
    val rawPayload: String = "",
)

internal enum class QqBotMediaFileType(val code: Int, val label: String) {
    IMAGE(1, "图片"),
    VIDEO(2, "视频"),
    AUDIO(3, "语音"),
}

internal data class QqBotUrlMedia(
    val fileType: QqBotMediaFileType,
    val url: String,
)

internal sealed interface QqBotSendPlanItem {
    val request: QqBotSendRequest

    data class Text(
        override val request: QqBotSendRequest,
    ) : QqBotSendPlanItem

    data class Media(
        val media: QqBotUrlMedia,
        override val request: QqBotSendRequest,
    ) : QqBotSendPlanItem
}

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

    fun toSendPlan(
        batches: List<MessageBatch>,
        replyToMessageId: String? = null,
    ): List<QqBotSendPlanItem> {
        val normalizedReplyTo = replyToMessageId?.trim()?.takeIf { it.isNotBlank() }
            ?: batches.firstReplyMessageId()
        val items = mutableListOf<QqBotSendPlanItem>()
        val pendingText = mutableListOf<String>()
        var nextMsgSeq = 1

        fun nextRequest(content: String, msgType: Int): QqBotSendRequest {
            return QqBotSendRequest(
                content = content,
                msgType = msgType,
                replyToMessageId = normalizedReplyTo,
                msgSeq = nextMsgSeq++,
            )
        }

        fun addText(text: String) {
            text.trim().takeIf { it.isNotBlank() }?.let { pendingText += it }
        }

        fun flushText() {
            val content = pendingText.joinToString(separator = "\n").trim()
            pendingText.clear()
            if (content.isNotBlank()) {
                items += QqBotSendPlanItem.Text(nextRequest(content = content, msgType = QQBOT_TEXT_MSG_TYPE))
            }
        }

        fun addMedia(fileType: QqBotMediaFileType, uri: String, fallbackText: String) {
            val url = uri.toHttpUrlOrNull()
            if (url == null) {
                addText(fallbackText.ifBlank { "（暂不支持发送${fileType.label}，请使用 HTTP(S) URL）" })
                return
            }

            flushText()
            items += QqBotSendPlanItem.Media(
                media = QqBotUrlMedia(fileType = fileType, url = url),
                request = nextRequest(
                    content = fallbackText.trim().ifBlank { " " },
                    msgType = QQBOT_MEDIA_MSG_TYPE,
                ),
            )
        }

        batches.asSequence()
            .flatMap { it.content.asSequence() }
            .forEach { content ->
                when (content) {
                    is MessageContent.Image -> addMedia(
                        fileType = QqBotMediaFileType.IMAGE,
                        uri = content.image.uri,
                        fallbackText = content.fallbackText,
                    )
                    is MessageContent.Video -> addMedia(
                        fileType = QqBotMediaFileType.VIDEO,
                        uri = content.video.uri,
                        fallbackText = content.fallbackText,
                    )
                    is MessageContent.Audio -> addMedia(
                        fileType = QqBotMediaFileType.AUDIO,
                        uri = content.audio.uri,
                        fallbackText = content.fallbackText,
                    )
                    else -> addText(content.fallbackText)
                }
            }
        flushText()

        return items.ifEmpty {
            listOf(QqBotSendPlanItem.Text(toTextRequest(batches = batches, replyToMessageId = normalizedReplyTo)))
        }
    }

    private fun List<MessageBatch>.firstReplyMessageId(): String? {
        return asSequence()
            .flatMap { it.content.asSequence() }
            .filterIsInstance<MessageContent.Reply>()
            .mapNotNull { it.messageId.trim().takeIf(String::isNotBlank) }
            .firstOrNull()
    }

    private fun String.toHttpUrlOrNull(): String? {
        val value = trim()
        if (value.isBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return value
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
internal const val QQBOT_TEXT_MSG_TYPE: Int = 0
internal const val QQBOT_MEDIA_MSG_TYPE: Int = 7
private const val QQBOT_EMPTY_MESSAGE_TEXT: String = "（空消息）"
