package top.colter.dynamic.qqbot

import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

/** 同一账号、目标、入站消息共享被动回复配额与 msg_seq，包括重试和降级重发。 */
internal class QqBotReplySeqKeeper(
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val windowMillis: Long = 60L * 60 * 1000,
    private val maxEntries: Int = 4_096,
) {
    private data class Key(val accountId: String, val target: String, val replyId: String)
    private data class State(var nextSeq: Int, var remaining: Int, var lastUsed: Long)

    class Reservation(val item: QqBotSendPlanItem, private val releaseUnused: () -> Unit = {}) {
        fun release() = releaseUnused()
    }

    private val states = LinkedHashMap<Key, State>()
    private var lastPruneMillis: Long? = null

    init {
        require(windowMillis > 0)
        require(maxEntries > 0)
    }

    /** 紧邻实际发送时预约，不为尚未发送的后续批次提前消耗配额。 */
    @Synchronized
    fun reserve(accountId: String, target: TargetAddress, item: QqBotSendPlanItem): Reservation {
        val replyId = item.request.replyToMessageId?.trim()?.takeIf(String::isNotBlank) ?: return Reservation(item)
        val limit = when (target.kind) {
            TargetKind.USER -> 4
            TargetKind.GROUP -> 5
            else -> return Reservation(item)
        }
        val now = nowEpochMillis()
        if (lastPruneMillis == null || now - lastPruneMillis!! >= windowMillis / 2) {
            states.entries.removeIf { now - it.value.lastUsed > windowMillis }
            lastPruneMillis = now
        }
        val key = Key(accountId.trim(), target.stableValue(), replyId)
        val state = states[key] ?: run {
            // 容量耗尽时按主动消息处理，不淘汰仍有效的配额以免重复发放。
            if (states.size >= maxEntries) return Reservation(item.asActiveMessage())
            State(nextSeq = 1, remaining = limit, lastUsed = now).also { states[key] = it }
        }
        state.lastUsed = now
        if (state.remaining == 0) return Reservation(item.asActiveMessage())
        state.remaining--
        val allocated = item.withRequest(item.request.copy(replyToMessageId = replyId, msgSeq = state.nextSeq++))
        var released = false
        return Reservation(allocated) {
            synchronized(this) {
                if (!released) {
                    // 只归还确定未发送的次数；序号继续递增，避免并发及重试复用。
                    state.remaining++
                    released = true
                }
            }
        }
    }

    private fun QqBotSendPlanItem.asActiveMessage(): QqBotSendPlanItem =
        withRequest(request.copy(replyToMessageId = null))
}
