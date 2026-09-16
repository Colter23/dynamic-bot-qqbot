package top.colter.dynamic.qqbot

import java.util.concurrent.ConcurrentHashMap

/**
 * 入站消息 ID → 消息自身引用索引（`msg_idx`）的留存器。
 *
 * QQ 官方的"回复"其实有两套机制，用的是**两个不同的值**：
 * - 被动回复（`msg_id`）：用事件 `d.id`，主项目通过 `replyToMessageId` 传下来；
 * - 引用展示（`message_reference.message_id`）：用该消息自身事件 `message_scene.ext.msg_idx`
 *   （形如 `REFIDX_xxx==`）。
 *
 * 主项目回复时只给得出前者，所以必须在收到消息时把后者记下来，发送时再取回，
 * 否则引用只能退化成"被动回复"（平台不展示引用气泡）。
 *
 * 仅在内存中维护：进程重启后旧索引丢失，引用退化为仅被动回复；被动回复窗口本身
 * 最长也只有单聊 60 分钟，超窗后同样无法回复。
 */
internal class QqBotMessageIndexKeeper(
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val indexByMessageId = ConcurrentHashMap<String, CachedIndex>()
    private var lastPruneMillis: Long = 0L

    /** 记录一条入站消息的引用索引；参数不完整时忽略。 */
    fun remember(messageId: String?, messageIndex: String?) {
        val id = messageId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val index = messageIndex?.trim()?.takeIf { it.isNotBlank() } ?: return
        val now = nowEpochMillis()
        pruneIfDue(now)
        if (indexByMessageId.size >= maxEntries && !indexByMessageId.containsKey(id)) {
            evictOldest()
        }
        indexByMessageId[id] = CachedIndex(index = index, atMillis = now)
    }

    /** 取回该消息的引用索引；没有记录（或已过期）时返回 null，调用方应退化为仅被动回复。 */
    fun referenceFor(messageId: String?): String? {
        val id = messageId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cached = indexByMessageId[id] ?: return null
        if (nowEpochMillis() - cached.atMillis > windowMillis) {
            indexByMessageId.remove(id)
            return null
        }
        return cached.index
    }

    /** 给发送计划补上引用索引；无记录时原样返回。 */
    fun apply(replyToMessageId: String?, plan: List<QqBotSendPlanItem>): List<QqBotSendPlanItem> {
        val reference = referenceFor(replyToMessageId) ?: return plan
        return plan.map { item ->
            if (item.request.referenceMessageId == reference) {
                item
            } else {
                item.withRequest(item.request.copy(referenceMessageId = reference))
            }
        }
    }

    private fun evictOldest() {
        val oldest = indexByMessageId.minByOrNull { it.value.atMillis }?.key ?: return
        indexByMessageId.remove(oldest)
    }

    private fun pruneIfDue(now: Long) {
        if (lastPruneMillis != 0L && now - lastPruneMillis < windowMillis / 2) return
        lastPruneMillis = now
        indexByMessageId.entries.removeIf { (_, cached) -> now - cached.atMillis > windowMillis }
    }

    private data class CachedIndex(
        val index: String,
        val atMillis: Long,
    )

    private companion object {
        /** 引用索引的有效期文档未明确；比被动回复窗口（最长 60 分钟）略短即可。 */
        private const val DEFAULT_WINDOW_MILLIS: Long = 30L * 60 * 1000

        /** 上限兜底：正常情况下窗口内的消息量远小于此，避免异常流量撑爆内存。 */
        private const val DEFAULT_MAX_ENTRIES: Int = 512
    }
}
