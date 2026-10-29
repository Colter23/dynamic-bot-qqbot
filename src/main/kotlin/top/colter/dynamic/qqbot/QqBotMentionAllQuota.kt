package top.colter.dynamic.qqbot

import java.time.LocalDate
import java.time.ZoneId

/**
 * 群聊 @全体成员 的每日配额记账。
 *
 * 官方限制：群聊文本/markdown 消息可以用 `<qqbot-at-everyone />` @全体成员，但机器人必须是
 * 该群管理员，且**每个群每个自然日仅可发送 1 次**，超限时平台返回 `304019`。
 *
 * 主项目对一个群一天可能推送多条消息（多个订阅、多种事件），因此在插件侧先记账：
 * 当天已经 @ 过的群，后续推送直接降级为纯文本，避免每次都发送必然被拒的请求。
 *
 * 只在内存中维护，有意不落盘：
 * - 重启/插件重载后每个群当天会重新尝试一次，被拒后由发送兜底降级，最多多一条失败请求；
 * - 也避免了"落盘日期与平台日切不一致导致配额被长期卡死"的问题。
 */
internal class QqBotMentionAllQuota(
    private val todayEpochDay: () -> Long = { LocalDate.now(PLATFORM_ZONE).toEpochDay() },
) {
    private val lock = Any()

    /** 群 openid -> 最近一次占用配额的自然日（epoch day）。 */
    private val usedEpochDayByGroup: MutableMap<String, Long> = mutableMapOf()

    /**
     * 尝试占用该群今天的 @全体成员 配额。
     *
     * 返回 true 表示调用方可以真正发送 `<qqbot-at-everyone />`，并已记为该群今天已用；
     * 返回 false 表示空白 openid、或该群今天已经用过，调用方应降级为纯文本。
     *
     * 采用"先占后用"（fail-closed）：并发或后续推送不会重复尝试；即使本次发送最终失败，
     * 当天也不会再反复撞平台频控。
     */
    fun tryAcquire(groupOpenid: String): Boolean {
        val id = normalize(groupOpenid) ?: return false
        return synchronized(lock) {
            val today = todayEpochDay()
            pruneIfNeeded(today)
            if (usedEpochDayByGroup[id] == today) {
                false
            } else {
                usedEpochDayByGroup[id] = today
                true
            }
        }
    }

    private fun normalize(groupOpenid: String): String? =
        groupOpenid.trim().takeIf { it.isNotBlank() }

    /** 只在表满时清理旧记录，避免长期运行后无界增长。 */
    private fun pruneIfNeeded(today: Long) {
        if (usedEpochDayByGroup.size < MAX_ENTRIES) return
        usedEpochDayByGroup.entries.removeIf { (_, epochDay) -> epochDay != today }
    }

    private companion object {
        /** 平台按自然日计算配额；QQ 平台的日切按北京时间，不跟随服务器时区。 */
        private val PLATFORM_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 内存表上限兜底：正常情况下同时在用的群远小于此。 */
        private const val MAX_ENTRIES: Int = 1_024
    }
}
