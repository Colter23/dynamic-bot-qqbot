package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import top.colter.dynamic.core.plugin.CORE_PLUGIN_API_VERSION

class PluginDescriptorTest {
    @Test
    fun `插件描述文件指向 QQ 官方机器人插件`() {
        val content = Files.readString(Path.of("src/main/resources/plugin.yml"))

        assertTrue(content.contains("id: qqbot-gateway"))
        assertTrue(content.contains("mainClass: top.colter.dynamic.qqbot.QqBotGatewayPlugin"))
        assertTrue(content.contains("apiVersion: $CORE_PLUGIN_API_VERSION"))
    }
}
