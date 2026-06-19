package top.colter.dynamic.qqbot

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigMigration

public data class QqBotConfig(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val sandbox: Boolean = false,
    val intents: List<String> = listOf(QqBotIntent.GROUP_AND_C2C_EVENT.name),
    val reconnect: Boolean = true,
    val autoPublishIncoming: Boolean = true,
    val debugLogPayloads: Boolean = false,
    val debugEchoText: String = "",
    val debugEchoReply: String = "QQ 官方机器人 WebSocket 连通性正常",
)

public enum class QqBotIntent(public val bit: Int) {
    GROUP_AND_C2C_EVENT(1 shl 25),
    INTERACTION(1 shl 26),
    MESSAGE_AUDIT(1 shl 27),
}

internal fun QqBotConfig.enabledIntentMask(): Int {
    return normalizedIntents().fold(0) { mask, intent -> mask or intent.bit }
}

internal fun QqBotConfig.normalizedIntents(): List<QqBotIntent> {
    return intents
        .map { raw ->
            parseQqBotIntent(raw)
                ?: throw IllegalArgumentException("QQ 官方机器人事件订阅不支持：$raw")
        }
        .distinct()
}

private fun parseQqBotIntent(value: String): QqBotIntent? {
    return QqBotIntent.entries.firstOrNull { it.name == value.trim() }
}

public object QqBotConfigForm {
    public val migrations: List<ConfigMigration> = listOf()

    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "QQ 官方机器人",
        description = "QQ 官方 API v2 接入配置，用于连接 WebSocket 网关并通过 OpenAPI 发送单聊/群聊消息。",
        fields = listOf(
            ConfigFieldSpec(
                path = "enabled",
                label = "启用插件",
                type = ConfigFieldType.BOOLEAN,
                section = "连接",
                description = "开启后插件会连接 QQ 官方 WebSocket，并接入单聊/群聊消息收发。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "appId",
                label = "AppID",
                type = ConfigFieldType.TEXT,
                section = "连接",
                description = "QQ 开放平台分配的机器人 AppID。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "appSecret",
                label = "AppSecret",
                type = ConfigFieldType.SECRET,
                section = "连接",
                description = "QQ 开放平台分配的机器人密钥，用于获取访问令牌。",
                secret = true,
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "sandbox",
                label = "沙箱环境",
                type = ConfigFieldType.BOOLEAN,
                section = "连接",
                description = "预留配置；后续如果接入沙箱地址，在这里切换。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "intents",
                label = "事件订阅",
                type = ConfigFieldType.JSON,
                section = "事件",
                description = "QQ 官方 WebSocket Identify 时请求的事件订阅；初期只需要 GROUP_AND_C2C_EVENT。",
                metadata = mapOf(
                    "example" to """
                        [
                          "GROUP_AND_C2C_EVENT",
                          "INTERACTION"
                        ]
                    """.trimIndent(),
                ),
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "reconnect",
                label = "自动重连",
                type = ConfigFieldType.BOOLEAN,
                section = "事件",
                description = "WebSocket 断开后是否自动重连。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "autoPublishIncoming",
                label = "发布入站消息",
                type = ConfigFieldType.BOOLEAN,
                section = "事件",
                description = "收到单聊或群聊事件后，是否发布给 dynamic-bot 的普通入站消息管线。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "debugLogPayloads",
                label = "记录原始事件包",
                type = ConfigFieldType.BOOLEAN,
                section = "调试",
                description = "开启后会在日志中记录收到的 WebSocket 原始载荷片段，排查连通性时临时使用。",
                restartRequired = false,
            ),
            ConfigFieldSpec(
                path = "debugEchoText",
                label = "调试回显触发文本",
                type = ConfigFieldType.TEXT,
                section = "调试",
                description = "填写后，当单聊或群聊收到完全相同的文本时，会直接回复调试回显内容；留空表示关闭。",
                restartRequired = false,
            ),
            ConfigFieldSpec(
                path = "debugEchoReply",
                label = "调试回显回复内容",
                type = ConfigFieldType.TEXT,
                section = "调试",
                description = "触发调试回显时发送的文本。",
                restartRequired = false,
            ),
        ),
    )

    public fun validate(config: QqBotConfig) {
        if (config.enabled) {
            require(config.appId.isNotBlank()) { "启用 QQ 官方机器人插件时必须填写 AppID" }
            require(config.appSecret.isNotBlank()) { "启用 QQ 官方机器人插件时必须填写 AppSecret" }
            require(config.intents.isNotEmpty()) { "启用 QQ 官方机器人插件时至少需要一个事件订阅" }
        }
        config.intents.forEach { intent ->
            require(intent.isNotBlank()) { "QQ 官方机器人事件订阅不能为空" }
        }
        require(config.debugEchoReply.isNotBlank()) { "QQ 官方机器人调试回显回复内容不能为空" }
        config.normalizedIntents()
    }
}
