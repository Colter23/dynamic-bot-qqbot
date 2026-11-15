package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class QqBotReplySeqKeeperTest {
    private val group = TargetAddress.of("qq-official", TargetKind.GROUP, "group")
    private fun item(replyId: String? = "msg-1") = QqBotSendPlanItem.Text(
        QqBotSendRequest(content = "text", replyToMessageId = replyId),
    )
    private fun QqBotReplySeqKeeper.advance(accountId: String, target: TargetAddress, item: QqBotSendPlanItem): QqBotSendPlanItem {
        remember(accountId, target, item.request.replyToMessageId.orEmpty(), 0L)
        return reserve(accountId, target, item).item
    }

    @Test
    fun `释放预约只归还次数且不回退序号`() {
        val keeper = QqBotReplySeqKeeper()
        keeper.remember("app", group, "msg-1", 0L)
        val reserved = (1..5).map { keeper.reserve("app", group, item()) }
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }

        reserved.first().release()
        reserved.first().release()

        val next = keeper.advance("app", group, item())
        assertEquals("msg-1", next.request.replyToMessageId)
        assertEquals(6, next.request.msgSeq)
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }
    }

    @Test
    fun `并发释放同一预约不会重复归还配额`() {
        val keeper = QqBotReplySeqKeeper()
        keeper.remember("app", group, "msg-1", 0L)
        val reserved = keeper.reserve("app", group, item())
        repeat(4) { keeper.advance("app", group, item()) }

        (1..8).map { Thread { repeat(20) { reserved.release() } } }
            .also { threads -> threads.forEach { it.start() } }
            .forEach { it.join() }

        assertEquals(6, keeper.advance("app", group, item()).request.msgSeq)
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }
    }

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
        val second = listOf(keeper.advance("app", group, item()))
        assertEquals(listOf(1, 2, 3, 4, 5), (first + second).filter { it.request.replyToMessageId != null }.map { it.request.msgSeq })
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }
    }

    @Test
    fun `单聊最多四条被动回复`() {
        val keeper = QqBotReplySeqKeeper()
        val user = TargetAddress.of("qq-official", TargetKind.USER, "user")
        val replies = (1..4).map { keeper.advance("app", user, item()) }
        assertEquals(4, replies.count { it.request.replyToMessageId != null })
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", user, item()) }
    }

    @Test
    fun `账号目标和消息之间配额隔离`() {
        val keeper = QqBotReplySeqKeeper()
        repeat(5) { keeper.advance("app", group, item()) }
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group.copy(accountId = "route-hint"), item()) }
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
    fun `回复窗口从入站时刻开始且重复事件不会延长`() {
        var now = 1_000_000L
        val keeper = QqBotReplySeqKeeper(nowEpochMillis = { now })
        keeper.remember("app", group, "msg-1", 800L)
        assertEquals(1, keeper.advance("app", group, item()).request.msgSeq)
        now = 1_100_000L
        keeper.remember("app", group, "msg-1", 1_100L)
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }
    }

    @Test
    fun `窗口过期后清理历史 seq 记录`() {
        var now = 0L
        val keeper = QqBotReplySeqKeeper(
            nowEpochMillis = { now },
            windowMillis = 1_000L,
        )
        repeat(5) { keeper.advance("app", group, item()) }
        now = 1_001L
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }
        now = 2_001L
        val afterWindow = keeper.advance("app", group, item("msg-new"))
        assertEquals(1, afterWindow.request.msgSeq)
        assertEquals("msg-new", afterWindow.request.replyToMessageId)
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
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, media) }
    }

    @Test
    fun `并发分配不重复发放配额和序号`() {
        val keeper = QqBotReplySeqKeeper()
        val threads = 4
        val results = java.util.concurrent.ConcurrentLinkedQueue<QqBotSendPlanItem>()
        (1..threads).map {
            Thread {
                repeat(50) { runCatching { keeper.advance("app", group, item()) }.getOrNull()?.let(results::add) }
            }
        }.also { list -> list.forEach { it.start() } }.forEach { it.join() }

        val passive = results.filter { it.request.replyToMessageId != null }
        assertEquals(5, results.size)
        assertEquals(listOf(1, 2, 3, 4, 5), passive.map { it.request.msgSeq }.sorted())
    }

    @Test
    fun `容量耗尽不淘汰仍有效的回复配额`() {
        val keeper = QqBotReplySeqKeeper(maxEntries = 1)
        repeat(5) { keeper.advance("app", group, item()) }
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item("other")) }
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }
    }

    @Test
    fun `大量未回复消息不占用配额账本且新指令可以正常回复`() {
        val keeper = QqBotReplySeqKeeper(nowEpochMillis = { 1_000_000L }, maxEntries = 2)
        repeat(20) { keeper.remember("app", group, "chat-$it", 1_000L) }
        keeper.remember("app", group, "command", 1_000L)
        assertEquals(1, keeper.advance("app", group, item("command")).request.msgSeq)
        assertEquals(2, keeper.advance("app", group, item("command")).request.msgSeq)
    }

    @Test
    fun `未回复消息的淘汰不影响已经消耗的配额`() {
        val keeper = QqBotReplySeqKeeper(nowEpochMillis = { 1_000_000L }, maxEntries = 1)
        keeper.remember("app", group, "msg-1", 1_000L)
        repeat(5) { keeper.advance("app", group, item()) }
        repeat(20) { keeper.remember("app", group, "chat-$it", 1_000L) }
        assertFailsWith<QqBotPassiveReplyException> { keeper.advance("app", group, item()) }
    }

    @Test
    fun `未回复时间索引淘汰后不能重新获得完整回复窗口`() {
        val keeper = QqBotReplySeqKeeper(nowEpochMillis = { 1_000_000L }, maxEntries = 1)
        keeper.remember("app", group, "old", 800L)
        keeper.remember("app", group, "new", 1_000L)
        assertFailsWith<QqBotPassiveReplyException> { keeper.reserve("app", group, item("old")) }
        assertEquals(1, keeper.advance("app", group, item("new")).request.msgSeq)
    }

    @Test
    fun `清理未使用或已使用的过期记录后仍拒绝旧回复目标`() {
        var now = 1_000_000L
        val keeper = QqBotReplySeqKeeper(nowEpochMillis = { now })
        keeper.remember("app", group, "unused", 1_000L)
        keeper.remember("app", group, "used", 1_000L)
        keeper.advance("app", group, item("used"))
        now += 4_000_000L
        assertFailsWith<QqBotPassiveReplyException> { keeper.reserve("app", group, item("unused")) }
        assertFailsWith<QqBotPassiveReplyException> { keeper.reserve("app", group, item("used")) }
        keeper.remember("app", group, "next", now / 1_000)
        assertEquals(1, keeper.advance("app", group, item("next")).request.msgSeq)
    }

    @Test
    fun `没有入站记录时不能重新发放旧消息配额`() {
        val keeper = QqBotReplySeqKeeper()
        assertFailsWith<QqBotPassiveReplyException> { keeper.reserve("app", group, item("before-restart")) }
    }
}
