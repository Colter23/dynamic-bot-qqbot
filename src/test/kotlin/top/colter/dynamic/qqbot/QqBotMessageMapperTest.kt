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

    private fun createTempImage(): Path {
        val dir = Files.createTempDirectory("qqbot-mapper-test")
        val file = dir.resolve("image.png")
        Files.write(file, byteArrayOf(1, 2, 3, 4))
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        return file
    }
}
