package top.colter.dynamic.qqbot

import java.net.URI
import java.nio.file.Files
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<QqBotWebSocketGateway>()

internal class QqBotWebSocketGateway(
    private val config: QqBotConfig,
    private val openApiClient: QqBotOpenApiClient = QqBotOpenApiClient(config),
    private val mediaUploader: QqBotMediaUploader = QqBotMediaUploader(openApiClient),
    private val connector: QqBotWebSocketConnector = QqBotJdkWebSocketConnector(),
    private val heartbeatScheduler: QqBotHeartbeatScheduler = ExecutorQqBotHeartbeatScheduler(),
    private val reconnectScheduler: QqBotReconnectScheduler = ExecutorQqBotReconnectScheduler(),
    private val nowNanos: () -> Long = System::nanoTime,
) : QqBotGateway {
    private val lifecycleLock = Any()

    @Volatile
    private var webSocket: QqBotWebSocketSession? = null

    private val pendingGatewayTexts: MutableList<String> = mutableListOf()
    private val mediaCache: MutableMap<MediaCacheKey, CachedMediaResource> = ConcurrentHashMap()
    private val mediaFlights: MutableMap<MediaCacheKey, CompletableFuture<QqBotMediaResource>> = ConcurrentHashMap()

    @Volatile
    private var heartbeatTask: AutoCloseable? = null

    // 由 lifecycleLock 保护；额外心跳不延后最早一条未确认心跳的截止时间。
    private var heartbeatSentAtNanos: Long? = null
    private var heartbeatIntervalNanos: Long = Long.MAX_VALUE

    @Volatile
    private var reconnectTask: AutoCloseable? = null

    @Volatile
    private var incomingEventHandler: ((QqBotIncomingEvent) -> Unit)? = null

    @Volatile
    private var account: QqBotRuntimeAccount? = null

    @Volatile
    private var latestSequence: Long? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var accessToken: String = ""

    @Volatile
    private var closing: Boolean = false

    @Volatile
    private var reconnecting: Boolean = false

    @Volatile
    private var reconnectAttempts: Int = 0

    @Volatile
    private var connectionGeneration: Long = 0L

    override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) {
        synchronized(lifecycleLock) {
            if (webSocket != null) return
        }

        QqBotConfigForm.validate(config)
        incomingEventHandler = onIncomingEvent
        closing = false
        reconnecting = false
        reconnectAttempts = 0
        latestSequence = null
        sessionId = null
        account = initialAccount(MessageSinkRouteState.UNAVAILABLE)
        synchronized(lifecycleLock) {
            pendingGatewayTexts.clear()
        }

        withContext(Dispatchers.IO) {
            openConnection()
        }
    }

    override suspend fun availableAccounts(): List<QqBotRuntimeAccount> {
        ensureBotProfile()
        return listOfNotNull(account)
    }

    override suspend fun sendUserMessage(
        accountId: String,
        openid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = sendText(QqBotOpenApiScope.User(openid), accountId, request)

    override suspend fun sendGroupMessage(
        accountId: String,
        groupOpenid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = sendText(QqBotOpenApiScope.Group(groupOpenid), accountId, request)

    override suspend fun sendUserMediaMessage(
        accountId: String,
        openid: String,
        media: QqBotMediaResourceSource,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = sendMedia(QqBotOpenApiScope.User(openid), accountId, media, request)

    override suspend fun sendGroupMediaMessage(
        accountId: String,
        groupOpenid: String,
        media: QqBotMediaResourceSource,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = sendMedia(QqBotOpenApiScope.Group(groupOpenid), accountId, media, request)

    override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
        recall(QqBotOpenApiScope.User(openid), accountId, messageId)
    }

    override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
        recall(QqBotOpenApiScope.Group(groupOpenid), accountId, messageId)
    }

    override suspend fun groupInfo(accountId: String, groupOpenid: String): QqBotGroupInfo? =
        withContext(Dispatchers.IO) {
            requireAccount(accountId)
            openApiClient.groupInfo(groupOpenid)
        }

    private suspend fun sendText(
        scope: QqBotOpenApiScope,
        accountId: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = withContext(Dispatchers.IO) {
        requireAccount(accountId)
        openApiClient.sendMessage(scope, request)
    }

    private suspend fun sendMedia(
        scope: QqBotOpenApiScope,
        accountId: String,
        media: QqBotMediaResourceSource,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = withContext(Dispatchers.IO) {
        requireAccount(accountId)
        val resource = try {
            resolveMediaResource(scope, media)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 资源登记阶段尚未调用消息接口，失败后可以安全重试；不要让它被上层
            // 当成“消息已发出但响应丢失”的状态未知错误。
            throw QqBotMediaPreparationException(error)
        }
        openApiClient.sendMessage(scope, request.copy(mediaFileInfo = resource.fileInfo)).also { receipt ->
            logger.debug { "QQ 官方机器人富媒体消息已发送：scope=${scope.resource}/${scope.id} messageId=${receipt.messageId}" }
        }
    }

    private suspend fun recall(
        scope: QqBotOpenApiScope,
        accountId: String,
        messageId: String,
    ) {
        withContext(Dispatchers.IO) {
            requireAccount(accountId)
            openApiClient.recallMessage(scope, messageId)
        }
    }

    /**
     * 把富媒体来源登记为 `file_info`：本地文件走分片上传，HTTP(S) 地址走 URL 转存。
     */
    private fun resolveMediaResource(
        scope: QqBotOpenApiScope,
        media: QqBotMediaResourceSource,
    ): QqBotMediaResource {
        val key = media.cacheKey(scope)
        val now = System.currentTimeMillis()
        mediaCache[key]?.takeIf { it.expiresAtMillis > now }?.let { return it.resource }
        val newFlight = CompletableFuture<QqBotMediaResource>()
        val flight = mediaFlights.putIfAbsent(key, newFlight)
        if (flight != null) return awaitMediaFlight(flight)

        try {
            val current = System.currentTimeMillis()
            mediaCache[key]?.takeIf { it.expiresAtMillis > current }?.let {
                newFlight.complete(it.resource)
                return it.resource
            }

            val resource = resolveMediaResourceUncached(scope, media)
            val ttlSeconds = resource.ttlSeconds
            if (ttlSeconds != null && ttlSeconds >= 0L) {
                val expiresAtMillis = if (ttlSeconds == 0L) {
                    Long.MAX_VALUE
                } else {
                    current + ttlSeconds.minus(10L).coerceAtLeast(1L) * MILLIS_PER_SECOND
                }
                cacheMediaResource(
                    key = key,
                    resource = resource,
                    expiresAtMillis = expiresAtMillis,
                    nowMillis = current,
                )
            }
            newFlight.complete(resource)
            return resource
        } catch (error: Throwable) {
            newFlight.completeExceptionally(error)
            throw error
        } finally {
            mediaFlights.remove(key, newFlight)
        }
    }

    private fun awaitMediaFlight(flight: CompletableFuture<QqBotMediaResource>): QqBotMediaResource {
        return try {
            flight.join()
        } catch (error: CompletionException) {
            throw error.cause ?: error
        }
    }

    private fun cacheMediaResource(
        key: MediaCacheKey,
        resource: QqBotMediaResource,
        expiresAtMillis: Long,
        nowMillis: Long,
    ) {
        mediaCache[key] = CachedMediaResource(resource, expiresAtMillis)
        if (mediaCache.size <= MAX_MEDIA_CACHE_ENTRIES) return

        mediaCache.entries.removeIf { entry ->
            entry.key != key && entry.value.expiresAtMillis <= nowMillis
        }
        while (mediaCache.size > MAX_MEDIA_CACHE_ENTRIES) {
            val evictedKey = mediaCache.keys.firstOrNull { it != key } ?: break
            mediaCache.remove(evictedKey)
        }
    }

    /**
     * 把富媒体来源登记为 `file_info`：本地文件走分片上传，HTTP(S) 地址走 URL 转存。
     * 本方法按目标和内容指纹做缓存/单飞；有效期未知的资源只在当前并发请求期间合并，之后不持久缓存。
     */
    private fun resolveMediaResourceUncached(
        scope: QqBotOpenApiScope,
        media: QqBotMediaResourceSource,
    ): QqBotMediaResource {
        logger.debug {
            "QQ 官方机器人正在登记富媒体资源：scope=${scope.resource}/${scope.id} type=${media.fileType.label} source=${media.sourceLabel()}"
        }
        val localFile = media.localFile
        val resource = if (localFile != null) {
            val fileName = media.fileName?.trim()?.takeIf { it.isNotBlank() }
                ?: localFile.fileName?.toString()
                ?: DEFAULT_MEDIA_FILE_NAME
            mediaUploader.upload(
                scope = scope,
                fileType = media.fileType,
                path = localFile,
                fileName = fileName,
            )
        } else {
            val url = media.url ?: error("QQ 官方机器人富媒体资源缺少 URL 或本地文件")
            openApiClient.prepareMedia(
                scope = scope,
                request = QqBotMediaPrepareRequest(fileType = media.fileType, url = url),
            )
        }
        logger.debug { "QQ 官方机器人富媒体资源已登记：type=${media.fileType.label} ttl=${resource.ttlSeconds ?: "-"}" }
        return resource
    }

    private fun QqBotMediaResourceSource.cacheKey(scope: QqBotOpenApiScope): MediaCacheKey {
        val local = localFile
        val source = if (local != null) {
            val absolute = runCatching { local.toAbsolutePath().normalize().toString() }.getOrDefault(local.toString())
            val size = runCatching { Files.size(local) }.getOrDefault(-1L)
            val modified = runCatching { Files.getLastModifiedTime(local).toMillis() }.getOrDefault(-1L)
            "file:$absolute:$size:$modified"
        } else {
            "url:${url?.trim().orEmpty()}"
        }
        return MediaCacheKey(
            resource = scope.resource,
            targetId = scope.id,
            fileType = fileType,
            source = source,
            fileName = fileName?.trim().orEmpty(),
        )
    }
    override fun close() {
        closing = true
        incomingEventHandler = null
        heartbeatTask.closeAndClear { heartbeatTask = null }
        reconnectTask.closeAndClear { reconnectTask = null }
        reconnecting = false
        val session = synchronized(lifecycleLock) {
            connectionGeneration += 1
            heartbeatSentAtNanos = null
            pendingGatewayTexts.clear()
            val current = webSocket
            webSocket = null
            current
        }
        session?.close(1000, "插件停止")
        mediaCache.clear()
        mediaFlights.clear()
        heartbeatScheduler.close()
        reconnectScheduler.close()
        markUnavailable()
    }

    private fun openConnection(): Long {
        val endpoint = openApiClient.gatewayBotEndpoint()
        // 官方限制：每个机器人创建的连接数不能超过 session_start_limit.remaining，
        // 用尽后继续重试只会白刷日志，因此按终态处理。
        val shardCount = endpoint.shards?.coerceAtLeast(1) ?: 1
        if (shardCount > 1) {
            markUnavailable()
            throw QqBotShardUnsupportedException(
                "QQ 官方机器人建议使用 $shardCount 个 Shard，但当前插件仍是单连接模式，" +
                    "为避免静默丢失事件已停止连接"
            )
        }
        val sessionLimit = endpoint.sessionStartLimit
        val remaining = sessionLimit?.remaining
        if (remaining != null && remaining <= 0) {
            markUnavailable()
            throw QqBotConnectionQuotaExhaustedException(
                "QQ 官方机器人连接配额已用尽（remaining=$remaining，" +
                    "resetAfter=${sessionLimit.resetAfterMillis ?: "-"}ms），已停止重连，请等待配额重置",
            )
        }
        accessToken = openApiClient.accessToken().value
        val generation = nextConnectionGeneration()
        logger.debug {
            "正在连接 QQ 官方机器人 WebSocket：appId=${config.appId} url=${endpoint.url} shards=${endpoint.shards ?: "-"}"
        }
        val session = connector.connect(
            url = endpoint.url,
            listener = GatewayListener(generation),
        )
        val pendingTexts = mutableListOf<String>()
        synchronized(lifecycleLock) {
            if (closing || generation != connectionGeneration) {
                pendingGatewayTexts.clear()
                session.close(1000, "过期连接")
            } else {
                webSocket = session
                pendingTexts += pendingGatewayTexts
                pendingGatewayTexts.clear()
            }
        }
        pendingTexts.forEach(session::sendText)
        return generation
    }

    private fun nextConnectionGeneration(): Long = synchronized(lifecycleLock) {
        connectionGeneration += 1
        connectionGeneration
    }

    private fun isCurrentConnection(generation: Long): Boolean {
        return !closing && generation == connectionGeneration
    }

    private fun clearTransport(generation: Long): QqBotWebSocketSession? {
        return synchronized(lifecycleLock) {
            if (generation != connectionGeneration) return@synchronized null
            connectionGeneration += 1
            heartbeatTask.closeAndClear { heartbeatTask = null }
            heartbeatSentAtNanos = null
            pendingGatewayTexts.clear()
            val session = webSocket
            webSocket = null
            session
        }
    }

    /**
     * 安排重连，并作为**唯一的重连日志出口**。
     *
     * 原则是"正常静默、异常出声"：
     * - [expected]（服务端要求重连 op 7、连接过期 4009）属正常流程，记 DEBUG——成功时
     *   整个周期只剩 `handleControlDispatch` 里那一条 INFO，失败时下面的 WARN 仍会出来；
     * - 其余按异常记 WARN，带 [cause] 时一并输出堆栈。
     */
    private fun scheduleReconnect(
        reason: String,
        closeCurrentSession: Boolean,
        expected: Boolean = false,
        cause: Throwable? = null,
    ) {
        if (closing || !config.reconnect) return

        val session = if (closeCurrentSession) invalidateCurrentConnection() else null
        session?.close(4000, "准备重连")

        val delayMillis = synchronized(lifecycleLock) {
            if (closing || reconnecting) return
            reconnecting = true
            reconnectAttempts += 1
            reconnectDelayMillis(reconnectAttempts)
        }
        val message = "$reason；${delayMillis}ms 后重连"
        when {
            expected -> logger.debug { message }
            cause != null -> logger.warn(cause) { message }
            else -> logger.warn { message }
        }
        reconnectTask = reconnectScheduler.schedule(delayMillis) {
            runReconnectAttempt()
        }
    }

    private fun invalidateCurrentConnection(): QqBotWebSocketSession? {
        return synchronized(lifecycleLock) {
            connectionGeneration += 1
            heartbeatTask.closeAndClear { heartbeatTask = null }
            heartbeatSentAtNanos = null
            pendingGatewayTexts.clear()
            val session = webSocket
            webSocket = null
            session
        }
    }

    private fun runReconnectAttempt() {
        if (closing) return
        runCatching {
            openConnection()
        }.onSuccess {
            synchronized(lifecycleLock) {
                reconnectTask = null
                reconnecting = false
            }
        }.onFailure { error ->
            synchronized(lifecycleLock) {
                reconnectTask = null
                reconnecting = false
            }
            markUnavailable()
            // 连接配额用尽是终态，重试只会白刷日志，交给调用点停止重连
            if (error is QqBotConnectionQuotaExhaustedException || error is QqBotShardUnsupportedException) {
                logger.error { error.message.orEmpty() }
                return@onFailure
            }
            scheduleReconnect("QQ 官方机器人重连失败", closeCurrentSession = false, cause = error)
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return 1_000L * (1L shl exponent)
    }

    private fun handleText(payload: String, generation: Long) {
        val frame = runCatching { QqBotWebSocketProtocol.parseFrame(payload) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人 WebSocket 帧解析失败" } }
            .getOrNull()
            ?: return

        frame.sequence?.let { latestSequence = it }
        logFrame(frame)

        when (frame.opcode) {
            QqBotOpcode.HELLO -> handleHello(payload, generation)
            QqBotOpcode.HEARTBEAT -> sendHeartbeat("QQ 官方机器人网关要求立即心跳", generation)
            QqBotOpcode.DISPATCH -> handleDispatch(payload)
            QqBotOpcode.HEARTBEAT_ACK -> {
                synchronized(lifecycleLock) {
                    if (!isCurrentConnection(generation)) return
                    heartbeatSentAtNanos = null
                }
                logger.debug { "QQ 官方机器人心跳已确认" }
            }
            QqBotOpcode.RECONNECT -> handleReconnectRequest()
            QqBotOpcode.INVALID_SESSION -> handleInvalidSession()
            else -> logger.debug { "忽略 QQ 官方机器人 WebSocket 帧：op=${frame.opcode.code} type=${frame.type.orEmpty()}" }
        }
    }

    private fun handleHello(payload: String, generation: Long) {
        val hello = runCatching { QqBotWebSocketProtocol.parseHello(payload) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人 Hello 包解析失败" } }
            .getOrNull()
            ?: return

        startHeartbeat(hello.heartbeatIntervalMillis, generation)
        sendResumeOrIdentify()
        logger.debug { "QQ 官方机器人 WebSocket Hello 包已处理：heartbeat=${hello.heartbeatIntervalMillis}ms" }
    }

    private fun handleDispatch(payload: String) {
        val dispatch = runCatching { QqBotWebSocketProtocol.parseDispatch(payload) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人 Dispatch 包解析失败" } }
            .getOrNull()
            ?: return
        if (handleControlDispatch(dispatch)) return

        val event = QqBotIncomingEvent(
            type = dispatch.type,
            rawPayload = dispatch.rawPayload,
            botAccountId = config.appId.trim().takeIf { it.isNotBlank() },
        )
        logger.debug { "收到 QQ 官方机器人 Dispatch 事件：type=${event.type} seq=${dispatch.sequence ?: "-"}" }
        incomingEventHandler?.invoke(event)
    }

    private fun handleControlDispatch(dispatch: QqBotDispatchEvent): Boolean {
        return when (dispatch.type) {
            QQBOT_READY_EVENT -> {
                recordReadySession(dispatch)
                true
            }
            QQBOT_RESUMED_EVENT -> {
                markReady()
                // 正常重连周期里唯一的一条 INFO：带上 seq 便于确认事件不会丢
                logger.info {
                    "QQ 官方机器人 WebSocket 会话已恢复：sessionId=${sessionId.orEmpty()} seq=${dispatch.sequence ?: "-"}"
                }
                true
            }
            else -> false
        }
    }

    private fun recordReadySession(dispatch: QqBotDispatchEvent) {
        val ready = runCatching { QqBotWebSocketProtocol.parseReadySession(dispatch) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人 READY 事件解析失败" } }
            .getOrNull()
            ?: return
        sessionId = ready.sessionId
        ready.sequence?.let { latestSequence = it }
        markReady()
        logger.info { "QQ 官方机器人 WebSocket 会话已就绪：sessionId=${ready.sessionId}" }
    }

    /** 服务端通过 op 7 要求重连：属正常流程，保留会话走 RESUME。 */
    private fun handleReconnectRequest() {
        markUnavailable()
        scheduleReconnect("QQ 官方机器人网关要求重连", closeCurrentSession = true, expected = true)
    }

    private fun handleInvalidSession() {
        sessionId = null
        latestSequence = null
        markUnavailable()
        scheduleReconnect("QQ 官方机器人会话无效", closeCurrentSession = true)
    }

    /**
     * 按官方 WebSocket 错误码表处置关闭事件。
     *
     * 此前不看关闭码，导致两类问题：`4006`/`4007`（会话已失效，官方要求 identify）会继续
     * RESUME 而反复被拒；`4013`/`4014`/`4914`/`4915` 等官方明确"不可重试"的终态错误会无限
     * 重连，既刷日志又消耗连接配额。
     */
    private fun handleCloseCode(statusCode: Int, reason: String) {
        val label = "QQ 官方机器人 WebSocket 已关闭：code=$statusCode reason=$reason"
        when (closeActionOf(statusCode)) {
            QqBotCloseAction.TERMINAL -> {
                markUnavailable()
                // 插件没有手动重连入口，终态后只能靠重启，日志里写清恢复途径
                logger.error { "$label；该错误重试无意义，已停止重连，修复后需重启插件${terminalHint(statusCode)}" }
            }
            QqBotCloseAction.REIDENTIFY -> {
                sessionId = null
                latestSequence = null
                markUnavailable()
                scheduleReconnect("$label；会话已失效，将重新鉴权", closeCurrentSession = false)
            }
            QqBotCloseAction.RESUME -> {
                markUnavailable()
                // 4009「连接过期，请重连并执行 resume」是常态，属正常流程
                scheduleReconnect(label, closeCurrentSession = false, expected = statusCode == QQBOT_CLOSE_CONNECTION_EXPIRED)
            }
        }
    }

    private fun startHeartbeat(intervalMillis: Long, generation: Long) {
        synchronized(lifecycleLock) {
            if (!isCurrentConnection(generation)) return
            heartbeatTask.closeAndClear { heartbeatTask = null }
            heartbeatSentAtNanos = null
            heartbeatIntervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis)
            heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                initialDelayMillis = intervalMillis,
                intervalMillis = intervalMillis,
            ) {
                sendHeartbeat("定时心跳", generation)
            }
        }
    }

    private fun sendHeartbeat(reason: String, generation: Long) {
        synchronized(lifecycleLock) {
            if (!isCurrentConnection(generation)) return
            val now = nowNanos()
            val sentAt = heartbeatSentAtNanos
            if (sentAt != null && now - sentAt >= heartbeatIntervalNanos) {
                markUnavailable()
                clearTransport(generation)?.close(4000, "心跳确认超时")
                scheduleReconnect("QQ 官方机器人心跳确认超时", closeCurrentSession = false)
                return
            }
            if (sentAt == null) heartbeatSentAtNanos = now
            sendGatewayText(QqBotWebSocketProtocol.heartbeatPayload(latestSequence))
        }
        logger.debug { "QQ 官方机器人心跳已发送：reason=$reason seq=${latestSequence ?: "-"}" }
    }

    private fun sendIdentify() {
        val payload = QqBotWebSocketProtocol.identifyPayload(
            accessToken = accessToken,
            intentMask = QQBOT_INTENT_MASK,
        )
        sendGatewayText(payload)
        logger.info { "QQ 官方机器人 Identify 已发送：intents=$QQBOT_INTENT_MASK" }
    }

    private fun sendResumeOrIdentify() {
        val currentSessionId = sessionId
        val sequence = latestSequence
        if (!currentSessionId.isNullOrBlank() && sequence != null) {
            val payload = QqBotWebSocketProtocol.resumePayload(
                accessToken = accessToken,
                sessionId = currentSessionId,
                sequence = sequence,
            )
            sendGatewayText(payload)
            logger.debug { "QQ 官方机器人正在恢复 WebSocket 会话：sessionId=$currentSessionId seq=$sequence" }
            return
        }

        sendIdentify()
    }

    /**
     * 会话不存在时暂存待发送载荷，新连接建立后由 [openConnection] 补发。
     *
     * 正常重连等待期间心跳任务已取消，几乎不会有载荷进入队列；保留它只为兜底
     * `connect()` 已返回但 `openConnection()` 尚未把会话写入字段的极小窗口，
     * 例如 Identify 恰好在首次连接建立瞬间被触发。
     */
    private fun sendGatewayText(payload: String) {
        val session = synchronized(lifecycleLock) {
            val current = webSocket
            if (current == null && !closing) {
                pendingGatewayTexts += payload
            }
            current
        }
        session?.sendText(payload)
    }

    private fun markReady() {
        reconnectAttempts = 0
        account = initialAccount(MessageSinkRouteState.READY)
    }

    /**
     * 标记出口不可用。
     *
     * 只改状态、**不记日志**：所有调用点后面都紧跟 [scheduleReconnect]，由它按"正常/异常"
     * 决定级别并输出唯一一条日志，避免同一句话被打两遍。
     */
    private fun markUnavailable() {
        if (account == null) return
        account = initialAccount(MessageSinkRouteState.UNAVAILABLE)
    }

    /**
     * 机器人自身资料（`GET /users/@me`），首次成功拉取后缓存；用于后台展示真实名称与头像。
     */
    @Volatile
    private var botProfile: QqBotBotInfo? = null

    /**
     * 拉取互斥标记。
     *
     * [availableAccounts] 会被后台账号查询与发送路径（`isAccountReady`）并发调用，
     * 用原子 CAS 而不是普通布尔，避免两边同时通过检查、各发一次资料请求。
     */
    private val botProfileLoading: AtomicBoolean = AtomicBoolean(false)

    /** 资料拉取失败后的重试时间；避免在发送热路径上反复打接口。 */
    @Volatile
    private var botProfileRetryAfterMillis: Long = 0L

    /**
     * 拉取机器人资料并缓存。
     *
     * 用"已缓存 / 失败冷却 / 正在拉取"三重短路：正常情况零开销，失败也不会反复请求。
     * 拉取失败只记 WARN，保留默认展示名，不影响连接与收发。
     */
    private suspend fun ensureBotProfile() {
        if (botProfile != null) return
        if (System.currentTimeMillis() < botProfileRetryAfterMillis) return
        val current = account ?: return
        if (current.state != MessageSinkRouteState.READY) return
        if (!botProfileLoading.compareAndSet(false, true)) return
        try {
            val info = withContext(Dispatchers.IO) { openApiClient.botInfo() }
            botProfile = info
            // 写回时重新读取当下的 state：拉取期间连接可能已断开（markUnavailable 会置为
            // UNAVAILABLE），用几毫秒前读到的 current.state 覆盖回去会把状态改错
            account?.let { latest -> account = initialAccount(latest.state) }
            logger.info {
                "QQ 官方机器人资料已加载：name=${info.name} avatar=${if (info.avatar.isBlank()) "无" else "有"}"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            botProfileRetryAfterMillis = System.currentTimeMillis() + BOT_PROFILE_RETRY_MILLIS
            logger.warn(error) { "QQ 官方机器人资料获取失败，后台展示名回退为默认值" }
        } finally {
            botProfileLoading.set(false)
        }
    }

    private fun initialAccount(state: MessageSinkRouteState): QqBotRuntimeAccount {
        val appId = config.appId.trim()
        val profile = botProfile
        return QqBotRuntimeAccount(
            accountId = appId,
            name = profile?.name?.takeIf { it.isNotBlank() } ?: "QQ 官方机器人 $appId",
            state = state,
            avatar = profile?.avatar
                ?.takeIf { it.isNotBlank() }
                ?.let { uri -> MediaRef(uri = uri, kind = MediaKind.AVATAR) },
        )
    }

    private fun requireAccount(accountId: String) {
        val current = account ?: error("QQ 官方机器人账号尚未初始化")
        require(current.accountId == accountId) {
            "QQ 官方机器人账号不匹配：期望=${current.accountId} 实际=$accountId"
        }
        require(current.state == MessageSinkRouteState.READY) { "QQ 官方机器人账号不可用：$accountId" }
    }

    private fun AutoCloseable?.closeAndClear(clear: () -> Unit) {
        val closeable = this ?: return
        runCatching { closeable.close() }
            .onFailure { error -> logger.debug(error) { "QQ 官方机器人可关闭资源清理失败" } }
        clear()
    }

    private fun QqBotMediaResourceSource.sourceLabel(): String {
        return when {
            url != null -> "url"
            localFile != null -> "本地文件"
            else -> "未知"
        }
    }

    private fun logFrame(frame: QqBotWebSocketFrame) {
        if (frame.opcode == QqBotOpcode.DISPATCH || frame.opcode == QqBotOpcode.INVALID_SESSION) {
            logger.debug {
                "收到 QQ 官方机器人 WebSocket 帧：op=${frame.opcode.code} type=${frame.type.orEmpty().ifBlank { "-" }} seq=${frame.sequence ?: "-"}"
            }
        }
    }

    private inner class GatewayListener(
        private val generation: Long,
    ) : QqBotWebSocketListener {
        override fun onOpen() {
            if (!isCurrentConnection(generation)) return
            logger.debug { "QQ 官方机器人 WebSocket 已打开：appId=${config.appId}" }
        }

        override fun onText(payload: String) {
            if (!isCurrentConnection(generation)) return
            handleText(payload, generation)
        }

        override fun onClosed(statusCode: Int, reason: String) {
            if (!isCurrentConnection(generation)) return
            clearTransport(generation)
            handleCloseCode(statusCode, reason)
        }

        override fun onError(error: Throwable) {
            if (!isCurrentConnection(generation)) return
            markUnavailable()
            scheduleReconnect("QQ 官方机器人 WebSocket 出错", closeCurrentSession = true, cause = error)
        }
    }
}

private data class MediaCacheKey(
    val resource: String,
    val targetId: String,
    val fileType: QqBotMediaFileType,
    val source: String,
    val fileName: String,
)

private data class CachedMediaResource(
    val resource: QqBotMediaResource,
    val expiresAtMillis: Long,
)
private const val DEFAULT_MEDIA_FILE_NAME: String = "media"
private const val MILLIS_PER_SECOND: Long = 1_000L
private const val MAX_MEDIA_CACHE_ENTRIES: Int = 1_024

/** 机器人资料（`GET /users/@me`）拉取失败后的重试冷却。 */
private const val BOT_PROFILE_RETRY_MILLIS: Long = 10L * 60 * 1000

/** 官方关闭码：连接过期，需重连并 RESUME。属常态，按正常流程记录。 */
private const val QQBOT_CLOSE_CONNECTION_EXPIRED: Int = 4009

/** 关闭码处置。 */
internal enum class QqBotCloseAction {
    /** 保留会话重连（可 RESUME）。 */
    RESUME,

    /** 会话已失效，需清除后重新 IDENTIFY。 */
    REIDENTIFY,

    /** 终态：官方明确不可重试，停止重连。 */
    TERMINAL,
}

/**
 * 依据官方 WebSocket 错误码表判定关闭码处置。
 *
 * 可 RESUME 的码保留会话；`4006`/`4007` 官方要求重新 IDENTIFY；其余为官方标注"不可重试"的终态。
 */
internal fun closeActionOf(statusCode: Int): QqBotCloseAction {
    return when (statusCode) {
        // 无效 session id / seq 错误：无法继续 resume，官方要求 identify
        4006, 4007 -> QqBotCloseAction.REIDENTIFY
        // 无效 opcode / payload / shard / version / intent / intent 无权限 / 已下架 / 已封禁
        4001, 4002, 4010, 4011, 4012, 4013, 4014, 4914, 4915 -> QqBotCloseAction.TERMINAL
        // 4008 发送过快、4009 连接过期、4900~4913 内部错误，以及未知码：保守按可重连处理
        else -> QqBotCloseAction.RESUME
    }
}

/** 终态关闭码的排查提示。 */
private fun terminalHint(statusCode: Int): String {
    return when (statusCode) {
        4013 -> "；intent 无效，请核对 QQBOT_INTENT_MASK"
        4014 -> "；intent 无权限，请确认已在开放平台申请对应事件权限"
        4914 -> "；机器人已下架，只允许连接沙箱环境"
        4915 -> "；机器人已封禁，请申请解封后重连"
        4001, 4002 -> "；客户端协议或载荷不符合官方规范"
        4010, 4011, 4012 -> "；分片或版本参数不符合官方规范"
        else -> ""
    }
}

/** 连接配额（`session_start_limit.remaining`）已用尽：重试无意义，属终态。 */
private class QqBotConnectionQuotaExhaustedException(message: String) : IllegalStateException(message)
private class QqBotShardUnsupportedException(message: String) : IllegalStateException(message)

internal interface QqBotWebSocketConnector {
    fun connect(url: String, listener: QqBotWebSocketListener): QqBotWebSocketSession
}

internal interface QqBotWebSocketListener {
    fun onOpen()

    fun onText(payload: String)

    fun onClosed(statusCode: Int, reason: String)

    fun onError(error: Throwable)
}

internal interface QqBotWebSocketSession {
    fun sendText(payload: String)

    fun close(statusCode: Int, reason: String)
}

internal class QqBotJdkWebSocketConnector(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : QqBotWebSocketConnector {
    override fun connect(url: String, listener: QqBotWebSocketListener): QqBotWebSocketSession {
        val webSocket = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(url), JdkListener(listener))
            .join()
        return QqBotJdkWebSocketSession(webSocket)
    }

    private class JdkListener(
        private val listener: QqBotWebSocketListener,
    ) : WebSocket.Listener {
        private val textBuffer: StringBuilder = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
            listener.onOpen()
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            textBuffer.append(data)
            if (last) {
                val payload = textBuffer.toString()
                textBuffer.setLength(0)
                listener.onText(payload)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            listener.onClosed(statusCode, reason)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            listener.onError(error)
        }
    }
}

internal class QqBotJdkWebSocketSession(
    private val webSocket: WebSocket,
) : QqBotWebSocketSession {
    private val sendLock = Any()
    private var sendTail: CompletionStage<*> = CompletableFuture.completedFuture(null)

    /**
     * JDK WebSocket 的回调线程和心跳线程都可能发送载荷；用有序异步链保证顺序，
     * 避免在这些线程上同步等待网络写入。
     */
    override fun sendText(payload: String) {
        synchronized(sendLock) {
            sendTail = sendTail
                .handle { _, _ -> null }
                .thenCompose {
                    webSocket.sendText(payload, true).thenApply { null }
                }
                .also { stage ->
                    stage.exceptionally { error ->
                        logger.warn(error) { "QQ 官方机器人 WebSocket 载荷发送失败" }
                        null
                    }
                }
        }
    }

    override fun close(statusCode: Int, reason: String) {
        runCatching {
            webSocket.sendClose(statusCode, reason)
                .exceptionally { error ->
                    logger.debug(error) { "QQ 官方机器人 WebSocket 关闭失败" }
                    null
                }
        }
    }
}
internal interface QqBotHeartbeatScheduler : AutoCloseable {
    override fun close() {}

    fun scheduleAtFixedRate(
        initialDelayMillis: Long,
        intervalMillis: Long,
        task: () -> Unit,
    ): AutoCloseable
}

internal class ExecutorQqBotHeartbeatScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "qqbot-heartbeat").apply { isDaemon = true }
    },
) : QqBotHeartbeatScheduler {
    override fun scheduleAtFixedRate(
        initialDelayMillis: Long,
        intervalMillis: Long,
        task: () -> Unit,
    ): AutoCloseable {
        val future = executor.scheduleAtFixedRate(
            { runCatching(task).onFailure { error -> logger.warn(error) { "QQ 官方机器人心跳发送失败" } } },
            initialDelayMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

internal interface QqBotReconnectScheduler : AutoCloseable {
    override fun close() {}

    fun schedule(delayMillis: Long, task: () -> Unit): AutoCloseable
}

internal class ExecutorQqBotReconnectScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "qqbot-reconnect").apply { isDaemon = true }
    },
) : QqBotReconnectScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): AutoCloseable {
        val future = executor.schedule(
            { runCatching(task).onFailure { error -> logger.warn(error) { "QQ 官方机器人重连任务执行失败" } } },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
