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
    private data class State(var nextSeq: Int, var remaining: Int, val expiresAt: Long)

    class Reservation(val item: QqBotSendPlanItem, private val releaseUnused: () -> Unit = {}) {
        fun release() = releaseUnused()
    }

    private val states = LinkedHashMap<Key, State>()
    private val incomingExpirations = LinkedHashMap<Key, Long>()
    private var lastPruneMillis: Long? = null

    init {
        require(windowMillis > 0)
        require(maxEntries > 0)
    }

    /** 从入站时刻计时，避免长时间解析或下载后重新获得一个完整窗口。 */
    @Synchronized
    fun remember(accountId: String, target: TargetAddress, replyId: String, receivedAtEpochSeconds: Long) {
        if (replyId.isBlank() || accountId.isBlank()) return
        if (target.kind != TargetKind.GROUP && target.kind != TargetKind.USER) return
        val now = nowEpochMillis()
        prune(now)
        val key = Key(accountId.trim(), target.stableValue(), replyId.trim())
        if (key in states || key in incomingExpirations) return
        val receivedAt = receivedAtEpochSeconds.takeIf { it > 0 }?.times(1000)?.coerceAtMost(now) ?: now
        val duration = if (target.kind == TargetKind.GROUP) minOf(windowMillis, 5L * 60 * 1000) else windowMillis
        if (receivedAt + duration <= now) return
        // 未回复消息只留存有界的时间索引，不占用配额账本，也不能淘汰已使用的计数。
        if (incomingExpirations.size >= maxEntries) {
            incomingExpirations.entries.iterator().also { it.next(); it.remove() }
        }
        incomingExpirations[key] = receivedAt + duration
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
        prune(now)
        val key = Key(accountId.trim(), target.stableValue(), replyId)
        val state = states[key] ?: run {
            if (states.size >= maxEntries) throw QqBotPassiveReplyException("被动回复状态容量已满，请稍后重新发送指令")
            val expiresAt = incomingExpirations.remove(key)
                ?: throw QqBotPassiveReplyException("原始消息已过期或不可用，请重新发送指令或链接")
            State(nextSeq = 1, remaining = limit, expiresAt = expiresAt).also { states[key] = it }
        }
        if (now >= state.expiresAt) throw QqBotPassiveReplyException("被动回复窗口已过期，请重新发送指令或链接")
        if (state.remaining == 0) throw QqBotPassiveReplyException("被动回复条数已达平台上限，请减少模板拆分或重新发送指令")
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

    private fun prune(now: Long) {
        if (lastPruneMillis == null || now - lastPruneMillis!! >= minOf(windowMillis / 2, 30_000L)) {
            // 过期记录保留一段时间用于拒绝旧 msg_id，不重新发放被动回复配额。
            states.entries.removeIf { now > it.value.expiresAt + windowMillis }
            incomingExpirations.entries.removeIf { now >= it.value }
            lastPruneMillis = now
        }
    }

}

internal class QqBotPassiveReplyException(message: String) : IllegalStateException(message)
