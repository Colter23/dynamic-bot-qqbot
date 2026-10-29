package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QqBotMentionAllQuotaTest {
    @Test
    fun `同一群同一天只能占用一次配额`() {
        val quota = QqBotMentionAllQuota(todayEpochDay = { 100L })

        assertTrue(quota.tryAcquire("group-1"))
        assertFalse(quota.tryAcquire("group-1"))
        // 其他群互不影响
        assertTrue(quota.tryAcquire("group-2"))
    }

    @Test
    fun `跨自然日配额自动重置`() {
        var today = 100L
        val quota = QqBotMentionAllQuota(todayEpochDay = { today })

        assertTrue(quota.tryAcquire("group-1"))
        assertFalse(quota.tryAcquire("group-1"))

        today = 101L
        assertTrue(quota.tryAcquire("group-1"))
        assertFalse(quota.tryAcquire("group-1"))
    }

    @Test
    fun `空白 openid 不占用配额`() {
        val quota = QqBotMentionAllQuota(todayEpochDay = { 100L })

        assertFalse(quota.tryAcquire(""))
        assertFalse(quota.tryAcquire("   "))
    }
}
