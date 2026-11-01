package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.TargetAddress

class QqBotMessageMapperTest {
    @Test
    fun `本地文件解析支持 Windows 盘符路径`() {
        val file = createTempImage()

        val slashForm = QqBotMessageMapper.toMediaResourceSource(
            QqBotMediaFileType.IMAGE,
            file.toString().replace('\\', '/'),
        )
        val backslashForm = QqBotMessageMapper.toMediaResourceSource(
            QqBotMediaFileType.IMAGE,
            file.toString(),
        )

        assertEquals(file, slashForm?.localFile)
        assertEquals(file, backslashForm?.localFile)
        assertEquals("image.png", slashForm?.fileName)
    }

    @Test
    fun `本地文件解析支持 file URI`() {
        val file = createTempImage()

        val resolved = QqBotMessageMapper.resolveLocalFile(file.toUri().toString())

        assertEquals(file, resolved)
    }

    @Test
    fun `本地文件解析拒绝不存在的文件与其他 scheme`() {
        assertNull(QqBotMessageMapper.resolveLocalFile("file:///qqbot/missing/image.png"))
        assertNull(QqBotMessageMapper.resolveLocalFile("D:/qqbot/missing/image.png"))
        assertNull(QqBotMessageMapper.resolveLocalFile("ftp://example.com/image.png"))
        assertNull(QqBotMessageMapper.resolveLocalFile("http://example.com/image.png"))
        assertNull(QqBotMessageMapper.resolveLocalFile(""))
    }

    @Test
    fun `媒体来源解析优先识别 HTTP URL`() {
        val resolved = QqBotMessageMapper.toMediaResourceSource(
            QqBotMediaFileType.IMAGE,
            "https://example.com/a.png",
        )

        assertEquals("https://example.com/a.png", resolved?.url)
        assertNull(resolved?.localFile)
    }

    @Test
    fun `发送计划把同批次图文合并为一条富媒体消息`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Image(
                            fallbackText = "",
                            image = MediaRef(
                                uri = "https://example.com/b.png",
                                kind = MediaKind.IMAGE,
                            ),
                        ),
                        MessageContent.Text("文字说明"),
                    ),
                ),
            ),
        )

        val mediaItem = assertIs<QqBotSendPlanItem.Media>(plan.single())
        assertEquals(QQBOT_MEDIA_MSG_TYPE, mediaItem.request.msgType)
        assertTrue(mediaItem.request.content.isNotBlank())
    }

    @Test
    fun `发送计划对不可发送媒体回退为文本提示`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Image(
                            fallbackText = "",
                            image = MediaRef(
                                uri = "data:image/png;base64,AAAA",
                                kind = MediaKind.IMAGE,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val textItem = assertIs<QqBotSendPlanItem.Text>(plan.single())
        assertEquals(QQBOT_TEXT_MSG_TYPE, textItem.request.msgType)
        assertTrue(textItem.request.content.contains("暂不支持发送图片"))
    }

    @Test
    fun `发送计划为多条消息递增 msgSeq`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(listOf(MessageContent.Text("第一段"))),
                MessageBatch(listOf(MessageContent.Text("第二段"))),
                MessageBatch(listOf(MessageContent.Text("第三段"))),
            ),
            replyToMessageId = "source-msg",
        )

        assertEquals(listOf(1, 2, 3), plan.map { it.request.msgSeq })
        assertTrue(plan.all { it.request.replyToMessageId == "source-msg" })
    }

    @Test
    fun `被动回复超过平台限制时超出部分改为主动消息`() {
        val plan = (1..6).map { index ->
            QqBotSendPlanItem.Text(
                QqBotSendRequest(
                    content = "第${index}条",
                    replyToMessageId = "source-msg",
                    msgSeq = index,
                ),
            )
        }

        val keeper = QqBotReplySeqKeeper()
        val limited = plan.map { keeper.reserve("app", TargetAddress.of("qq-official", TargetKind.GROUP, "group"), it).item }

        assertEquals(listOf("source-msg", "source-msg", "source-msg", "source-msg", "source-msg", null), limited.map { it.request.replyToMessageId })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), limited.map { it.request.msgSeq })
    }

    @Test
    fun `发送计划保留批次图文边界`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(listOf(MessageContent.Text("先看图"))),
                MessageBatch(
                    listOf(
                        MessageContent.Image(
                            fallbackText = "图片说明",
                            image = MediaRef(
                                uri = "https://example.com/a.png",
                                kind = MediaKind.IMAGE,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(2, plan.size)
        assertIs<QqBotSendPlanItem.Text>(plan[0])
        assertIs<QqBotSendPlanItem.Media>(plan[1])
        assertFalse(plan[0].request.msgSeq == plan[1].request.msgSeq)
    }

    @Test
    fun `发送计划映射 Mention 为官方 at 协议`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Text("通知："),
                        MessageContent.Mention(
                            fallbackText = "@某用户",
                            target = top.colter.dynamic.core.data.TargetAddress.of(
                                platformId = "qq-official",
                                kind = TargetKind.USER,
                                externalId = "user-openid-1",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val textItem = assertIs<QqBotSendPlanItem.Text>(plan.single())
        assertEquals(true, textItem.request.content.contains("""<qqbot-at-user id="user-openid-1" />"""))
    }

    @Test
    fun `发送计划对 MentionAll 降级为纯文本`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Text("新动态来了"),
                        MessageContent.MentionAll(fallbackText = ""),
                    ),
                ),
            ),
        )

        val textItem = assertIs<QqBotSendPlanItem.Text>(plan.single())
        assertEquals(true, textItem.request.content.contains("新动态来了"))
        assertEquals(true, textItem.request.content.contains("@全体成员"))
    }

    @Test
    fun `群聊文本消息的 MentionAll 使用官方 at-everyone 语法`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Text("新动态来了"),
                        MessageContent.MentionAll(fallbackText = ""),
                    ),
                ),
            ),
            mentionAllAllowed = true,
        )

        val textItem = assertIs<QqBotSendPlanItem.Text>(plan.single())
        assertEquals(true, textItem.request.content.contains("<qqbot-at-everyone />"))
        assertFalse(textItem.request.content.contains("@全体成员"))
        assertEquals(QQBOT_TEXT_MSG_TYPE, textItem.request.msgType)
    }

    @Test
    fun `富媒体批次的 MentionAll 抽成独立文本消息`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Image(
                            fallbackText = "",
                            image = MediaRef(uri = "https://example.com/a.png", kind = MediaKind.IMAGE),
                        ),
                        MessageContent.Text("\n发布了新动态"),
                        MessageContent.MentionAll(fallbackText = ""),
                    ),
                ),
            ),
            mentionAllAllowed = true,
        )

        assertEquals(2, plan.size)
        val mediaItem = assertIs<QqBotSendPlanItem.Media>(plan[0])
        assertEquals(QQBOT_MEDIA_MSG_TYPE, mediaItem.request.msgType)
        assertFalse(mediaItem.request.content.contains("qqbot-at-everyone"))

        val atAllItem = assertIs<QqBotSendPlanItem.Text>(plan[1])
        assertEquals(QQBOT_TEXT_MSG_TYPE, atAllItem.request.msgType)
        assertEquals("<qqbot-at-everyone />", atAllItem.request.content)
    }

    @Test
    fun `一条消息里的多个 MentionAll 只有第一个使用官方语法`() {
        val plan = QqBotMessageMapper.toSendPlan(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Text("通知"),
                        MessageContent.MentionAll(fallbackText = ""),
                        MessageContent.Text("\n"),
                        MessageContent.MentionAll(fallbackText = ""),
                    ),
                ),
            ),
            mentionAllAllowed = true,
        )

        val content = assertIs<QqBotSendPlanItem.Text>(plan.single()).request.content
        assertEquals(1, Regex(Regex.escape(QQBOT_MENTION_ALL_SYNTAX)).findAll(content).count())
        assertTrue(content.contains("@全体成员"))
    }

    private fun createTempImage(): Path {
        val dir = Files.createTempDirectory("qqbot-mapper-test")
        val file = dir.resolve("image.png")
        Files.write(file, byteArrayOf(1, 2, 3, 4))
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        return file
    }
}
