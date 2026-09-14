package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class PluginBoundaryTest {
    @Test
    fun `插件源码不应导入运行时或存储实现`() {
        val roots = listOf(Path.of("src/main/kotlin"), Path.of("src/test/kotlin"))
        val forbiddenImports = listOf(
            "top.colter.dynamic.core." + "repository",
            "top.colter.dynamic.core." + "table",
            "top.colter.dynamic." + "repository",
            "top.colter.dynamic." + "table",
            "top.colter.dynamic." + "plugin",
            "top.colter.dynamic." + "event",
        )

        val offenders = roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .flatMap { path ->
                        val content = Files.readString(path)
                        forbiddenImports
                            .filter { content.contains(it) }
                            .map { "${root.relativize(path)} -> $it" }
                            .stream()
                    }
                    .toList()
            }
        }
            .sorted()

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `平台 ID 与 OneBot 的 qq 分离且不接管对方目标`() {
        val plugin = QqBotGatewayPlugin()

        // 平台 ID 固定为 qq-official：OneBot 插件占用 qq，两者目标标识空间不通用
        assertEquals("qq-official", QQBOT_PLATFORM_ID.value)
        assertEquals(setOf(QQBOT_PLATFORM_ID), plugin.supportedTargetPlatforms)
        assertEquals(setOf(TargetKind.GROUP, TargetKind.USER), plugin.supportedTargetKinds)

        // 自己的平台可投递
        assertEquals(
            true,
            plugin.supportsTarget(TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid")),
        )
        assertEquals(
            true,
            plugin.supportsTarget(TargetAddress.of("qq-official", TargetKind.USER, "user-openid")),
        )

        // 必须拒绝 OneBot 的 qq 平台目标，否则两插件的账号路线会进入同一候选池
        assertEquals(
            false,
            plugin.supportsTarget(TargetAddress.of("qq", TargetKind.GROUP, "123456")),
        )
        assertEquals(
            false,
            plugin.supportsTarget(TargetAddress.of("qq", TargetKind.USER, "123456")),
        )

        // 不支持的平台与目标类型
        assertEquals(
            false,
            plugin.supportsTarget(TargetAddress.of("bilibili", TargetKind.USER, "uid-1")),
        )
        assertEquals(
            false,
            plugin.supportsTarget(TargetAddress.of("qq-official", TargetKind.CHANNEL, "channel-1")),
        )
    }
}
