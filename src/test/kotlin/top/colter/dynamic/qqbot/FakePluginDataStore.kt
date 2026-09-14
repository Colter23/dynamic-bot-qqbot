package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.PluginDataStore

/**
 * 复刻主程序 `YamlDocumentStore` 的两条关键约束：
 *
 * 1. **值按类型存取**：存 [String] 会被写成 YAML 标量，真实实现读取时直接报
 *    "根节点必须是对象"；
 * 2. **存取走序列化往返**：返回的对象与存进去的**不共享状态**，之后的修改不会被"存储"看见。
 *
 * 早期版本用 `value.toString()` 存、再强转回去，等于把"存字符串"这个错误行为合法化，
 * 因此掩盖了真实缺陷。这里显式还原真实行为，让回归能被捕获。
 */
internal class FakePluginDataStore : PluginDataStore {
    private val values = mutableMapOf<String, Any>()

    /** 落盘次数，用于断言"重复 sighting 不产生写放大"。 */
    var saveCount: Int = 0
        private set

    override val dataDir: Path = TEST_DATA_DIR

    fun savedValue(name: String): Any? = values[name]

    override fun <T : Any> loadOrCreate(
        name: String,
        clazz: kotlin.reflect.KClass<T>,
        migrations: List<ConfigMigration>,
        defaultProvider: () -> T,
    ): T {
        val stored = values[name] ?: return defaultProvider()
        require(clazz.isInstance(stored)) { "存储类型不匹配：${stored::class}" }
        @Suppress("UNCHECKED_CAST")
        return snapshot(stored) as T
    }

    override fun <T : Any> save(name: String, value: T) {
        require(value !is String) { "文件根节点必须是对象" }
        saveCount += 1
        values[name] = snapshot(value)
    }

    override fun <T : Any> reload(
        name: String,
        clazz: kotlin.reflect.KClass<T>,
        migrations: List<ConfigMigration>,
    ): T {
        val stored = values[name] ?: error("未保存的存储：$name")
        @Suppress("UNCHECKED_CAST")
        return snapshot(stored) as T
    }

    override fun exists(name: String): Boolean = values.containsKey(name)

    override fun delete(name: String): Boolean = values.remove(name) != null

    override fun resolvePath(name: String): Path = dataDir.resolve("$name.yml")

    /** 模拟真实存储的序列化往返：返回与入参不共享状态的新实例。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> snapshot(value: T): T {
        if (value !is QqBotTargetLedgerState) return value
        val encoded = Json.encodeToString(QqBotTargetLedgerState.serializer(), value)
        return Json.decodeFromString(QqBotTargetLedgerState.serializer(), encoded) as T
    }

    private companion object {
        /** 整个测试运行共用一个临时目录，避免每个实例都新建临时目录且不清理。 */
        private val TEST_DATA_DIR: Path = Files.createTempDirectory("qqbot-plugin-test")
    }
}
