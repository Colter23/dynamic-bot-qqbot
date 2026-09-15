package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.colter.dynamic.core.config.MutableConfigDocument

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
    fun `迁移会移除已废弃的事件订阅配置`() {
        val document = FakeMutableConfigDocument(
            mutableMapOf(
                "enabled" to true,
                "appId" to "123456",
                "appSecret" to "secret",
                "intents" to listOf("GROUP_AND_C2C_EVENT", "INTERACTION"),
            ),
        )

        QqBotConfigForm.migrations.forEach { migration -> migration.apply(document) }

        // 残留旧键会让配置加载失败（开启了 FAIL_ON_UNKNOWN_PROPERTIES）
        assertEquals(setOf("enabled", "appId", "appSecret"), document.keys())
    }

    @Test
    fun `迁移会移除正式版已删除的旧字段`() {
        val document = FakeMutableConfigDocument(
            mutableMapOf(
                "enabled" to true,
                "appId" to "123456",
                "sandbox" to true,
                "debugLogPayloads" to true,
                "debugEchoText" to "ping",
                "debugEchoReply" to "pong",
                "debugImageEchoText" to "发图",
                "debugImageEchoSource" to "https://example.com/a.png",
                "debugImageEchoCaption" to "说明",
            ),
        )

        QqBotConfigForm.migrations.forEach { migration -> migration.apply(document) }

        assertEquals(setOf("enabled", "appId"), document.keys())
    }
}

private class FakeMutableConfigDocument(
    private val values: MutableMap<String, Any?>,
) : MutableConfigDocument {
    fun keys(): Set<String> = values.keys.toSet()

    override fun contains(path: String): Boolean = values.containsKey(path)

    override fun get(path: String): Any? = values[path]

    override fun set(path: String, value: Any?) {
        values[path] = value
    }

    override fun remove(path: String): Boolean = values.remove(path) != null

    override fun move(from: String, to: String, overwrite: Boolean): Boolean {
        if (!values.containsKey(from)) return false
        if (!overwrite && values.containsKey(to)) return false
        values[to] = values.remove(from)
        return true
    }
}
