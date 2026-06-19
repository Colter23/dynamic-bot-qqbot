package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class QqBotCommandMapperTest {
    @Test
    fun `群消息提及当前机器人时去掉开头官方 at 文本`() {
        val request = QqBotCommandMapper.toCommandRequest(
            sourcePlugin = QQBOT_PLUGIN_ID,
            incoming = groupIncoming(
                text = "<@bot-member-openid> /db help",
                mentions = setOf("bot-app-id"),
            ),
        )

        requireNotNull(request)
        assertEquals("/db help", request.rawText)
        assertEquals(setOf("bot-app-id"), request.context.mentionedAccountIds)
    }

    @Test
    fun `群消息没有提及当前机器人时保留原始文本`() {
        val request = QqBotCommandMapper.toCommandRequest(
            sourcePlugin = QQBOT_PLUGIN_ID,
            incoming = groupIncoming(
                text = "<@other-member-openid> /db help",
                mentions = setOf("other-member-openid"),
            ),
        )

        requireNotNull(request)
        assertEquals("<@other-member-openid> /db help", request.rawText)
        assertEquals("bot-app-id", request.context.botAccountId)
        assertEquals("bot-app-id", request.context.target.accountId)
        assertEquals(setOf("other-member-openid"), request.context.mentionedAccountIds)
    }

    @Test
    fun `群消息未 at 当前机器人时仍发布给主项目用于链接解析`() {
        val request = QqBotCommandMapper.toCommandRequest(
            sourcePlugin = QQBOT_PLUGIN_ID,
            incoming = groupIncoming(
                text = "https://t.bilibili.com/1215152419671900183",
            ),
        )

        requireNotNull(request)
        assertEquals("https://t.bilibili.com/1215152419671900183", request.rawText)
        assertEquals("bot-app-id", request.context.botAccountId)
        assertEquals("bot-app-id", request.context.target.accountId)
        assertEquals(emptySet(), request.context.mentionedAccountIds)
    }

    @Test
    fun `私聊命令上下文标记为当前机器人被直接命中`() {
        val request = QqBotCommandMapper.toCommandRequest(
            sourcePlugin = QQBOT_PLUGIN_ID,
            incoming = userIncoming("/db help"),
        )

        requireNotNull(request)
        assertEquals("/db help", request.rawText)
        assertEquals(setOf("bot-app-id"), request.context.mentionedAccountIds)
    }

    @Test
    fun `空白文本不发布命令请求`() {
        val request = QqBotCommandMapper.toCommandRequest(
            sourcePlugin = QQBOT_PLUGIN_ID,
            incoming = groupIncoming(text = "   "),
        )

        assertNull(request)
    }

    private fun groupIncoming(
        text: String,
        mentions: Set<String> = emptySet(),
    ): IncomingMessage {
        return IncomingMessage(
            platformId = QQBOT_PLATFORM_ID,
            target = TargetAddress.of(
                platformId = QQBOT_PLATFORM_ID.value,
                kind = TargetKind.GROUP,
                externalId = "group-openid",
                accountId = "bot-app-id",
            ),
            senderId = "member-openid",
            botAccountId = "bot-app-id",
            messageId = "group-msg-1",
            text = text,
            mentions = mentions,
        )
    }

    private fun userIncoming(text: String): IncomingMessage {
        return IncomingMessage(
            platformId = QQBOT_PLATFORM_ID,
            target = TargetAddress.of(
                platformId = QQBOT_PLATFORM_ID.value,
                kind = TargetKind.USER,
                externalId = "user-openid",
                accountId = "bot-app-id",
            ),
            senderId = "user-openid",
            botAccountId = "bot-app-id",
            messageId = "user-msg-1",
            text = text,
        )
    }
}
