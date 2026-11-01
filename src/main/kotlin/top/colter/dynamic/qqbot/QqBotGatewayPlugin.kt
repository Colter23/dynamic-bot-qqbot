package top.colter.dynamic.qqbot

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkFeature
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvice
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdviceRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvisor
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryConfidence
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryMethod
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryModel
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeResult
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<QqBotGatewayPlugin>()
/**
 * QQ 官方机器人独占的平台 ID。
 *
 * 刻意与 OneBot 插件的 `qq` 区分：两者的目标标识完全不通（官方是 openid，OneBot 是 QQ 号）。
 * 若共用同一平台 ID，投递时出口插件选择只校验平台、不校验目标 ID 归属，两个插件的账号路线
 * 会进入同一个候选池，导致先尝试不匹配的插件、失败后才回退，并存在误投风险。
 */
internal val QQBOT_PLATFORM_ID: PlatformId = PlatformId.of("qq-official")
internal const val QQBOT_PLUGIN_ID: String = "qqbot-gateway"

public class QqBotGatewayPlugin :
    AccountRoutedMessageSinkPlugin,
    MessageSinkMediaDeliveryAdvisor,
    ConfigurablePlugin<QqBotConfig> {

    private var pluginId: String = QQBOT_PLUGIN_ID
    private var config: QqBotConfig = QqBotConfig()
    private var gateway: QqBotGateway = NoopQqBotGateway()
    private var incomingMessagePublisher: IncomingMessagePublisher = IncomingMessagePublisher { }
    private lateinit var pluginScope: CoroutineScope
    private var incomingScope: CoroutineScope? = null
    private var incomingJob: Job? = null
    private var running: Boolean = false
    private val retryReplySeqKeeper = QqBotReplySeqKeeper()
    private val messageIndexKeeper = QqBotMessageIndexKeeper()
    private val mentionAllQuota = QqBotMentionAllQuota()
    private val groupInfoRateLimiter = QqBotIntervalRateLimiter(GROUP_INFO_REQUEST_INTERVAL_MILLIS)
    private var targetLedger: QqBotTargetLedger? = null

    override val configId: String
        get() = pluginId
    override val configName: String = "QQ 官方机器人"
    override val configDescription: String = "QQ 官方机器人消息收发配置。"
    override val configClass = QqBotConfig::class
    override val configFormSpec = QqBotConfigForm.spec

    override val transportId: String = "qqbot"
    override val transportName: String = "QQ 官方机器人"
    override val supportedMessageFeatures: Set<MessageSinkFeature> = emptySet()
    override val supportedTargetPlatforms: Set<PlatformId> = setOf(QQBOT_PLATFORM_ID)
    override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

    /**
     * 本地图片/视频由插件自己分片上传给 QQ，不需要主程序改写成签名链接或客户端可读路径。
     *
     * 主程序的媒体交付 profile（如指向内网地址的签名链接）对 QQ 官方是无效的——腾讯服务器
     * 取不到内网地址，会以"富媒体文件下载失败"告终。
     */
    override val mediaDeliveryModel: MessageSinkMediaDeliveryModel = MessageSinkMediaDeliveryModel.SELF_MANAGED

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        incomingMessagePublisher = context.incomingMessagePublisher
        pluginScope = context.scope
        config = context.configService.loadOrCreate(pluginId, QqBotConfigForm.migrations) { QqBotConfig() }
        QqBotConfigForm.validate(config)
        targetLedger = runCatching {
            QqBotTargetLedger(dataStore = context.dataStore)
        }.onFailure { error ->
            logger.warn(error) { "QQ 官方机器人目标账本初始化失败，目标列表将从空开始积累" }
        }.getOrNull()
        logger.info { "QQ 官方机器人配置已加载：pluginId=$pluginId enabled=${config.enabled} targets=${targetLedger?.size() ?: 0}" }
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
        // 连接建立过程中服务端已经可能推送事件；先进入接收状态，避免丢掉首条消息。
        gateway = nextGateway
        running = true
        runCatching {
            nextGateway.connect(::onIncomingEvent)
        }.onFailure { error ->
            running = false
            gateway = NoopQqBotGateway()
            stopIncomingScope()
            nextGateway.close()
            logger.error(error) { "QQ 官方机器人 WebSocket 网关启动失败：appId=${config.appId}" }
            throw error
        }
        logger.info { "QQ 官方机器人 WebSocket 网关已启动：appId=${config.appId}" }
        startStartupTasks()
    }

    override suspend fun onStop() {
        if (!running) return
        running = false
        stopIncomingScope()
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
        request: MessageSendRequest,
        routeId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("QQ 官方机器人插件未运行")
        if (!supportsTarget(request.target)) {
            return MessageSendResult.failed("目标平台不是 QQ 官方机器人或类型不受支持", retryable = false)
        }
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageSendResult.failed("QQ 官方机器人路线 ID 无效：$routeId", retryable = false)
        if (!isAccountReady(accountId)) {
            return MessageSendResult.failed("QQ 官方机器人账号不可用：$accountId")
        }

        val target = QqBotTarget.fromAddress(request.target)
        // 平台限制：群聊 @全体成员 每群每自然日 1 次，且只有文本消息支持；
        // 先占配额，同群当天后续推送直接降级为纯文本，避免发送必然被拒的请求。
        val mentionAllAllowed = reserveMentionAll(request, target)
        // 引用展示需要消息自身事件里的 msg_idx，主项目只给得出被动回复用的 d.id，故先补引用
        val plan = messageIndexKeeper.apply(
            replyToMessageId = request.replyToMessageId,
            plan = QqBotMessageMapper.toSendPlan(
                batches = request.batches,
                replyToMessageId = request.replyToMessageId,
                mentionAllAllowed = mentionAllAllowed,
            ),
        )
        return when (target) {
            is QqBotTarget.User -> sendPlanWithReceipt(
                routeId = routeId,
                accountId = accountId,
                failureLabel = "QQ 官方机器人单聊消息发送失败",
                target = request.target,
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
                target = request.target,
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

    override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
        val ledger = targetLedger ?: return emptyList()
        if (!running) return emptyList()
        val candidates = ledger.listCandidates(kind)
        if (candidates.isEmpty()) return candidates
        val sources = gateway.availableAccounts()
            .filter { it.state == MessageSinkRouteState.READY }
            .map { it.toRoute().toTargetSource() }
        return candidates.map { candidate -> candidate.copy(sources = sources) }
    }

    override suspend fun resolveMessageTarget(address: TargetAddress): MessageTargetCandidate? {
        if (!supportsTarget(address)) return null
        val ledgerCandidate = targetLedger?.findCandidate(address)
        if (ledgerCandidate != null) return ledgerCandidate
        // 账本未见过（例如订阅由其他渠道创建）：回显 externalId，至少让后台能显示
        return MessageTargetCandidate(
            address = address.copy(accountId = address.accountId?.trim()?.takeIf { it.isNotBlank() }),
            name = address.externalId,
        )
    }

    override suspend fun adviseMediaDelivery(
        request: MessageSinkMediaDeliveryAdviceRequest,
    ): MessageSinkMediaDeliveryAdvice {
        if (!running) return MessageSinkMediaDeliveryAdvice()
        if (request.accountIdFromRequest() == null) return MessageSinkMediaDeliveryAdvice()
        return MessageSinkMediaDeliveryAdvice(
            clientName = transportName,
            localFileConfidence = MessageSinkMediaDeliveryConfidence.CONFIRMED,
        )
    }

    override suspend fun probeMediaDelivery(
        request: MessageSinkMediaDeliveryProbeRequest,
    ): MessageSinkMediaDeliveryProbeResult {
        if (!running) return MessageSinkMediaDeliveryProbeResult.unknown("QQ 官方机器人插件未运行")
        if (request.accountIdFromRequest() == null) {
            return MessageSinkMediaDeliveryProbeResult.unknown("缺少 QQ 官方机器人账号")
        }
        return when (request.method) {
            MessageSinkMediaDeliveryMethod.LOCAL_FILE -> {
                val path = QqBotMessageMapper.resolveLocalFile(request.uri)
                if (path != null) {
                    MessageSinkMediaDeliveryProbeResult.available("本地文件可通过分片上传发送：$path")
                } else {
                    MessageSinkMediaDeliveryProbeResult.unavailable("本地文件不可读：${request.uri}")
                }
            }
            MessageSinkMediaDeliveryMethod.SIGNED_URL -> MessageSinkMediaDeliveryProbeResult.unknown(
                "QQ 官方机器人优先使用分片上传发送本地文件，无需签名链接",
            )
        }
    }

    internal fun onIncomingEvent(event: QqBotIncomingEvent) {
        if (!running) return
        logger.debug {
            "QQ 官方机器人收到入站事件：type=${event.type} account=${event.botAccountId.orEmpty().ifBlank { "-" }}"
        }
        recordTargetSighting(event)
        // 留存消息自身的引用索引：主项目稍后回复该消息时只有 d.id，引用需要 msg_idx
        runCatching { QqBotIncomingMessageMapper.toReplyReference(event) }
            .getOrNull()
            ?.let { messageIndexKeeper.remember(it.messageId, it.messageIndex) }
        val incoming = runCatching { QqBotIncomingMessageMapper.toIncomingMessage(event) }
            .onFailure { error ->
                logger.warn(error) { "QQ 官方机器人入站消息解析失败：type=${event.type}" }
            }
            .getOrNull()
        if (incoming == null) {
            logger.debug { "忽略 QQ 官方机器人入站事件：type=${event.type}" }
            return
        }
        logger.debug {
            "QQ 官方机器人入站消息已解析：messageId=${incoming.messageId.ifBlank { "-" }} target=${incoming.target.kind}:${incoming.target.externalId} sender=${incoming.senderId} ${incoming.mediaSummary()} text=${incoming.text.take(MAX_LOG_TEXT_CHARS)}"
        }

        val scope = incomingScope ?: return
        if (!config.autoPublishIncoming) return

        scope.launch {
            if (!running) return@launch
            runCatching {
                incomingMessagePublisher.publish(
                    IncomingMessagePublishRequest(
                        message = incoming,
                        traceId = event.traceId(incoming),
                        replyToMessageId = incoming.messageId,
                        receivedAtEpochSeconds = System.currentTimeMillis() / 1000,
                        sourceEventId = event.sourceEventId(incoming.messageId),
                        dedupeKey = event.dedupeKey(incoming),
                    ),
                )
                logger.debug {
                    "QQ 官方机器人入站消息已提交主项目：messageId=${incoming.messageId.ifBlank { "-" }} target=${incoming.target.stableValue()}"
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                logger.warn(error) {
                    "QQ 官方机器人入站消息提交失败：type=${event.type} messageId=${incoming.messageId.ifBlank { "-" }}"
                }
            }
        }
    }

    private fun recordTargetSighting(event: QqBotIncomingEvent) {
        val ledger = targetLedger ?: return
        val sighting = runCatching { QqBotIncomingMessageMapper.toTargetSighting(event) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人目标解析失败：type=${event.type}" } }
            .getOrNull()
            ?: return
        val removed = event.type == QQBOT_GROUP_DEL_ROBOT || event.type == QQBOT_FRIEND_DEL
        val changed = runCatching {
            if (removed) {
                ledger.markRemoved(sighting.kind, sighting.externalId)
            } else {
                ledger.record(sighting.kind, sighting.externalId)
            }
        }.onFailure { error ->
            logger.warn(error) { "QQ 官方机器人目标账本记录失败：type=${event.type}" }
        }.getOrDefault(false)
        if (changed) {
            logger.info {
                "QQ 官方机器人目标账本已${if (removed) "移除" else "新增"}：${sighting.kind}:${sighting.externalId}"
            }
        }
        if (!removed) {
            requestTargetInfo(ledger, sighting.kind, sighting.externalId)
        }
    }

    /**
     * 目标缺少展示名时补全资料，让后台显示群名称而不是一串 OpenID。
     *
     * 官方只有群资料接口（且为白名单能力），好友/用户没有对应接口，因此只处理群目标。
     * 是否真的发起请求由 [QqBotTargetLedger.tryBeginInfoRequest] 决定：已取到名称、已有请求
     * 在途、或仍在失败冷却期时都会被挡掉，避免同一目标被并发重复请求。
     */
    private fun requestTargetInfo(
        ledger: QqBotTargetLedger,
        kind: TargetKind,
        externalId: String,
    ) {
        if (!running) return
        if (kind != TargetKind.GROUP) return
        val accountId = config.appId.trim().takeIf { it.isNotBlank() } ?: return
        val scope = incomingScope ?: return
        scope.launch { fetchGroupInfo(ledger, accountId, externalId) }
    }

    /**
     * 启动后的收尾任务：等网关就绪后先预热平台账号资料，再补全历史遗留的未命名群。
     *
     * 预热是必要的：[QqBotGateway.availableAccounts] 内部会拉一次 `GET /users/@me`，
     * 而它在发送路径（`isAccountReady`）与后台账号查询里都会被调用；不预热的话，
     * "启动后第一次发送"和后台首次查询要各自承担一次网络往返。
     */
    private fun startStartupTasks() {
        val scope = incomingScope ?: return
        val ledger = targetLedger
        val pending = ledger?.let { target ->
            runCatching {
                target.listCandidates(TargetKind.GROUP)
                    .map { it.address.externalId }
                    .filter { groupOpenid -> target.needsInfo(TargetKind.GROUP, groupOpenid) }
            }.getOrDefault(emptyList())
        }.orEmpty()

        scope.launch {
            // 启动瞬间账号尚未 READY，直接请求会因"账号不可用"失败并误入冷却期
            val accountId = awaitReadyAccountId() ?: run {
                logger.warn {
                    "QQ 官方机器人网关在 ${ACCOUNT_READY_TIMEOUT_MILLIS / 1000}s 内未就绪，" +
                        "跳过平台账号预热与群资料补全（待补全群数=${pending.size}）"
                }
                return@launch
            }
            warmUpPlatformAccount()
            if (ledger == null || pending.isEmpty()) return@launch
            logger.info { "QQ 官方机器人开始补全群资料：count=${pending.size} account=$accountId" }
            pending.forEach { groupOpenid ->
                // 群多时避免启动瞬间连打一串请求
                fetchGroupInfo(ledger, accountId, groupOpenid)
            }
        }
    }

    /** 预热平台账号资料；失败已在网关内部兜住并记 WARN，这里只保证不中断启动。 */
    private suspend fun warmUpPlatformAccount() {
        runCatching { gateway.availableAccounts() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                logger.warn(error) { "QQ 官方机器人平台账号资料预热失败" }
            }
    }

    private suspend fun awaitReadyAccountId(timeoutMillis: Long = ACCOUNT_READY_TIMEOUT_MILLIS): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val ready = runCatching {
                gateway.availableAccounts().firstOrNull { it.state == MessageSinkRouteState.READY }
            }.getOrNull()
            if (ready != null) return ready.accountId
            delay(ACCOUNT_READY_POLL_MILLIS)
        }
        return null
    }

    /**
     * 拉取单个群的资料并登记结果。
     *
     * [accountId] 由调用方给出（启动补全用就绪账号、事件路径用配置的 AppID），避免
     * "日志里的账号"与"实际请求的账号"不一致。
     */
    private suspend fun fetchGroupInfo(
        ledger: QqBotTargetLedger,
        accountId: String,
        groupOpenid: String,
    ) {
        if (!ledger.tryBeginInfoRequest(TargetKind.GROUP, groupOpenid)) return
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        val info = try {
            groupInfoRateLimiter.withPermit { gateway.groupInfo(accountId, groupOpenid) }
        } catch (error: CancellationException) {
            // 插件停止：释放名额后原样抛出，不登记任何冷却
            ledger.abandonInfoRequest(TargetKind.GROUP, groupOpenid)
            throw error
        } catch (error: Throwable) {
            val cooldownSeconds = infoRetryCooldownSeconds(error)
            ledger.recordInfo(
                kind = TargetKind.GROUP,
                externalId = groupOpenid,
                name = null,
                retryAfterEpochSeconds = nowEpochSeconds + cooldownSeconds,
            )
            logger.warn(error) {
                "QQ 官方机器人群资料获取失败（${cooldownSeconds}s 后重试），展示名继续使用 OpenID：group=$groupOpenid"
            }
            return
        }

        val name = info?.name?.trim()?.takeIf { it.isNotBlank() }
        when {
            // 网关不支持资料查询：长期不再尝试
            info == null -> {
                ledger.recordInfo(
                    kind = TargetKind.GROUP,
                    externalId = groupOpenid,
                    name = null,
                    retryAfterEpochSeconds = nowEpochSeconds + INFO_RETRY_PERMANENT_SECONDS,
                )
                logger.debug { "QQ 官方机器人网关未提供群资料查询：group=$groupOpenid" }
            }
            // 接口成功但没给出名称：按取不到处理，日志不能谎报"已补全"
            name == null -> {
                ledger.recordInfo(
                    kind = TargetKind.GROUP,
                    externalId = groupOpenid,
                    name = null,
                    retryAfterEpochSeconds = nowEpochSeconds + INFO_RETRY_PERMANENT_SECONDS,
                )
                logger.warn {
                    "QQ 官方机器人群信息未返回群名称，展示名继续使用 OpenID：" +
                        "group=$groupOpenid members=${info.memberCount ?: "-"}"
                }
            }
            else -> {
                ledger.recordInfo(TargetKind.GROUP, groupOpenid, name)
                logger.info {
                    "QQ 官方机器人群资料已补全：name=$name members=${info.memberCount ?: "-"} group=$groupOpenid"
                }
            }
        }
    }

    /**
     * 资料拉取的失败冷却。
     *
     * 只有"接口无权限/目标不可用"这类语义明确的业务码才按长期冷却（如 `11253` 白名单接口无权限）；
     * 频控（`40034100`、`504000~504999`）、HTTP 429/5xx 与非 OpenAPI 异常（网络/IO）都只是暂时性
     * 失败，短暂退避后重试——否则一次限流就会让群名 24 小时不再补全。
     */
    private fun infoRetryCooldownSeconds(error: Throwable): Long {
        return if (isTransientInfoFailure(error)) {
            INFO_RETRY_TRANSIENT_SECONDS
        } else {
            INFO_RETRY_PERMANENT_SECONDS
        }
    }

    private fun isTransientInfoFailure(error: Throwable): Boolean {
        val api = error as? QqBotOpenApiException ?: return true
        if (api.statusCode == HTTP_TOO_MANY_REQUESTS || api.statusCode >= 500) return true
        val code = api.code?.trim().orEmpty()
        if (code.isEmpty()) return true
        if (code in INFO_RETRY_TRANSIENT_CODES) return true
        return code.toIntOrNull()?.let { it in MESSAGE_RATE_LIMIT_CODE_RANGE } == true
    }

    private fun QqBotIncomingEvent.sourceEventId(messageId: String): String {
        return listOf(type, messageId).joinToString(":")
    }

    private fun QqBotIncomingEvent.dedupeKey(incoming: IncomingMessage): String {
        return listOf(
            QQBOT_PLUGIN_ID,
            botAccountId.orEmpty(),
            type,
            incoming.target.kind.name,
            incoming.target.externalId,
            incoming.messageId,
        ).joinToString(":")
    }

    private fun QqBotIncomingEvent.traceId(incoming: IncomingMessage): String {
        return "${QQBOT_PLUGIN_ID}:${dedupeKey(incoming).sha256Hex()}"
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
        accountAvatar = avatar,
        enabled = true,
        state = state,
    )

    private fun routeId(accountId: String): String = "$transportId:${QQBOT_PLATFORM_ID.value}:$accountId"

    private suspend fun isAccountReady(accountId: String): Boolean {
        return gateway.availableAccounts().any {
            it.accountId == accountId && it.state == MessageSinkRouteState.READY
        }
    }

    /**
     * 为本次发送预占群聊 @全体成员 的每日配额。
     *
     * 只有"目标是群 + 消息里确实带 [MessageContent.MentionAll] + 该群今天还没用过"时才返回 true。
     * 采用先占后用：同一群当天后续推送不再尝试，避免反复发送必然被平台以 304019 拒绝的请求；
     * 发送被拒时仍会去掉标签、以纯文本重发，消息本身不会丢。
     */
    private fun reserveMentionAll(request: MessageSendRequest, target: QqBotTarget): Boolean {
        if (target !is QqBotTarget.Group) return false
        val hasMentionAll = request.batches.any { batch ->
            batch.content.any { it is MessageContent.MentionAll }
        }
        if (!hasMentionAll) return false
        return mentionAllQuota.tryAcquire(target.groupOpenid)
    }

    private suspend fun sendPlanWithReceipt(
        routeId: String,
        accountId: String,
        target: TargetAddress,
        failureLabel: String,
        plan: List<QqBotSendPlanItem>,
        send: suspend (QqBotSendPlanItem) -> QqBotSendReceipt,
    ): MessageSendResult {
        val receipts = mutableListOf<QqBotSendReceipt>()
        plan.forEach { item ->
            val receipt = runCatching { sendWithMentionAllFallback(accountId, target, item, send) }
                .getOrElse { error ->
                    return if (receipts.isNotEmpty()) {
                        MessageSendResult.partiallySent(
                            receipts = receipts.map { receipt ->
                                MessageSendResult.receipt(
                                    sinkMessageId = receipt.messageId,
                                    sinkRouteId = routeId,
                                    sinkAccountId = accountId,
                                    sinkTransportId = transportId,
                                )
                            },
                            reason = error.sendOutcomeDescription(failureLabel),
                        )
                    } else if (error.isSendOutcomeUnknown()) {
                        // 超时/连接中断等状态未知场景：服务器可能已收到消息，按未知上报避免重复推送
                        MessageSendResult.uncertain(
                            reason = error.sendOutcomeDescription(failureLabel),
                            sinkRouteId = routeId,
                            sinkAccountId = accountId,
                            sinkTransportId = transportId,
                        )
                    } else {
                        val retryable = error.retryableForDelivery()
                        MessageSendResult.failed(
                            reason = error.sendOutcomeDescription(failureLabel),
                            retryable = retryable,
                        )
                    }
                }
            receipts += receipt
        }
        return MessageSendResult.sent(
            receipts.map { receipt ->
                MessageSendResult.receipt(
                    sinkMessageId = receipt.messageId,
                    sinkRouteId = routeId,
                    sinkAccountId = accountId,
                    sinkTransportId = transportId,
                )
            },
        )
    }

    /**
     * 发送单个计划项；带 @全体成员 的条目被平台明确拒绝时，去掉标签后以纯文本重发一次。
     *
     * 平台还要求机器人是该群管理员，插件无法预检，只对明确的 @全体权限或次数错误降级。
     * 其他失败保持原有失败或状态未知语义，避免绕过限流或造成重复消息。
     */
    private suspend fun sendWithMentionAllFallback(
        accountId: String,
        target: TargetAddress,
        item: QqBotSendPlanItem,
        send: suspend (QqBotSendPlanItem) -> QqBotSendReceipt,
    ): QqBotSendReceipt {
        return try {
            sendWithPassiveReply(accountId, target, item, send)
        } catch (error: Throwable) {
            val fallbackItem = item.withoutMentionAllSyntax() ?: throw error
            if (!error.isMentionAllRejection()) throw error
            // 与主项目重试同一套规则：重发必须换 msg_seq，否则可能被 msg_id+msg_seq 去重拒绝
            logger.warn {
                "QQ 官方机器人 @全体成员被平台拒绝，已降级为纯文本重发：${error.message.orEmpty()}"
            }
            sendWithPassiveReply(accountId, target, fallbackItem, send)
        }
    }

    private suspend fun sendWithPassiveReply(
        accountId: String,
        target: TargetAddress,
        item: QqBotSendPlanItem,
        send: suspend (QqBotSendPlanItem) -> QqBotSendReceipt,
    ): QqBotSendReceipt {
        val reservation = retryReplySeqKeeper.reserve(accountId, target, item)
        val allocated = reservation.item
        if (item.request.replyToMessageId != null && allocated.request.replyToMessageId == null) {
            logger.warn { "QQ 官方机器人被动回复超过平台限制，改为主动消息：target=${target.stableValue()}" }
        }
        return try {
            send(allocated)
        } catch (error: Throwable) {
            if (error.findCause<QqBotMediaPreparationException>() != null) reservation.release()
            throw error
        }
    }

    private fun QqBotSendPlanItem.withoutMentionAllSyntax(): QqBotSendPlanItem? {
        if (!request.content.contains(QQBOT_MENTION_ALL_SYNTAX)) return null
        return withRequest(
            request.copy(
                content = request.content.replace(QQBOT_MENTION_ALL_SYNTAX, QQBOT_MENTION_ALL_FALLBACK),
            ),
        )
    }

    private fun Throwable.isMentionAllRejection(): Boolean {
        val api = this as? QqBotOpenApiException ?: return false
        if (api.outcomeUnknown) return false
        return api.statusCode in 400..499 && api.code in setOf("304019", "1100304")
    }

    /**
     * 状态未知的判定：请求超时、连接在响应前中断，或 OpenAPI 明示"发送异常请稍后重试"。
     *
     * 这些场景下服务器可能已经受理消息，主项目不应自动重试（重复推送风险），
     * 由人工或上层按需处理。
     */
    private fun Throwable.isSendOutcomeUnknown(): Boolean {
        if (findCause<QqBotMediaPreparationException>() != null) return false
        generateSequence(this) { it.cause }.forEach { error ->
            if (error is java.net.http.HttpTimeoutException) return true
            if (error is java.io.IOException && error !is java.net.ConnectException) return true
            if (error is QqBotOpenApiException && error.outcomeUnknown) return true
        }
        return false
    }

    private fun Throwable.retryableForDelivery(): Boolean {
        findCause<QqBotMediaPreparationException>()?.let { return it.retryable }
        return (this as? QqBotOpenApiException)?.retryable ?: true
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        return generateSequence(this) { it.cause }.filterIsInstance<T>().firstOrNull()
    }

    private fun Throwable.sendOutcomeDescription(failureLabel: String): String {
        return message?.takeIf { it.isNotBlank() } ?: failureLabel
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

    private fun MessageSinkMediaDeliveryAdviceRequest.accountIdFromRequest(): String? {
        return routeId?.let(::accountIdFromRoute)
            ?: accountId?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun MessageSinkMediaDeliveryProbeRequest.accountIdFromRequest(): String? {
        return routeId?.let(::accountIdFromRoute)
            ?: accountId?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun IncomingMessage.mediaSummary(): String {
        val images = segments.count { it is IncomingMessageSegment.Image }
        val videos = segments.count { it is IncomingMessageSegment.Video }
        val audios = segments.count { it is IncomingMessageSegment.Audio }
        val unknown = segments.count { it is IncomingMessageSegment.Unknown }
        val parts = listOfNotNull(
            images.takeIf { it > 0 }?.let { "图片=$it" },
            videos.takeIf { it > 0 }?.let { "视频=$it" },
            audios.takeIf { it > 0 }?.let { "语音=$it" },
            unknown.takeIf { it > 0 }?.let { "未知附件=$it" },
        )
        return if (parts.isEmpty()) {
            "媒体=-"
        } else {
            "媒体=${parts.joinToString(",")}"
        }
    }
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

internal fun createQqBotGateway(config: QqBotConfig): QqBotGateway {
    return QqBotWebSocketGateway(config)
}

/** 调试级日志中入站文本的截断长度，避免日志被超长消息刷屏。 */
private const val MAX_LOG_TEXT_CHARS: Int = 200

/** 启动补全群资料时等待网关就绪的上限与轮询间隔。 */
private const val ACCOUNT_READY_TIMEOUT_MILLIS: Long = 60_000L
private const val ACCOUNT_READY_POLL_MILLIS: Long = 1_000L

/** 启动补全群资料时，相邻两次请求之间的间隔（群多时不至于启动瞬间连打一串请求）。 */
private const val GROUP_INFO_REQUEST_INTERVAL_MILLIS: Long = 2_100L

/** 群资料拉取失败后的重试冷却：语义明确的业务码失败（多半无权限）按长期，其余按短退避。 */
private const val INFO_RETRY_PERMANENT_SECONDS: Long = 24L * 60 * 60
private const val INFO_RETRY_TRANSIENT_SECONDS: Long = 10L * 60

/** 官方错误码表：`504000~504999` 为消息频率相关错误。 */
private val MESSAGE_RATE_LIMIT_CODE_RANGE: IntRange = 504_000..504_999

/** 明确属于暂时性失败的频控业务码（`40034100` 主动消息发送超过频控限制）。 */
private val INFO_RETRY_TRANSIENT_CODES: Set<String> = setOf("40034100")

/** HTTP 429 频率限制。 */
private const val HTTP_TOO_MANY_REQUESTS: Int = 429
/** 资料接口按 30 QPM 限制；所有实时与启动补全请求共用一个串行间隔器。 */
private class QqBotIntervalRateLimiter(
    private val intervalMillis: Long,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Mutex()
    private var nextAllowedAtMillis: Long = 0L

    suspend fun <T> withPermit(block: suspend () -> T): T = lock.withLock {
        val now = nowMillis()
        val waitMillis = (nextAllowedAtMillis - now).coerceAtLeast(0L)
        nextAllowedAtMillis = maxOf(now, nextAllowedAtMillis) + intervalMillis
        if (waitMillis > 0) delay(waitMillis)
        block()
    }
}
