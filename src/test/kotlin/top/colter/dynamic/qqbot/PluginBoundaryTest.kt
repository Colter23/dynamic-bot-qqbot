package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
