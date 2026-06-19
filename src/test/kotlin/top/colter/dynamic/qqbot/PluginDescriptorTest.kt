package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginDescriptorTest {
    @Test
    fun `plugin descriptor points to qq bot plugin`() {
        val content = Files.readString(Path.of("src/main/resources/plugin.yml"))

        assertTrue(content.contains("id: qqbot-gateway"))
        assertTrue(content.contains("mainClass: top.colter.dynamic.qqbot.QqBotGatewayPlugin"))
        assertTrue(content.contains("apiVersion: 1.0.0"))
    }
}
