package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QqBotReplySeqKeeperTest {
    @Test
    fun `非被动回复计划原样返回`() {
        val plan = listOf(
            QqBotSendPlanItem.Text(QqBotSendRequest(content = "a", msgSeq = 1)),
            QqBotSendPlanItem.Text(QqBotSendRequest(content = "b", msgSeq = 2)),
        )
        val keeper = QqBotReplySeqKeeper()

        val advanced = keeper.advance(null, plan)
        val advancedBlank = keeper.advance("   ", plan)

        assertSame(plan, advanced)
        assertSame(plan, advancedBlank)
    }

    @Test
    fun `同一回复 ID 重试时 msgSeq 递增避免官方去重`() {
        val keeper = QqBotReplySeqKeeper()
        val plan = listOf(
            QqBotSendPlanItem.Text(QqBotSendRequest(content = "a", msgSeq = 1)),
            QqBotSendPlanItem.Text(QqBotSendRequest(content = "b", msgSeq = 2)),
        )

        val first = keeper.advance("msg-1", plan)
        val second = keeper.advance("msg-1", plan)
        val third = keeper.advance("msg-1", plan)

        assertEquals(listOf(1, 2), first.map { it.request.msgSeq })
        assertEquals(listOf(3, 4), second.map { it.request.msgSeq })
        assertEquals(listOf(5, 6), third.map { it.request.msgSeq })
    }

    @Test
    fun `不同回复 ID 的 seq 分配互不影响`() {
        val keeper = QqBotReplySeqKeeper()
        val plan = listOf(QqBotSendPlanItem.Text(QqBotSendRequest(content = "a", msgSeq = 1)))

        val first = keeper.advance("msg-1", plan)
        val other = keeper.advance("msg-2", plan)

        assertEquals(1, first.single().request.msgSeq)
        assertEquals(1, other.single().request.msgSeq)
    }

    @Test
    fun `窗口过期后清理历史 seq 记录`() {
        var now = 0L
        val keeper = QqBotReplySeqKeeper(
            nowEpochMillis = { now },
            windowMillis = 1_000L,
        )
        val plan = listOf(QqBotSendPlanItem.Text(QqBotSendRequest(content = "a", msgSeq = 1)))

        keeper.advance("msg-1", plan)
        now = 2_000L

        val afterWindow = keeper.advance("msg-1", plan)

        // 窗口过期后记录被清理，seq 从头分配
        assertEquals(1, afterWindow.single().request.msgSeq)
    }

    @Test
    fun `改写后的请求保留原内容与类型`() {
        val keeper = QqBotReplySeqKeeper()
        val media = QqBotSendPlanItem.Media(
            media = QqBotMediaResourceSource(fileType = QqBotMediaFileType.IMAGE, url = "https://example.com/a.png"),
            request = QqBotSendRequest(content = "图", msgType = QQBOT_MEDIA_MSG_TYPE, msgSeq = 1),
        )

        val first = keeper.advance("msg-1", listOf(media)).single() as QqBotSendPlanItem.Media
        val second = keeper.advance("msg-1", listOf(media)).single() as QqBotSendPlanItem.Media

        assertEquals(1, first.request.msgSeq)
        assertEquals(2, second.request.msgSeq)
        assertEquals("图", second.request.content)
        assertEquals(QQBOT_MEDIA_MSG_TYPE, second.request.msgType)
        assertEquals(media.media, second.media)
    }

    @Test
    fun `并发分配不产生重复 seq`() {
        val keeper = QqBotReplySeqKeeper()
        val plan = (1..50).map { index ->
            QqBotSendPlanItem.Text(QqBotSendRequest(content = "n$index", msgSeq = index))
        }

        val threads = 4
        val results = java.util.concurrent.ConcurrentLinkedQueue<List<QqBotSendPlanItem>>()
        (1..threads).map {
            Thread {
                results.add(keeper.advance("msg-1", plan))
            }
        }.also { list -> list.forEach { it.start() } }.forEach { it.join() }

        val allSeqs = results.flatten().map { it.request.msgSeq }
        assertEquals(threads * 50, allSeqs.size)
        assertEquals(allSeqs.size, allSeqs.distinct().size)
        assertTrue(allSeqs.all { it > 0 })
    }
}
