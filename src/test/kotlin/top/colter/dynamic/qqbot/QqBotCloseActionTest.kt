package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 关闭码处置依据官方 WebSocket 错误码表：
 * `qqbot-wiki-md/develop/api-v2/dev-prepare/event-emit/websocket.md`。
 *
 * 此前不看关闭码，导致 4006/4007 会继续 RESUME 而反复被拒，4013/4014/4914/4915 等
 * 官方明确"不可重试"的终态错误会无限重连。
 */
class QqBotCloseActionTest {
    @Test
    fun `会话失效类关闭码要求重新鉴权`() {
        // 4006 无效 session id（无法继续 resume，请 identify）、4007 seq 错误
        assertEquals(QqBotCloseAction.REIDENTIFY, closeActionOf(4006))
        assertEquals(QqBotCloseAction.REIDENTIFY, closeActionOf(4007))
    }

    @Test
    fun `可 resume 的关闭码保留会话重连`() {
        // 4008 发送过快、4009 连接过期（官方：请重连并执行 resume）、4900~4913 内部错误
        listOf(4008, 4009, 4900, 4905, 4913).forEach { code ->
            assertEquals(QqBotCloseAction.RESUME, closeActionOf(code), "code=$code 应可重连")
        }
    }

    @Test
    fun `官方标注不可重试的关闭码为终态`() {
        // 无效 opcode / payload / shard / version / intent / intent 无权限 / 已下架 / 已封禁
        listOf(4001, 4002, 4010, 4011, 4012, 4013, 4014, 4914, 4915).forEach { code ->
            assertEquals(QqBotCloseAction.TERMINAL, closeActionOf(code), "code=$code 应停止重连")
        }
    }

    @Test
    fun `未知关闭码保守按可重连处理`() {
        listOf(1000, 1006, 0, 9999).forEach { code ->
            assertEquals(QqBotCloseAction.RESUME, closeActionOf(code), "code=$code 应保守重连")
        }
    }
}
