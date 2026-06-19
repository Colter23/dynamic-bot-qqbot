package top.colter.dynamic.qqbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.colter.dynamic.core.command.CommandPublisher
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
    private var commandPublisher: CommandPublisher = CommandPublisher { }
    private var incomingMessagePublisher: IncomingMessagePublisher = IncomingMessagePublisher { }
    private var replyContextStore: QqBotReplyContextStore = QqBotReplyContextStore()
    private lateinit var pluginScope: CoroutineScope
    private var incomingScope: CoroutineScope? = null
    private var incomingJob: Job? = null
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
        commandPublisher = context.commandPublisher
        incomingMessagePublisher = context.incomingMessagePublisher
        pluginScope = context.scope
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
        startIncomingScope()
        runCatching {
            nextGateway.connect(::onIncomingEvent)
        }.onFailure { error ->
            stopIncomingScope()
            nextGateway.close()
            logger.error(error) { "QQ 官方机器人 WebSocket 网关启动失败：appId=${config.appId}" }
            throw error
        }
        gateway = nextGateway
        running = true
        logger.info { "QQ 官方机器人 WebSocket 网关已启动：appId=${config.appId}" }
    }

    override suspend fun onStop() {
        if (!running) return
        running = false
        stopIncomingScope()
        replyContextStore.clear()
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
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageSendResult.failed("QQ 官方机器人路线 ID 无效：$routeId", retryable = false)
        if (!isAccountReady(accountId)) {
            return MessageSendResult.failed("QQ 官方机器人账号不可用：$accountId")
        }

        val plan = QqBotMessageMapper.toSendPlan(request.batches)
        return when (val target = QqBotTarget.fromAddress(request.target)) {
            is QqBotTarget.User -> sendPlanWithReceipt(
                routeId = routeId,
                accountId = accountId,
                failureLabel = "QQ 官方机器人单聊消息发送失败",
                plan = plan,
            ) { item ->
                when (item) {
                    is QqBotSendPlanItem.Text -> gateway.sendUserMessage(
                        accountId = accountId,
                        openid = target.openid,
                        request = item.request,
                    )
                    is QqBotSendPlanItem.Media -> gateway.sendUserMediaMessage(
                        accountId = accountId,
                        openid = target.openid,
                        media = item.media,
                        request = item.request,
                    )
                }
            }
            is QqBotTarget.Group -> sendPlanWithReceipt(
                routeId = routeId,
                accountId = accountId,
                failureLabel = "QQ 官方机器人群聊消息发送失败",
                plan = plan,
            ) { item ->
                when (item) {
                    is QqBotSendPlanItem.Text -> gateway.sendGroupMessage(
                        accountId = accountId,
                        groupOpenid = target.groupOpenid,
                        request = item.request,
                    )
                    is QqBotSendPlanItem.Media -> gateway.sendGroupMediaMessage(
                        accountId = accountId,
                        groupOpenid = target.groupOpenid,
                        media = item.media,
                        request = item.request,
                    )
                }
            }
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
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageSendResult.failed("QQ 官方机器人路线 ID 无效：$routeId", retryable = false)
        if (!isAccountReady(accountId)) {
            return MessageSendResult.failed("QQ 官方机器人账号不可用：$accountId")
        }
        val replyToMessageId = replyContextStore.findReplyMessageId(
            traceId = request.inReplyTo,
            target = request.target.address,
            accountId = accountId,
        )
        val plan = QqBotMessageMapper.toSendPlan(
            batches = request.chain,
            replyToMessageId = replyToMessageId,
        )
        return when (val target = QqBotTarget.fromAddress(request.target.address)) {
            is QqBotTarget.User -> sendPlanWithReceipt(
                routeId = routeId,
                accountId = accountId,
                failureLabel = "QQ 官方机器人命令结果发送失败",
                plan = plan,
            ) { item ->
                when (item) {
                    is QqBotSendPlanItem.Text -> gateway.sendUserMessage(
                        accountId = accountId,
                        openid = target.openid,
                        request = item.request,
                    )
                    is QqBotSendPlanItem.Media -> gateway.sendUserMediaMessage(
                        accountId = accountId,
                        openid = target.openid,
                        media = item.media,
                        request = item.request,
                    )
                }
            }
            is QqBotTarget.Group -> sendPlanWithReceipt(
                routeId = routeId,
                accountId = accountId,
                failureLabel = "QQ 官方机器人命令结果发送失败",
                plan = plan,
            ) { item ->
                when (item) {
                    is QqBotSendPlanItem.Text -> gateway.sendGroupMessage(
                        accountId = accountId,
                        groupOpenid = target.groupOpenid,
                        request = item.request,
                    )
                    is QqBotSendPlanItem.Media -> gateway.sendGroupMediaMessage(
                        accountId = accountId,
                        groupOpenid = target.groupOpenid,
                        media = item.media,
                        request = item.request,
                    )
                }
            }
            is QqBotTarget.Unsupported -> MessageSendResult.failed("目标类型不受支持", retryable = false)
        }
    }

    override suspend fun recallMessage(request: MessageRecallRequest, routeId: String): MessageRecallResult {
        if (!running) return MessageRecallResult.failed("QQ 官方机器人插件未运行")
        if (!supportsTarget(request.target)) {
            return MessageRecallResult.failed("目标平台不是 QQ 或类型不受支持")
        }
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageRecallResult.failed("QQ 官方机器人路线 ID 无效：$routeId")
        if (!isAccountReady(accountId)) {
            return MessageRecallResult.failed("QQ 官方机器人账号不可用：$accountId")
        }
        val messageId = request.sinkMessageId.trim()
        if (messageId.isBlank()) {
            return MessageRecallResult.failed("QQ 官方机器人消息 ID 为空，无法撤回")
        }

        return when (val target = QqBotTarget.fromAddress(request.target)) {
            is QqBotTarget.User -> recallWithResult("QQ 官方机器人单聊消息撤回失败") {
                gateway.recallUserMessage(
                    accountId = accountId,
                    openid = target.openid,
                    messageId = messageId,
                )
            }
            is QqBotTarget.Group -> recallWithResult("QQ 官方机器人群聊消息撤回失败") {
                gateway.recallGroupMessage(
                    accountId = accountId,
                    groupOpenid = target.groupOpenid,
                    messageId = messageId,
                )
            }
            is QqBotTarget.Unsupported -> MessageRecallResult.failed(target.reason)
        }
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
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(event)
        if (incoming == null) {
            logger.debug { "忽略 QQ 官方机器人入站事件：type=${event.type}" }
            return
        }

        val commandRequest = QqBotCommandMapper.toCommandRequest(
            sourcePlugin = pluginId,
            incoming = incoming,
        )
        val scope = incomingScope ?: return
        if (commandRequest != null) {
            replyContextStore.remember(commandRequest.traceId, incoming)
        }
        scope.launch {
            if (!running) return@launch
            runCatching {
                incomingMessagePublisher.publish(incoming)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                logger.warn(error) {
                    "QQ 官方机器人入站消息提交失败：type=${event.type} messageId=${incoming.messageId.ifBlank { "-" }}"
                }
            }
            if (commandRequest != null) {
                try {
                    commandPublisher.publish(commandRequest)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.warn(error) { "QQ 官方机器人命令发布失败：traceId=${commandRequest.traceId}" }
                }
            }
        }
    }

    private fun startIncomingScope() {
        stopIncomingScope()
        val parentJob = pluginScope.coroutineContext[Job]
        val job = if (parentJob != null) SupervisorJob(parentJob) else SupervisorJob()
        incomingJob = job
        incomingScope = CoroutineScope(pluginScope.coroutineContext.minusKey(Job) + job)
    }

    private fun stopIncomingScope() {
        incomingJob?.cancel("QQ 官方机器人插件已停止")
        incomingJob = null
        incomingScope = null
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

    private suspend fun isAccountReady(accountId: String): Boolean {
        return gateway.availableAccounts().any {
            it.accountId == accountId && it.state == MessageSinkRouteState.READY
        }
    }

    private suspend fun sendPlanWithReceipt(
        routeId: String,
        accountId: String,
        failureLabel: String,
        plan: List<QqBotSendPlanItem>,
        send: suspend (QqBotSendPlanItem) -> QqBotSendReceipt,
    ): MessageSendResult {
        val receipts = mutableListOf<QqBotSendReceipt>()
        plan.forEach { item ->
            val receipt = runCatching { send(item) }
                .getOrElse { error ->
                    return MessageSendResult.failed(
                        reason = error.message ?: failureLabel,
                        partialSent = receipts.isNotEmpty(),
                    )
                }
            receipts += receipt
        }
        return MessageSendResult.sent(
            sinkMessageId = receipts.firstOrNull()?.messageId,
            sinkRouteId = routeId,
            sinkAccountId = accountId,
            sinkTransportId = transportId,
            sinkMessageIds = receipts.map { it.messageId },
        )
    }

    private suspend fun recallWithResult(
        failureLabel: String,
        recall: suspend () -> Unit,
    ): MessageRecallResult {
        return runCatching { recall() }
            .fold(
                onSuccess = { MessageRecallResult.recalled() },
                onFailure = { error -> MessageRecallResult.failed(error.message ?: failureLabel) },
            )
    }

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
