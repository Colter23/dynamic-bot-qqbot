package top.colter.dynamic.qqbot

import top.colter.dynamic.core.data.MessageBatch

internal data class QqBotSendRequest(
    val content: String,
    val msgType: Int = 0,
    val replyToMessageId: String? = null,
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
            content = content,
            msgType = 0,
            replyToMessageId = replyToMessageId,
        )
    }
}
