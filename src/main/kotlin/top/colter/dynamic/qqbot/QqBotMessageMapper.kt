package top.colter.dynamic.qqbot

import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

internal data class QqBotMediaResourceSource(
    val fileType: QqBotMediaFileType,
    val url: String? = null,
    val fileDataBase64: String? = null,
)

internal sealed interface QqBotSendPlanItem {
    val request: QqBotSendRequest

    data class Text(
        override val request: QqBotSendRequest,
    ) : QqBotSendPlanItem

    data class Media(
        val media: QqBotMediaResourceSource,
        override val request: QqBotSendRequest,
    ) : QqBotSendPlanItem
}

internal object QqBotMessageMapper {
    fun toMediaResourceSource(fileType: QqBotMediaFileType, uri: String): QqBotMediaResourceSource? {
        return uri.toMediaResourceSourceOrNull(fileType)
    }

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
        var nextMsgSeq = 1

        fun nextRequest(content: String, msgType: Int): QqBotSendRequest {
            val normalizedContent = if (msgType == QQBOT_MEDIA_MSG_TYPE) {
                content.trim().ifBlank { " " }
            } else {
                content.trim().ifBlank { QQBOT_EMPTY_MESSAGE_TEXT }
            }
            return QqBotSendRequest(
                content = normalizedContent,
                msgType = msgType,
                replyToMessageId = normalizedReplyTo,
                msgSeq = nextMsgSeq++,
            )
        }

        batches.forEach { batch ->
            val mediaCandidates = mutableListOf<QqBotBatchMediaCandidate>()
            val textParts = mutableListOf<String>()

            batch.content.forEach { content ->
                val media = content.toMediaCandidate()
                if (media != null) {
                    mediaCandidates += media
                    if (media.source == null) {
                        textParts += media.fallbackText.ifBlank {
                            "（暂不支持发送${media.fileType.label}，请使用 HTTP(S) URL 或 base64 数据）"
                        }
                    } else {
                        textParts += media.fallbackText
                    }
                } else {
                    textParts += content.fallbackText
                }
            }

            val text = textParts.joinNonBlankLines()
            val sendableMedia = mediaCandidates.firstOrNull { it.source != null }
            if (sendableMedia?.source != null) {
                items += QqBotSendPlanItem.Media(
                    media = sendableMedia.source,
                    request = nextRequest(
                        content = text.ifBlank { " " },
                        msgType = QQBOT_MEDIA_MSG_TYPE,
                    ),
                )
                mediaCandidates.dropWhile { it !== sendableMedia }
                    .drop(1)
                    .filter { it.source != null }
                    .forEach { extraMedia ->
                        items += QqBotSendPlanItem.Media(
                            media = extraMedia.source!!,
                            request = nextRequest(
                                content = extraMedia.fallbackText.ifBlank { " " },
                                msgType = QQBOT_MEDIA_MSG_TYPE,
                            ),
                        )
                    }
            } else if (text.isNotBlank()) {
                items += QqBotSendPlanItem.Text(nextRequest(content = text, msgType = QQBOT_TEXT_MSG_TYPE))
            }
        }

        return items.ifEmpty {
            listOf(QqBotSendPlanItem.Text(toTextRequest(batches = batches, replyToMessageId = normalizedReplyTo)))
        }
    }

    private fun MessageContent.toMediaCandidate(): QqBotBatchMediaCandidate? {
        return when (this) {
            is MessageContent.Image -> QqBotBatchMediaCandidate(
                fileType = QqBotMediaFileType.IMAGE,
                source = toMediaResourceSource(QqBotMediaFileType.IMAGE, image.uri),
                fallbackText = fallbackText,
            )
            is MessageContent.Video -> QqBotBatchMediaCandidate(
                fileType = QqBotMediaFileType.VIDEO,
                source = toMediaResourceSource(QqBotMediaFileType.VIDEO, video.uri),
                fallbackText = fallbackText,
            )
            is MessageContent.Audio -> QqBotBatchMediaCandidate(
                fileType = QqBotMediaFileType.AUDIO,
                source = toMediaResourceSource(QqBotMediaFileType.AUDIO, audio.uri),
                fallbackText = fallbackText,
            )
            else -> null
        }
    }

    private fun List<String>.joinNonBlankLines(): String {
        return mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")
            .trim()
    }

    private fun List<MessageBatch>.firstReplyMessageId(): String? {
        return asSequence()
            .flatMap { it.content.asSequence() }
            .filterIsInstance<MessageContent.Reply>()
            .mapNotNull { it.messageId.trim().takeIf(String::isNotBlank) }
            .firstOrNull()
    }

    private fun String.toMediaResourceSourceOrNull(fileType: QqBotMediaFileType): QqBotMediaResourceSource? {
        toHttpUrlOrNull()?.let { url -> return QqBotMediaResourceSource(fileType = fileType, url = url) }
        toBase64DataOrNull()?.let { data -> return QqBotMediaResourceSource(fileType = fileType, fileDataBase64 = data) }
        return null
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

    private fun String.toBase64DataOrNull(): String? {
        val value = trim()
        if (!value.startsWith(BASE64_URI_PREFIX, ignoreCase = true)) return null
        return value.substring(BASE64_URI_PREFIX.length).trim().takeIf { it.isNotBlank() }
    }
}

private data class QqBotBatchMediaCandidate(
    val fileType: QqBotMediaFileType,
    val source: QqBotMediaResourceSource?,
    val fallbackText: String,
)

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
            QQBOT_GROUP_AT_MESSAGE_CREATE -> data.toGroupIncoming(event, mentionedBot = true)
            QQBOT_GROUP_MESSAGE_CREATE -> data.toGroupIncoming(event, mentionedBot = false)
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

    private fun JsonObject.toGroupIncoming(event: QqBotIncomingEvent, mentionedBot: Boolean): IncomingMessage? {
        val groupOpenid = jsonString("group_openid") ?: return null
        val senderId = jsonString("author", "member_openid")
            ?: jsonString("author", "group_member_openid")
            ?: return null
        val messageId = jsonString("id")
        val content = jsonString("content").orEmpty()
        val attachments = attachments()
        val mentionItems = mentionItems(event)
        val segments = buildSegments(content, attachments, mentionItems)
        val mentions = mentionItems.mentionedAccountIds(event, mentionedBot)
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
            mentions = mentions,
        )
    }

    private fun List<QqBotIncomingMention>.mentionedAccountIds(event: QqBotIncomingEvent, mentionedBot: Boolean): Set<String> {
        return buildSet {
            if (mentionedBot) {
                event.botAccountId?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            }
            this@mentionedAccountIds.forEach { add(it.accountId) }
            remove("")
        }
    }

    private fun buildSegments(
        content: String,
        attachments: List<QqBotIncomingAttachment>,
        mentions: List<QqBotIncomingMention> = emptyList(),
    ): List<IncomingMessageSegment> {
        return buildList {
            addAll(content.toTextAndMentionSegments(mentions))
            attachments.forEach { attachment ->
                add(attachment.toSegment())
            }
        }
    }

    private fun String.toTextAndMentionSegments(mentions: List<QqBotIncomingMention>): List<IncomingMessageSegment> {
        if (isEmpty()) return emptyList()
        val byRawId = mentions.associateBy { it.rawId }
        val segments = mutableListOf<IncomingMessageSegment>()
        var cursor = 0
        QQBOT_OFFICIAL_MENTION_PATTERN.findAll(this).forEach { match ->
            if (match.range.first > cursor) {
                val text = substring(cursor, match.range.first)
                if (text.isNotEmpty()) segments += IncomingMessageSegment.Text(text)
            }
            val rawId = match.groupValues[1].trim()
            val mention = byRawId[rawId]
            segments += IncomingMessageSegment.Mention(
                targetId = mention?.accountId ?: rawId,
                rawPayload = mention?.rawPayload ?: match.value,
            )
            cursor = match.range.last + 1
        }
        if (cursor < length) {
            val text = substring(cursor)
            if (text.isNotEmpty()) segments += IncomingMessageSegment.Text(text)
        }
        return segments.ifEmpty { listOf(IncomingMessageSegment.Text(this)) }
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

    private fun JsonObject.mentionItems(event: QqBotIncomingEvent): List<QqBotIncomingMention> {
        return get("mentions")
            ?.jsonArray
            ?.mapNotNull { element ->
                val item = element.jsonObject
                val rawId = item.jsonString("member_openid") ?: item.jsonString("id") ?: return@mapNotNull null
                val isCurrentBot = item["is_you"]?.jsonPrimitive?.booleanOrNull == true
                QqBotIncomingMention(
                    rawId = rawId,
                    accountId = if (isCurrentBot) {
                        event.botAccountId?.trim()?.takeIf { it.isNotBlank() } ?: rawId
                    } else {
                        rawId
                    },
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

private data class QqBotIncomingMention(
    val rawId: String,
    val accountId: String,
    val rawPayload: String,
)

internal const val QQBOT_RAW_FORMAT: String = "qqbot-api-v2-dispatch-json"
internal const val QQBOT_C2C_MESSAGE_CREATE: String = "C2C_MESSAGE_CREATE"
internal const val QQBOT_GROUP_AT_MESSAGE_CREATE: String = "GROUP_AT_MESSAGE_CREATE"
internal const val QQBOT_GROUP_MESSAGE_CREATE: String = "GROUP_MESSAGE_CREATE"
internal const val QQBOT_TEXT_MSG_TYPE: Int = 0
internal const val QQBOT_MEDIA_MSG_TYPE: Int = 7
private const val QQBOT_EMPTY_MESSAGE_TEXT: String = "（空消息）"
private const val BASE64_URI_PREFIX: String = "base64://"
private val QQBOT_OFFICIAL_MENTION_PATTERN: Regex = Regex("""<@([^>]+)>""")
