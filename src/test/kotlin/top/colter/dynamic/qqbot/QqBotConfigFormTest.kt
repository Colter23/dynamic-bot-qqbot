package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertFailsWith

class QqBotConfigFormTest {
    @Test
    fun `默认未启用配置是有效的`() {
        QqBotConfigForm.validate(QqBotConfig())
    }

    @Test
    fun `启用配置需要应用凭据`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(QqBotConfig(enabled = true))
        }
    }

    @Test
    fun `启用配置接受最小凭据`() {
        QqBotConfigForm.validate(
            QqBotConfig(
                enabled = true,
                appId = "123456",
                appSecret = "secret",
            ),
        )
    }

    @Test
    fun `调试回显回复内容不能为空`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(QqBotConfig(debugEchoReply = "   "))
        }
    }
}
