package top.colter.dynamic.qqbot

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageReference
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
    /**
     * 引用回复用的消息索引（形如 `REFIDX_xxx==`），对应官方 `message_reference.message_id`。
     *
     * 与 [replyToMessageId] 不是同一个值：后者是事件 `d.id`，用于被动回复（`msg_id`）；
     * 引用展示需要的是消息自身事件 `message_scene.ext` 里的 `msg_idx`。
     */
    val referenceMessageId: String? = null,
)

/** 从入站事件中提取到的目标：账本只需要类型与 openid。 */
internal data class QqBotTargetSighting(
    val kind: TargetKind,
    val externalId: String,
)

/**
 * 入站消息自身的引用索引：留存后可用于以引用形式回复该消息。
 *
 * [messageId] 为事件 `d.id`（被动回复用），[messageIndex] 为 `message_scene.ext.msg_idx`（引用用）。
 */
internal data class QqBotReplyReference(
    val messageId: String,
    val messageIndex: String,
)

/** 为发送计划中的请求替换底层 [QqBotSendRequest]，保留 Text / Media 的具体类型。 */
internal fun QqBotSendPlanItem.withRequest(request: QqBotSendRequest): QqBotSendPlanItem = when (this) {
    is QqBotSendPlanItem.Text -> copy(request = request)
    is QqBotSendPlanItem.Media -> copy(request = request)
}

internal data class QqBotSendReceipt(
    val messageId: String,
    val rawPayload: String = "",
)

internal enum class QqBotMediaFileType(val code: Int, val label: String) {
    IMAGE(1, "图片"),
    VIDEO(2, "视频"),
    AUDIO(3, "语音"),
}

/**
 * 待发送的富媒体来源。
 *
 * - [url]：公网可访问的 HTTP(S) 地址，走平台下载转存。
 * - [localFile]：本地文件，走官方分片上传（无需公网地址）。
 *
 * 两者互斥，`url` 优先。
 */
internal data class QqBotMediaResourceSource(
    val fileType: QqBotMediaFileType,
    val url: String? = null,
    val localFile: Path? = null,
    val fileName: String? = null,
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
        val value = uri.trim()
        if (value.isBlank()) return null
        value.toHttpUrlOrNull()?.let { url ->
            return QqBotMediaResourceSource(fileType = fileType, url = url)
        }
        value.toLocalFileOrNull()?.let { path ->
            return QqBotMediaResourceSource(
                fileType = fileType,
                localFile = path,
                fileName = path.fileName?.toString(),
            )
        }
        return null
    }

    /** 把 URI 解析为可读的本地文件；不是本地文件或文件不可读时返回 null。 */
    fun resolveLocalFile(uri: String): Path? = uri.toLocalFileOrNull()

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

    /**
     * 把消息批次转换成 QQ 发送计划。
     *
     * [mentionAllAllowed] 为 true 表示本次允许真正使用官方的 `<qqbot-at-everyone />`：
     * 只有群聊目标、且本地每日配额尚未用过时才会传 true。默认 false 保持旧行为
     * （降级为纯文本 `@全体成员`）。
     */
    fun toSendPlan(
        batches: List<MessageBatch>,
        replyToMessageId: String? = null,
        mentionAllAllowed: Boolean = false,
    ): List<QqBotSendPlanItem> {
        val normalizedReplyTo = replyToMessageId?.trim()?.takeIf { it.isNotBlank() }
            ?: batches.firstReplyMessageId()
        val items = mutableListOf<QqBotSendPlanItem>()
        var nextMsgSeq = 1
        // 平台限制每群每天只能 @ 全体成员 1 次：一条消息里出现多个时只保留第一个。
        var mentionAllEmitted = false

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
            // 一次预扫描同时拿到媒体候选与"该批次最终是否会发成富媒体消息"：
            // 富媒体消息（msg_type=7）不支持 @全体成员，标签必须抽成独立的文本消息。
            val contentItems = batch.content.map { content -> content to content.toMediaCandidate() }
            val mediaCandidates = contentItems.mapNotNull { (_, media) -> media }
            val hasSendableMedia = mediaCandidates.any { it.source != null }
            val textParts = mutableListOf<String>()
            var pendingMentionAll = false

            contentItems.forEach { (content, media) ->
                when {
                    media != null -> {
                        if (media.source == null) {
                            textParts += media.fallbackText.ifBlank {
                                "（暂不支持发送${media.fileType.label}，请使用 HTTP(S) URL 或本地文件）"
                            }
                        } else {
                            textParts += media.fallbackText
                        }
                    }
                    // 官方 at 协议：<qqbot-at-user id=""/>（群聊/单聊可用，<@userid> 已弃用）
                    content is MessageContent.Mention && content.target.externalId.isNotBlank() -> {
                        textParts += content.mentionSyntax(content.target.externalId)
                            .takeIf { it.isNotBlank() } ?: content.fallbackText
                    }
                    content is MessageContent.MentionAll -> {
                        if (mentionAllAllowed && !mentionAllEmitted) {
                            mentionAllEmitted = true
                            if (hasSendableMedia) {
                                // 不能写进富媒体消息的内容里，稍后单独补一条文本消息
                                pendingMentionAll = true
                            } else {
                                textParts += QQBOT_MENTION_ALL_SYNTAX
                            }
                        } else {
                            textParts += content.fallbackText.ifBlank { QQBOT_MENTION_ALL_FALLBACK }
                        }
                    }
                    else -> textParts += content.fallbackText
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

            if (pendingMentionAll) {
                // 独立文本消息，接在该批次之后；平台只允许文本/markdown 消息承载 @全体成员
                items += QqBotSendPlanItem.Text(
                    nextRequest(content = QQBOT_MENTION_ALL_SYNTAX, msgType = QQBOT_TEXT_MSG_TYPE),
                )
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

    /** 官方 at 协议语法；openid 为空时返回空串，由调用方回退到 fallbackText。 */
    private fun MessageContent.mentionSyntax(openid: String): String {
        val id = openid.trim()
        if (id.isBlank()) return ""
        return "<qqbot-at-user id=\"$id\" />"
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

    /**
     * 把输入解析为可读的本地文件。
     *
     * Windows 盘符路径（如 `D:\a.png`、`D:/a.png`、`C:a.png`）会被 `URI` 当成 scheme
     * （`scheme=D`），必须先于 URI 解析识别；`file:` URI 与普通路径随后处理。
     * 不是本地文件或不可读时返回 null。
     */
    private fun String.toLocalFileOrNull(): Path? {
        val value = trim()
        if (value.isBlank()) return null
        val path = try {
            when {
                value.isWindowsDrivePath() -> Paths.get(value)
                else -> {
                    val parsed = runCatching { URI(value) }.getOrNull()
                    when {
                        parsed == null -> Paths.get(value)
                        parsed.scheme?.equals("file", ignoreCase = true) == true -> Paths.get(parsed)
                        parsed.scheme != null -> return null
                        else -> Paths.get(value)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return path.takeIf { candidate ->
            runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)
        }
    }

    /** `D:\x`、`D:/x`、`D:x` 这类盘符路径；`D` 为任意单个字母，不区分大小写。 */
    private fun String.isWindowsDrivePath(): Boolean {
        if (length < 2) return false
        if (this[1] != ':') return false
        val letter = this[0]
        return (letter in 'a'..'z') || (letter in 'A'..'Z')
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
        val data = dispatch.data.asObjectOrNull() ?: return null
        return when (event.type) {
            QQBOT_C2C_MESSAGE_CREATE -> data.toC2cIncoming(event)
            QQBOT_GROUP_AT_MESSAGE_CREATE -> data.toGroupIncoming(event, mentionedBot = true)
            QQBOT_GROUP_MESSAGE_CREATE -> data.toGroupIncoming(event, mentionedBot = false)
            else -> null
        }
    }

    /**
     * 从任意入站事件中提取"消息目标 sighting"，供目标账本记录。
     *
     * 覆盖三类：
     * - 消息事件（单聊/群聊）：机器人收到的消息必然来自可触达目标；
     * - 群/好友生命周期（加入/移出、添加/删除）：触发时可确知关系存续或断开；
     * - 主动消息开关（RECEIVE/REJECT）：开关事件证明该目标存在。
     *
     * 返回 null 表示事件与目标发现无关。
     */
    fun toTargetSighting(event: QqBotIncomingEvent): QqBotTargetSighting? {
        val dispatch = runCatching { QqBotWebSocketProtocol.parseDispatch(event.rawPayload) }.getOrNull()
            ?: return null
        val data = dispatch.data.asObjectOrNull() ?: return null
        return when (event.type) {
            QQBOT_C2C_MESSAGE_CREATE -> data.jsonString("author", "user_openid")
                ?.let { QqBotTargetSighting(TargetKind.USER, it) }
            QQBOT_GROUP_AT_MESSAGE_CREATE,
            QQBOT_GROUP_MESSAGE_CREATE,
            QQBOT_GROUP_ADD_ROBOT,
            QQBOT_GROUP_DEL_ROBOT,
            QQBOT_GROUP_MSG_RECEIVE,
            QQBOT_GROUP_MSG_REJECT,
            -> data.jsonString("group_openid")
                ?.let { QqBotTargetSighting(TargetKind.GROUP, it) }
            QQBOT_FRIEND_ADD,
            QQBOT_FRIEND_DEL,
            QQBOT_C2C_MSG_RECEIVE,
            QQBOT_C2C_MSG_REJECT,
            -> data.jsonString("openid")
                ?.let { QqBotTargetSighting(TargetKind.USER, it) }
            else -> null
        }
    }

    /**
     * 提取"本条消息自身的引用索引"，留存后可用于以引用形式回复它。
     *
     * 官方引用回复（`message_reference.message_id`）要求填**该消息自身事件**的
     * `message_scene.ext.msg_idx`（形如 `REFIDX_xxx==`）；这与被动回复所用的消息 ID
     * （事件 `d.id`）**不是同一个值**，所以必须在接收时留存两者的映射。
     *
     * 注意别与 `ref_msg_idx` 混淆：后者是"本条消息所引用的那条消息"的索引，格式也不同（`TMP_...`）。
     *
     * 返回 null 表示该事件不可作为引用对象（非消息事件，或缺少消息 ID / 索引）。
     */
    fun toReplyReference(event: QqBotIncomingEvent): QqBotReplyReference? {
        if (event.type !in QQBOT_MESSAGE_EVENT_TYPES) return null
        val dispatch = runCatching { QqBotWebSocketProtocol.parseDispatch(event.rawPayload) }.getOrNull()
            ?: return null
        val data = dispatch.data.asObjectOrNull() ?: return null
        val messageId = data.jsonString("id")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val messageIndex = data.messageSceneExt()["msg_idx"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return QqBotReplyReference(messageId = messageId, messageIndex = messageIndex)
    }

    private fun JsonObject.toC2cIncoming(event: QqBotIncomingEvent): IncomingMessage? {
        val senderId = jsonString("author", "user_openid") ?: jsonString("author", "openid") ?: return null
        val messageId = jsonString("id")
        val content = jsonString("content").orEmpty()
        val attachments = attachments()
        val quoted = quotedMessage()
        val segments = buildSegments(content, attachments, arkLink = arkLinkSegment(event), quote = quoted?.toSegment())
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
            replyTo = quoted?.toReference(),
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
        val quoted = quotedMessage()
        val segments = buildSegments(
            content = content,
            attachments = attachments,
            mentions = mentionItems,
            arkLink = arkLinkSegment(event),
            quote = quoted?.toSegment(),
        )
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
            replyTo = quoted?.toReference(),
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
        arkLink: IncomingMessageSegment.Link? = null,
        quote: IncomingMessageSegment.Reply? = null,
    ): List<IncomingMessageSegment> {
        return buildList {
            if (quote != null) add(quote)
            addAll(content.toTextAndMentionSegments(mentions))
            attachments.forEach { attachment ->
                add(attachment.toSegment())
            }
            if (arkLink != null) add(arkLink)
        }
    }

    /**
     * 引用消息（`message_type=103`）：引用索引优先取 `message_scene.ext.ref_msg_idx`；
     * 仅在 `message_type=103` 时才回退 `msg_elements[0].msg_idx`，避免把同样携带
     * `msg_elements` 的 101 并行消息 / 102 聊天记录误判为引用。被引用内容可能已被
     * 平台折叠，只保留索引与原文载荷。
     */
    private fun JsonObject.quotedMessage(): QqBotQuotedMessage? {
        val explicitIndex = messageSceneExt()["ref_msg_idx"]?.trim()?.takeIf { it.isNotBlank() }
        if (explicitIndex == null && messageType() != QQBOT_MESSAGE_TYPE_QUOTE) return null
        val elements = msgElements()
        val quotedIndex = explicitIndex
            ?: elements.firstOrNull()?.index?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val quoted = elements.firstOrNull { it.index == quotedIndex } ?: elements.firstOrNull()
        return QqBotQuotedMessage(
            messageId = quotedIndex,
            senderId = quoted?.senderId,
            rawPayload = quoted?.rawPayload.orEmpty(),
        )
    }

    /** `message_scene.ext` 为 `key=value` 字符串列表；非字符串元素与空值忽略。 */
    private fun JsonObject.messageSceneExt(): Map<String, String> {
        return get("message_scene")
            ?.asObjectOrNull()
            ?.get("ext")
            ?.asArrayOrNull()
            ?.mapNotNull { element ->
                val item = element.asPrimitiveOrNull()?.contentOrNull?.trim() ?: return@mapNotNull null
                val separator = item.indexOf('=')
                if (separator <= 0 || separator == item.lastIndex) {
                    null
                } else {
                    item.substring(0, separator) to item.substring(separator + 1)
                }
            }
            ?.toMap()
            .orEmpty()
    }

    private fun JsonObject.msgElements(): List<QqBotMsgElement> {
        return get("msg_elements")
            ?.asArrayOrNull()
            ?.mapNotNull { element ->
                val item = element.asObjectOrNull() ?: return@mapNotNull null
                val index = item.jsonString("msg_idx") ?: return@mapNotNull null
                QqBotMsgElement(
                    index = index,
                    senderId = item.jsonString("author", "member_openid")
                        ?: item.jsonString("author", "user_openid")
                        ?: item.jsonString("author", "openid"),
                    rawPayload = json.encodeToString(JsonElement.serializer(), element),
                )
            }
            .orEmpty()
    }

    private fun QqBotQuotedMessage.toSegment(): IncomingMessageSegment.Reply {
        return IncomingMessageSegment.Reply(
            messageId = messageId,
            rawPayload = rawPayload.takeIf { it.isNotBlank() },
        )
    }

    private fun QqBotQuotedMessage.toReference(): IncomingMessageReference {
        return IncomingMessageReference(
            messageId = messageId,
            senderId = senderId,
            rawPayload = rawPayload.takeIf { it.isNotBlank() },
        )
    }

    /** `message_type=3` 的卡片消息：从 `ark_data.fields` 提取明确跳转链接。 */
    private fun JsonObject.arkLinkSegment(event: QqBotIncomingEvent): IncomingMessageSegment.Link? {
        val arkData = get("ark_data") ?: return null
        return QqBotArkLinkExtractor.extract(arkData, event.rawPayload)
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
            ?.asArrayOrNull()
            ?.mapNotNull { element ->
                val item = element.asObjectOrNull() ?: return@mapNotNull null
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
            ?.asArrayOrNull()
            ?.mapNotNull { element ->
                val item = element.asObjectOrNull() ?: return@mapNotNull null
                val rawId = item.jsonString("member_openid") ?: item.jsonString("id") ?: return@mapNotNull null
                val isCurrentBot = item["is_you"]?.asPrimitiveOrNull()?.booleanOrNull == true
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
            ?.asPrimitiveOrNull()
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.jsonString(parent: String, name: String): String? {
        return get(parent)
            ?.asObjectOrNull()
            ?.jsonString(name)
    }

    /** 事件体中的 `message_type`：官方为整数，同时兼容字符串形式。 */
    private fun JsonObject.messageType(): Int? {
        val primitive = get("message_type")?.asPrimitiveOrNull() ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
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

/** 被引用的消息：[messageId] 为平台消息索引（如 `REFIDX_xxx==`），用于回复时携带引用。 */
private data class QqBotQuotedMessage(
    val messageId: String,
    val senderId: String?,
    val rawPayload: String,
)

/** `msg_elements` 中的一条被引用消息元素。 */
private data class QqBotMsgElement(
    val index: String,
    val senderId: String?,
    val rawPayload: String,
)

internal const val QQBOT_RAW_FORMAT: String = "qqbot-api-v2-dispatch-json"
internal const val QQBOT_C2C_MESSAGE_CREATE: String = "C2C_MESSAGE_CREATE"
internal const val QQBOT_GROUP_AT_MESSAGE_CREATE: String = "GROUP_AT_MESSAGE_CREATE"
internal const val QQBOT_GROUP_MESSAGE_CREATE: String = "GROUP_MESSAGE_CREATE"
internal const val QQBOT_GROUP_ADD_ROBOT: String = "GROUP_ADD_ROBOT"
internal const val QQBOT_GROUP_DEL_ROBOT: String = "GROUP_DEL_ROBOT"
internal const val QQBOT_FRIEND_ADD: String = "FRIEND_ADD"
internal const val QQBOT_FRIEND_DEL: String = "FRIEND_DEL"
internal const val QQBOT_C2C_MSG_RECEIVE: String = "C2C_MSG_RECEIVE"
internal const val QQBOT_C2C_MSG_REJECT: String = "C2C_MSG_REJECT"
internal const val QQBOT_GROUP_MSG_RECEIVE: String = "GROUP_MSG_RECEIVE"
internal const val QQBOT_GROUP_MSG_REJECT: String = "GROUP_MSG_REJECT"
internal const val QQBOT_TEXT_MSG_TYPE: Int = 0
internal const val QQBOT_MEDIA_MSG_TYPE: Int = 7
internal const val QQBOT_MESSAGE_TYPE_QUOTE: Int = 103

/** 携带消息体、可作为回复/引用对象的消息事件。 */
internal val QQBOT_MESSAGE_EVENT_TYPES: Set<String> = setOf(
    QQBOT_C2C_MESSAGE_CREATE,
    QQBOT_GROUP_AT_MESSAGE_CREATE,
    QQBOT_GROUP_MESSAGE_CREATE,
)
private const val QQBOT_EMPTY_MESSAGE_TEXT: String = "（空消息）"

/** 官方群聊 @全体成员 的内嵌文本语法；只在文本/markdown 消息中生效，每群每天 1 次。 */
internal const val QQBOT_MENTION_ALL_SYNTAX: String = "<qqbot-at-everyone />"

/** 不能真正 @ 时的纯文本降级内容，同时用作被平台拒绝后的重发文本。 */
internal const val QQBOT_MENTION_ALL_FALLBACK: String = "@全体成员"
private val QQBOT_OFFICIAL_MENTION_PATTERN: Regex = Regex("""<@([^>]+)>""")
