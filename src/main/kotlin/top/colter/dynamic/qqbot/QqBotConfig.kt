package top.colter.dynamic.qqbot

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigMigration

public data class QqBotConfig(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val reconnect: Boolean = true,
    val autoPublishIncoming: Boolean = true,
)

/**
 * 订阅的事件类别掩码，固定为 `GROUP_AND_C2C_EVENT`（`1 shl 25`）。
 *
 * 该位覆盖插件实际处理的全部事件：`C2C_MESSAGE_CREATE`、`GROUP_AT_MESSAGE_CREATE`、
 * `GROUP_MESSAGE_CREATE`（群消息全量模式，需机器人在后台开启"接收所有消息"，同属该位）、
 * `GROUP_ADD_ROBOT` / `GROUP_DEL_ROBOT`、`FRIEND_ADD` / `FRIEND_DEL`、
 * `C2C_MSG_RECEIVE` / `C2C_MSG_REJECT`、`GROUP_MSG_RECEIVE` / `GROUP_MSG_REJECT`。
 *
 * 刻意不做成配置项：
 * - 其余位（`INTERACTION`、`MESSAGE_AUDIT`，以及频道/论坛/语音等场景）插件均未实现，
 *   勾选后事件会被静默忽略；
 * - 官方明确"鉴权时传递**无权限的 intents**，websocket 会报错并直接关闭连接"，
 *   开放这个配置只会让用户踩空，还连带把连接搞坏。
 *
 * 将来实现按钮交互等能力时再按需开放，并配套权限校验与错误提示。
 */
internal const val QQBOT_INTENT_MASK: Int = 1 shl 25

public object QqBotConfigForm {
    /**
     * 正式版移除的字段：沙箱预留项与调试回显/原始载荷开关。
     * 配置加载开启了 `FAIL_ON_UNKNOWN_PROPERTIES`，残留旧键会导致加载失败，必须先删除。
     */
    private val REMOVED_LEGACY_FIELDS: List<String> = listOf(
        "sandbox",
        "debugLogPayloads",
        "debugEchoText",
        "debugEchoReply",
        "debugImageEchoText",
        "debugImageEchoSource",
        "debugImageEchoCaption",
    )

    public val migrations: List<ConfigMigration> = listOf(
        ConfigMigration(
            id = "2026-09-14-remove-debug-fields",
            description = "移除沙箱预留配置与调试回显相关字段",
        ) {
            REMOVED_LEGACY_FIELDS.forEach(::remove)
        },
        ConfigMigration(
            id = "2026-09-15-remove-intents",
            description = "移除事件订阅配置：订阅类别已固定为 GROUP_AND_C2C_EVENT",
        ) {
            remove("intents")
        },
    )

    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "QQ 官方机器人",
        description = "接入 QQ 官方机器人，在 QQ 群与单聊中收发消息。",
        fields = listOf(
            ConfigFieldSpec(
                path = "enabled",
                label = "启用插件",
                type = ConfigFieldType.BOOLEAN,
                section = "基础配置",
                description = "开启后插件会连接 QQ 官方 WebSocket，并接入单聊/群聊消息收发。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "appId",
                label = "AppID",
                type = ConfigFieldType.TEXT,
                section = "基础配置",
                description = "QQ 开放平台分配的机器人 AppID。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "appSecret",
                label = "AppSecret",
                type = ConfigFieldType.SECRET,
                section = "基础配置",
                description = "QQ 开放平台分配的机器人密钥，用于获取访问令牌。",
                secret = true,
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "reconnect",
                label = "自动重连",
                type = ConfigFieldType.BOOLEAN,
                section = "基础配置",
                description = "WebSocket 断开后是否自动重连。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
            ConfigFieldSpec(
                path = "autoPublishIncoming",
                label = "发布入站消息",
                type = ConfigFieldType.BOOLEAN,
                section = "基础配置",
                description = "收到单聊或群聊事件后，是否发布给 dynamic-bot 的普通入站消息管线。",
                restartRequired = true,
                restartTarget = "QQ 官方机器人插件",
            ),
        ),
    )

    public fun validate(config: QqBotConfig) {
        if (!config.enabled) return
        require(config.appId.isNotBlank()) { "启用 QQ 官方机器人插件时必须填写 AppID" }
        require(config.appSecret.isNotBlank()) { "启用 QQ 官方机器人插件时必须填写 AppSecret" }
    }
}
