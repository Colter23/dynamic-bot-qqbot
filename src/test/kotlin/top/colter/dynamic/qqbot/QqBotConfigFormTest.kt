package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertFailsWith

class QqBotConfigFormTest {
    @Test
    fun `default disabled config is valid`() {
        QqBotConfigForm.validate(QqBotConfig())
    }

    @Test
    fun `enabled config requires app credentials`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(QqBotConfig(enabled = true))
        }
    }

    @Test
    fun `enabled config accepts minimal credentials`() {
        QqBotConfigForm.validate(
            QqBotConfig(
                enabled = true,
                appId = "123456",
                appSecret = "secret",
            ),
        )
    }
}
