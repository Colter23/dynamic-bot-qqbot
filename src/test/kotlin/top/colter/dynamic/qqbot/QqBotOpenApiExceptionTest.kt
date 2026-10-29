package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QqBotOpenApiExceptionTest {
    @Test
    fun `at 全体成员次数超限与鉴权失败不可重试`() {
        listOf("304019", "1100304").forEach { code ->
            val error = QqBotOpenApiException(
                action = "发送 QQ 官方机器人消息",
                statusCode = 400,
                code = code,
                message = "群聊 @全体成员 失败",
                body = "",
            )

            assertFalse(error.retryable)
        }
    }

    @Test
    fun `官方明示发送异常的码仍按状态未知处理`() {
        val error = QqBotOpenApiException(
            action = "发送 QQ 官方机器人消息",
            statusCode = 500,
            code = "50055002",
            message = "消息发送异常，请稍后重试",
            body = "",
        )

        assertTrue(error.outcomeUnknown)
        assertTrue(error.retryable)
    }
}
