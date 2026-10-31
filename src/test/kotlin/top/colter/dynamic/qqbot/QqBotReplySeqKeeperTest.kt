package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class QqBotReplySeqKeeperTest {
    private val group = TargetAddress.of("qq-official", TargetKind.GROUP, "group")
    private fun item(replyId: String? = "msg-1") = QqBotSendPlanItem.Text(
        QqBotSendRequest(content = "text", replyToMessageId = replyId),
    )

    @Test
    fun `非被动回复计划原样返回`() {
        val keeper = QqBotReplySeqKeeper()
        for (replyId in listOf(null, "   ")) {
            val original = item(replyId)
            assertSame(original, keeper.advance("app", group, original))
        }
    }

    @Test
    fun `多次发送及重试共用群聊五条配额和递增序号`() {
        val keeper = QqBotReplySeqKeeper()
        val first = (1..4).map { keeper.advance("app", group, item()) }
        val second = (1..2).map { keeper.advance("app", group, item()) }
        assertEquals(listOf(1, 2, 3, 4, 5), (first + second).filter { it.request.replyToMessageId != null }.map { it.request.msgSeq })
        assertEquals(null, second.last().request.replyToMessageId)
    }

    @Test
    fun `单聊最多四条被动回复`() {
        val keeper = QqBotReplySeqKeeper()
        val user = TargetAddress.of("qq-official", TargetKind.USER, "user")
        val replies = (1..5).map { keeper.advance("app", user, item()) }
        assertEquals(4, replies.count { it.request.replyToMessageId != null })
        assertEquals(null, replies.last().request.replyToMessageId)
    }

    @Test
    fun `账号目标和消息之间配额隔离`() {
        val keeper = QqBotReplySeqKeeper()
        repeat(5) { keeper.advance("app", group, item()) }
        assertEquals(null, keeper.advance("app", group.copy(accountId = "route-hint"), item()).request.replyToMessageId)
        for (next in listOf(
            keeper.advance("other-app", group, item()),
            keeper.advance("app", group.copy(externalId = "other-group"), item()),
            keeper.advance("app", group, item("msg-2")),
        )) {
            assertEquals(1, next.request.msgSeq)
            assertTrue(next.request.replyToMessageId != null)
        }
    }

    @Test
    fun `窗口过期后清理历史 seq 记录`() {
        var now = 0L
        val keeper = QqBotReplySeqKeeper(
            nowEpochMillis = { now },
            windowMillis = 1_000L,
        )
        repeat(5) { keeper.advance("app", group, item()) }
        now = 2_000L
        val afterWindow = keeper.advance("app", group, item())
        assertEquals(1, afterWindow.request.msgSeq)
        assertEquals("msg-1", afterWindow.request.replyToMessageId)
    }

    @Test
    fun `改写后的请求保留原内容与类型`() {
        val keeper = QqBotReplySeqKeeper()
        val media = QqBotSendPlanItem.Media(
            media = QqBotMediaResourceSource(fileType = QqBotMediaFileType.IMAGE, url = "https://example.com/a.png"),
            request = QqBotSendRequest(content = "图", msgType = QQBOT_MEDIA_MSG_TYPE, replyToMessageId = "msg-1", referenceMessageId = "ref-index"),
        )
        keeper.advance("app", group, media)
        val second = keeper.advance("app", group, media) as QqBotSendPlanItem.Media
        assertEquals(2, second.request.msgSeq)
        assertEquals(media.request.copy(msgSeq = 2), second.request)
        assertEquals(media.media, second.media)
        repeat(3) { keeper.advance("app", group, media) }
        val overflow = keeper.advance("app", group, media) as QqBotSendPlanItem.Media
        assertEquals(media.request.copy(replyToMessageId = null), overflow.request)
        assertEquals(media.media, overflow.media)
    }

    @Test
    fun `并发分配不重复发放配额和序号`() {
        val keeper = QqBotReplySeqKeeper()
        val threads = 4
        val results = java.util.concurrent.ConcurrentLinkedQueue<QqBotSendPlanItem>()
        (1..threads).map {
            Thread {
                repeat(50) { results.add(keeper.advance("app", group, item())) }
            }
        }.also { list -> list.forEach { it.start() } }.forEach { it.join() }

        val passive = results.filter { it.request.replyToMessageId != null }
        assertEquals(threads * 50, results.size)
        assertEquals(listOf(1, 2, 3, 4, 5), passive.map { it.request.msgSeq }.sorted())
    }

    @Test
    fun `容量耗尽不淘汰仍有效的回复配额`() {
        val keeper = QqBotReplySeqKeeper(maxEntries = 1)
        repeat(5) { keeper.advance("app", group, item()) }
        assertEquals(null, keeper.advance("app", group, item("other")).request.replyToMessageId)
        assertEquals(null, keeper.advance("app", group, item()).request.replyToMessageId)
    }
}
