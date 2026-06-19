package top.colter.dynamic.qqbot

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkFeature
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<QqBotGatewayPlugin>()
internal val QQBOT_PLATFORM_ID: PlatformId = PlatformId.of("qq")
internal const val QQBOT_PLUGIN_ID: String = "qqbot-gateway"

public class QqBotGatewayPlugin :
    AccountRoutedMessageSinkPlugin,
    ConfigurablePlugin<QqBotConfig> {

    private var pluginId: String = QQBOT_PLUGIN_ID
    private var config: QqBotConfig = QqBotConfig()
    private var gateway: QqBotGateway = NoopQqBotGateway()
    private var incomingMessagePublisher: IncomingMessagePublisher = IncomingMessagePublisher { }
    private var running: Boolean = false

    override val configId: String
        get() = pluginId
    override val configName: String = "QQ 官方机器人"
    override val configDescription: String = "QQ 官方 API v2 WebSocket 与 OpenAPI 接入配置。"
    override val configClass = QqBotConfig::class
    override val configFormSpec = QqBotConfigForm.spec

    override val transportId: String = "qqbot"
    override val transportName: String = "QQ 官方机器人"
    override val supportedMessageFeatures: Set<MessageSinkFeature> = emptySet()
    override val supportedTargetPlatforms: Set<PlatformId> = setOf(QQBOT_PLATFORM_ID)
    override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        incomingMessagePublisher = context.incomingMessagePublisher
        config = context.configService.loadOrCreate(pluginId, QqBotConfigForm.migrations) { QqBotConfig() }
        QqBotConfigForm.validate(config)
        logger.info { "QQ 官方机器人配置已加载：pluginId=$pluginId enabled=${config.enabled}" }
    }

    override suspend fun onStart() {
        if (running) return

        QqBotConfigForm.validate(config)
        if (!config.enabled) {
            logger.info { "QQ 官方机器人插件未启用，跳过连接" }
            return
        }

        val nextGateway = createQqBotGateway(config)
        nextGateway.connect(::onIncomingEvent)
        gateway = nextGateway
        running = true
        logger.info { "QQ 官方机器人 WebSocket 网关已启动：appId=${config.appId}" }
    }

    override suspend fun onStop() {
        if (!running) return
        running = false
        gateway.close()
        gateway = NoopQqBotGateway()
        logger.info { "QQ 官方机器人插件已停止" }
    }

    override suspend fun onUnload() {
        onStop()
    }

    override fun currentConfig(): QqBotConfig = config

    override fun applyConfig(next: QqBotConfig): ConfigApplyResult {
        QqBotConfigForm.validate(next)
        val changed = next != config
        config = next
        return ConfigApplyResult(
            changed = changed,
            restartRequired = changed,
            restartTargets = if (changed) listOf("QQ 官方机器人插件") else emptyList(),
            message = if (changed) {
                "QQ 官方机器人配置已保存；请重启插件后生效"
            } else {
                "QQ 官方机器人配置未变化"
            },
        )
    }

    override suspend fun listMessageSinkRoutes(target: TargetAddress?): List<MessageSinkRoute> {
        if (target != null && !supportsTarget(target)) return emptyList()
        if (!running) return emptyList()
        return gateway.availableAccounts().map { account ->
            account.toRoute(if (running) account.state else MessageSinkRouteState.UNAVAILABLE)
        }
    }

    override suspend fun sendMessage(
        request: MessageDeliveryRequest,
        routeId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("QQ 官方机器人插件未运行")
        if (!supportsTarget(request.target)) {
            return MessageSendResult.failed("目标平台不是 QQ 或类型不受支持", retryable = false)
        }
        accountIdFromRoute(routeId)
            ?: return MessageSendResult.failed("QQ 官方机器人路线 ID 无效：$routeId", retryable = false)

        return when (QqBotTarget.fromAddress(request.target)) {
            is QqBotTarget.User,
            is QqBotTarget.Group,
            -> MessageSendResult.failed("QQ 官方机器人发送实现尚未接入", retryable = false)
            is QqBotTarget.Unsupported -> MessageSendResult.failed("目标类型不受支持", retryable = false)
        }
    }

    override suspend fun sendCommandResult(
        request: CommandResultSendRequest,
        routeId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("QQ 官方机器人插件未运行")
        if (!supportsTarget(request.target.address)) {
            return MessageSendResult.failed("目标平台不是 QQ 或类型不受支持", retryable = false)
        }
        accountIdFromRoute(routeId)
            ?: return MessageSendResult.failed("QQ 官方机器人路线 ID 无效：$routeId", retryable = false)
        return MessageSendResult.failed("QQ 官方机器人命令结果发送实现尚未接入", retryable = false)
    }

    override suspend fun recallMessage(request: MessageRecallRequest, routeId: String): MessageRecallResult {
        if (!running) return MessageRecallResult.failed("QQ 官方机器人插件未运行")
        if (!supportsTarget(request.target)) {
            return MessageRecallResult.failed("目标平台不是 QQ 或类型不受支持")
        }
        accountIdFromRoute(routeId)
            ?: return MessageRecallResult.failed("QQ 官方机器人路线 ID 无效：$routeId")
        return MessageRecallResult.unsupported()
    }

    override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> = emptyList()

    override suspend fun resolveMessageTarget(address: TargetAddress): MessageTargetCandidate? {
        if (!supportsTarget(address)) return null
        return MessageTargetCandidate(
            address = address,
            name = address.externalId,
        )
    }

    internal fun onIncomingEvent(event: QqBotIncomingEvent) {
        if (!running || !config.autoPublishIncoming) return
        logger.debug { "收到 QQ 官方机器人入站事件占位：type=${event.type}" }
        // TODO: 将 C2C_MESSAGE_CREATE / GROUP_AT_MESSAGE_CREATE 映射为 IncomingMessage 后发布。
        @Suppress("UNUSED_VARIABLE")
        val publisher = incomingMessagePublisher
    }

    private fun QqBotRuntimeAccount.toRoute(
        state: MessageSinkRouteState = MessageSinkRouteState.READY,
    ): MessageSinkRoute = MessageSinkRoute(
        routeId = routeId(accountId),
        transportId = transportId,
        transportName = transportName,
        targetPlatformId = QQBOT_PLATFORM_ID,
        accountId = accountId,
        accountName = name,
        enabled = true,
        state = state,
    )

    private fun routeId(accountId: String): String = "$transportId:${QQBOT_PLATFORM_ID.value}:$accountId"

    private fun accountIdFromRoute(routeId: String): String? {
        val prefix = "$transportId:${QQBOT_PLATFORM_ID.value}:"
        return routeId.removePrefix(prefix)
            .takeIf { it != routeId }
            ?.takeIf { it.isNotBlank() }
    }
}

internal fun createQqBotGateway(config: QqBotConfig): QqBotGateway {
    return JavaNetQqBotWebSocketGateway(config)
}
