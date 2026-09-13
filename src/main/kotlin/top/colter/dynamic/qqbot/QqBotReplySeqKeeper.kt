package top.colter.dynamic.qqbot

import java.util.concurrent.ConcurrentHashMap

/**
 * 被动回复重试时的 msg_seq 续编器。
 *
 * 官方按 `msg_id + msg_seq` 去重：同一 [msgId] 的重复请求需要递增 [msgSeq] 才会被接受。
 * 主项目对失败投递重试时会原样重发消息，这里记录每个 msg_id 已用过的最大 seq，
 * 让重试请求从"上次已用 + 1"开始，避免重试被官方去重规则直接拒绝。
 *
 * 仅在内存中维护：进程重启后主项目重发的请求可能重新从 1 开始，此时被动回复窗口
 * （单聊 60 分钟、群聊 5 分钟）大概率已过期，官方会返回可识别的终态错误码。
 */
internal class QqBotReplySeqKeeper(
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val usedSeqByReplyId = ConcurrentHashMap<String, MutableMap<Int, Long>>()
    private var lastPruneMillis: Long = 0L

    /**
     * 把发送计划中各请求的 msgSeq 改写为该回复 ID 下未用过的新值。
     * 非被动回复（replyMessageId 为空）的计划原样返回。
     */
    fun advance(replyMessageId: String?, plan: List<QqBotSendPlanItem>): List<QqBotSendPlanItem> {
        if (replyMessageId.isNullOrBlank()) return plan
        val replyId = replyMessageId.trim()
        val now = nowEpochMillis()
        pruneIfDue(now)

        val used = usedSeqByReplyId.computeIfAbsent(replyId) { ConcurrentHashMap() }
        return plan.map { item ->
            val seq = allocateSeq(used, item.request.msgSeq, now)
            if (seq == item.request.msgSeq) {
                item
            } else {
                item.withRequest(item.request.copy(msgSeq = seq))
            }
        }
    }

    private fun allocateSeq(
        used: MutableMap<Int, Long>,
        requestedSeq: Int,
        now: Long,
    ): Int {
        var seq = requestedSeq
        while (used.putIfAbsent(seq, now) != null) {
            seq += 1
        }
        return seq
    }

    private fun pruneIfDue(now: Long) {
        if (now - lastPruneMillis < windowMillis / 2 && lastPruneMillis != 0L) return
        lastPruneMillis = now
        usedSeqByReplyId.entries.removeIf { (_, used) ->
            val latest = used.values.maxOrNull() ?: return@removeIf true
            now - latest > windowMillis
        }
        usedSeqByReplyId.values.forEach { used ->
            used.entries.removeIf { (_, atMillis) -> now - atMillis > windowMillis }
        }
    }

    private companion object {
        /** 被动回复窗口最长为单聊 60 分钟，超窗后重发必然收到过期错误码，记录无需更久。 */
        private const val DEFAULT_WINDOW_MILLIS: Long = 60L * 60 * 1000
    }
}

private fun QqBotSendPlanItem.withRequest(request: QqBotSendRequest): QqBotSendPlanItem = when (this) {
    is QqBotSendPlanItem.Text -> copy(request = request)
    is QqBotSendPlanItem.Media -> copy(request = request)
}
