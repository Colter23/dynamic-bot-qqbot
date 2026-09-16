package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class QqBotMessageIndexKeeperTest {
    @Test
    fun `记录后可按消息 ID 取回引用索引`() {
        val keeper = QqBotMessageIndexKeeper()

        keeper.remember("msg-1", "REFIDX_aaa==")

        assertEquals("REFIDX_aaa==", keeper.referenceFor("msg-1"))
    }

    @Test
    fun `无记录或参数不完整时返回 null`() {
        val keeper = QqBotMessageIndexKeeper()

        assertNull(keeper.referenceFor(null))
        assertNull(keeper.referenceFor("   "))
        assertNull(keeper.referenceFor("unknown"))

        keeper.remember(null, "REFIDX_aaa==")
        keeper.remember("msg-1", null)
        keeper.remember("msg-2", "   ")

        assertNull(keeper.referenceFor("msg-1"))
        assertNull(keeper.referenceFor("msg-2"))
    }

    @Test
    fun `窗口过期后索引失效`() {
        var now = 0L
        val keeper = QqBotMessageIndexKeeper(nowEpochMillis = { now }, windowMillis = 1_000L)

        keeper.remember("msg-1", "REFIDX_aaa==")
        assertEquals("REFIDX_aaa==", keeper.referenceFor("msg-1"))

        now = 1_001L

        assertNull(keeper.referenceFor("msg-1"))
    }

    @Test
    fun `超出上限时淘汰最旧记录`() {
        var now = 0L
        val keeper = QqBotMessageIndexKeeper(
            nowEpochMillis = { now },
            windowMillis = 1_000_000L,
            maxEntries = 2,
        )

        keeper.remember("msg-1", "REFIDX_1==")
        now = 1L
        keeper.remember("msg-2", "REFIDX_2==")
        now = 2L
        keeper.remember("msg-3", "REFIDX_3==")

        assertNull(keeper.referenceFor("msg-1"))
        assertEquals("REFIDX_2==", keeper.referenceFor("msg-2"))
        assertEquals("REFIDX_3==", keeper.referenceFor("msg-3"))
    }

    @Test
    fun `apply 为计划补上引用索引且保留内容`() {
        val keeper = QqBotMessageIndexKeeper()
        keeper.remember("msg-1", "REFIDX_aaa==")
        val plan = listOf(
            QqBotSendPlanItem.Text(QqBotSendRequest(content = "a", replyToMessageId = "msg-1")),
            QqBotSendPlanItem.Text(QqBotSendRequest(content = "b", replyToMessageId = "msg-1")),
        )

        val applied = keeper.apply("msg-1", plan)

        assertEquals(listOf("REFIDX_aaa==", "REFIDX_aaa=="), applied.map { it.request.referenceMessageId })
        assertEquals(listOf("a", "b"), applied.map { it.request.content })
    }

    @Test
    fun `apply 无记录时原样返回`() {
        val keeper = QqBotMessageIndexKeeper()
        val plan = listOf(QqBotSendPlanItem.Text(QqBotSendRequest(content = "a", replyToMessageId = "msg-1")))

        assertSame(plan, keeper.apply("msg-1", plan))
        assertSame(plan, keeper.apply(null, plan))
    }

    @Test
    fun `apply 保留富媒体类型与来源`() {
        val keeper = QqBotMessageIndexKeeper()
        keeper.remember("msg-1", "REFIDX_aaa==")
        val media = QqBotSendPlanItem.Media(
            media = QqBotMediaResourceSource(fileType = QqBotMediaFileType.IMAGE, url = "https://example.com/a.png"),
            request = QqBotSendRequest(content = "图", msgType = QQBOT_MEDIA_MSG_TYPE, replyToMessageId = "msg-1"),
        )

        val applied = keeper.apply("msg-1", listOf(media)).single() as QqBotSendPlanItem.Media

        assertEquals("REFIDX_aaa==", applied.request.referenceMessageId)
        assertEquals(QQBOT_MEDIA_MSG_TYPE, applied.request.msgType)
        assertEquals(media.media, applied.media)
    }

    @Test
    fun `并发记录不丢索引`() {
        val keeper = QqBotMessageIndexKeeper()
        val count = 50

        val threads = (1..count).map { index ->
            Thread { keeper.remember("msg-$index", "REFIDX_$index==") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 容量充足时不得因并发淘汰而丢记录
        (1..count).forEach { index ->
            assertEquals("REFIDX_$index==", keeper.referenceFor("msg-$index"))
        }
    }
}
