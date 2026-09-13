package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
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

    @Test
    fun `调试图片回显需要图片资源`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(QqBotConfig(debugImageEchoText = "发图"))
        }
    }

    @Test
    fun `调试图片回显接受 HTTP 图片资源`() {
        QqBotConfigForm.validate(
            QqBotConfig(
                debugImageEchoText = "发图",
                debugImageEchoSource = "https://example.com/a.png",
            ),
        )
    }

    @Test
    fun `调试图片回显接受本地图片资源`() {
        val image = createTempImage()
        QqBotConfigForm.validate(
            QqBotConfig(
                debugImageEchoText = "发图",
                debugImageEchoSource = image.toUri().toString(),
            ),
        )
    }

    @Test
    fun `调试图片回显接受 Windows 盘符路径`() {
        // `D:/x.png` 会被 URI 解析成 scheme=D，盘符路径必须在该分支前识别
        val image = createTempImage()
        QqBotConfigForm.validate(
            QqBotConfig(
                debugImageEchoText = "发图",
                debugImageEchoSource = image.toString().replace('\\', '/'),
            ),
        )
        QqBotConfigForm.validate(
            QqBotConfig(
                debugImageEchoText = "发图",
                debugImageEchoSource = image.toString(),
            ),
        )
    }

    @Test
    fun `调试图片回显拒绝不存在的本地路径`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(
                QqBotConfig(
                    debugImageEchoText = "发图",
                    debugImageEchoSource = "file:///qqbot/missing/image.png",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            QqBotConfigForm.validate(
                QqBotConfig(
                    debugImageEchoText = "发图",
                    debugImageEchoSource = "D:/qqbot/missing/image.png",
                ),
            )
        }
    }

    private fun createTempImage(): Path {
        val dir = Files.createTempDirectory("qqbot-config-test")
        val file = dir.resolve("image.png")
        Files.write(file, byteArrayOf(1, 2, 3, 4))
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        return file
    }
}
