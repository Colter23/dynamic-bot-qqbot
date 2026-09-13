package top.colter.dynamic.qqbot

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<JavaNetQqBotWebSocketGateway>()

internal class JavaNetQqBotWebSocketGateway(
    private val config: QqBotConfig,
    private val openApiClient: QqBotOpenApiClient = QqBotOpenApiClient(config),
    private val mediaUploader: QqBotMediaUploader = QqBotMediaUploader(openApiClient),
    private val connector: QqBotWebSocketConnector = JavaNetQqBotWebSocketConnector(),
    private val heartbeatScheduler: QqBotHeartbeatScheduler = ExecutorQqBotHeartbeatScheduler(),
    private val reconnectScheduler: QqBotReconnectScheduler = ExecutorQqBotReconnectScheduler(),
) : QqBotGateway {
    private val lifecycleLock = Any()

    @Volatile
    private var webSocket: QqBotWebSocketSession? = null

    private val pendingGatewayTexts: MutableList<String> = mutableListOf()

    @Volatile
    private var heartbeatTask: AutoCloseable? = null

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
        val resource = resolveMediaResource(scope, media)
        openApiClient.sendMessage(scope, request.copy(mediaFileInfo = resource.fileInfo)).also { receipt ->
            logger.info { "QQ 官方机器人富媒体消息已发送：scope=${scope.resource}/${scope.id} messageId=${receipt.messageId}" }
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
        logger.info {
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
        logger.info { "QQ 官方机器人富媒体资源已登记：type=${media.fileType.label} ttl=${resource.ttlSeconds ?: "-"}" }
        return resource
    }

    override fun close() {
        closing = true
        incomingEventHandler = null
        heartbeatTask.closeAndClear { heartbeatTask = null }
        reconnectTask.closeAndClear { reconnectTask = null }
        reconnecting = false
        val session = synchronized(lifecycleLock) {
            connectionGeneration += 1
            pendingGatewayTexts.clear()
            val current = webSocket
            webSocket = null
            current
        }
        session?.close(1000, "插件停止")
        markUnavailable("QQ 官方机器人 WebSocket 已关闭")
    }

    private fun openConnection(): Long {
        val endpoint = openApiClient.gatewayBotEndpoint()
        accessToken = openApiClient.accessToken().value
        val generation = nextConnectionGeneration()
        logger.info {
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
            pendingGatewayTexts.clear()
            val session = webSocket
            webSocket = null
            session
        }
    }

    private fun scheduleReconnect(reason: String, closeCurrentSession: Boolean) {
        if (closing || !config.reconnect) return

        val session = if (closeCurrentSession) invalidateCurrentConnection() else null
        session?.close(4000, "准备重连")

        val delayMillis = synchronized(lifecycleLock) {
            if (closing || reconnecting) return
            reconnecting = true
            reconnectAttempts += 1
            reconnectDelayMillis(reconnectAttempts)
        }
        logger.warn { "$reason；${delayMillis}ms 后重连" }
        reconnectTask = reconnectScheduler.schedule(delayMillis) {
            runReconnectAttempt()
        }
    }

    private fun invalidateCurrentConnection(): QqBotWebSocketSession? {
        return synchronized(lifecycleLock) {
            connectionGeneration += 1
            heartbeatTask.closeAndClear { heartbeatTask = null }
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
            markUnavailable(error.message?.takeIf { it.isNotBlank() } ?: "QQ 官方机器人重连失败")
            scheduleReconnect("QQ 官方机器人重连失败", closeCurrentSession = false)
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return 1_000L * (1L shl exponent)
    }

    private fun handleText(payload: String) {
        val frame = runCatching { QqBotWebSocketProtocol.parseFrame(payload) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人 WebSocket 帧解析失败" } }
            .getOrNull()
            ?: return

        frame.sequence?.let { latestSequence = it }
        logFrame(frame, payload)

        when (frame.opcode) {
            QqBotOpcode.HELLO -> handleHello(payload)
            QqBotOpcode.HEARTBEAT -> sendHeartbeat("QQ 官方机器人网关要求立即心跳")
            QqBotOpcode.DISPATCH -> handleDispatch(payload)
            QqBotOpcode.HEARTBEAT_ACK -> logger.debug { "QQ 官方机器人心跳已确认" }
            QqBotOpcode.RECONNECT -> handleReconnectRequest()
            QqBotOpcode.INVALID_SESSION -> handleInvalidSession()
            else -> logger.debug { "忽略 QQ 官方机器人 WebSocket 帧：op=${frame.opcode.code} type=${frame.type.orEmpty()}" }
        }
    }

    private fun handleHello(payload: String) {
        val hello = runCatching { QqBotWebSocketProtocol.parseHello(payload) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人 Hello 包解析失败" } }
            .getOrNull()
            ?: return

        startHeartbeat(hello.heartbeatIntervalMillis)
        sendResumeOrIdentify()
        logger.info { "QQ 官方机器人 WebSocket Hello 包已处理：heartbeat=${hello.heartbeatIntervalMillis}ms" }
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
        logger.info { "收到 QQ 官方机器人 Dispatch 事件：type=${event.type} seq=${dispatch.sequence ?: "-"}" }
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
                logger.info { "QQ 官方机器人 WebSocket 会话已恢复：sessionId=${sessionId.orEmpty()}" }
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

    private fun handleReconnectRequest() {
        markUnavailable("QQ 官方机器人网关要求重连")
        scheduleReconnect("QQ 官方机器人网关要求重连", closeCurrentSession = true)
    }

    private fun handleInvalidSession() {
        sessionId = null
        latestSequence = null
        markUnavailable("QQ 官方机器人会话无效")
        scheduleReconnect("QQ 官方机器人会话无效", closeCurrentSession = true)
    }

    private fun startHeartbeat(intervalMillis: Long) {
        heartbeatTask.closeAndClear { heartbeatTask = null }
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
            initialDelayMillis = intervalMillis,
            intervalMillis = intervalMillis,
        ) {
            sendHeartbeat("定时心跳")
        }
    }

    private fun sendHeartbeat(reason: String) {
        val payload = QqBotWebSocketProtocol.heartbeatPayload(latestSequence)
        sendGatewayText(payload)
        logger.debug { "QQ 官方机器人心跳已发送：reason=$reason seq=${latestSequence ?: "-"}" }
    }

    private fun sendIdentify() {
        val payload = QqBotWebSocketProtocol.identifyPayload(
            accessToken = accessToken,
            intentMask = config.enabledIntentMask(),
        )
        sendGatewayText(payload)
        logger.info { "QQ 官方机器人 Identify 已发送：intents=${config.enabledIntentMask()}" }
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
            logger.info { "QQ 官方机器人正在恢复 WebSocket 会话：sessionId=$currentSessionId seq=$sequence" }
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

    private fun markUnavailable(reason: String) {
        if (account == null) return
        account = initialAccount(MessageSinkRouteState.UNAVAILABLE)
        if (!closing) {
            logger.warn { reason }
        }
    }

    private fun initialAccount(state: MessageSinkRouteState): QqBotRuntimeAccount {
        val appId = config.appId.trim()
        return QqBotRuntimeAccount(
            accountId = appId,
            name = "QQ 官方机器人 $appId",
            state = state,
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

    private fun logFrame(frame: QqBotWebSocketFrame, payload: String) {
        if (frame.opcode == QqBotOpcode.DISPATCH || frame.opcode == QqBotOpcode.INVALID_SESSION) {
            logger.info {
                "收到 QQ 官方机器人 WebSocket 帧：op=${frame.opcode.code} type=${frame.type.orEmpty().ifBlank { "-" }} seq=${frame.sequence ?: "-"}"
            }
        }
        if (config.debugLogPayloads) {
            logger.info { "QQ 官方机器人 WebSocket 原始载荷：${payload.take(MAX_DEBUG_PAYLOAD_CHARS)}" }
        }
    }

    private inner class GatewayListener(
        private val generation: Long,
    ) : QqBotWebSocketListener {
        override fun onOpen() {
            if (!isCurrentConnection(generation)) return
            logger.info { "QQ 官方机器人 WebSocket 已打开：appId=${config.appId}" }
        }

        override fun onText(payload: String) {
            if (!isCurrentConnection(generation)) return
            handleText(payload)
        }

        override fun onClosed(statusCode: Int, reason: String) {
            if (!isCurrentConnection(generation)) return
            clearTransport(generation)
            markUnavailable("QQ 官方机器人 WebSocket 已关闭：code=$statusCode reason=$reason")
            scheduleReconnect("QQ 官方机器人 WebSocket 已关闭：code=$statusCode reason=$reason", closeCurrentSession = false)
        }

        override fun onError(error: Throwable) {
            if (!isCurrentConnection(generation)) return
            markUnavailable(error.message?.takeIf { it.isNotBlank() } ?: "QQ 官方机器人 WebSocket 出错")
            logger.warn(error) { "QQ 官方机器人 WebSocket 出错" }
            scheduleReconnect("QQ 官方机器人 WebSocket 出错", closeCurrentSession = true)
        }
    }
}

private const val MAX_DEBUG_PAYLOAD_CHARS: Int = 2_000
private const val DEFAULT_MEDIA_FILE_NAME: String = "media"

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

internal class JavaNetQqBotWebSocketConnector(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : QqBotWebSocketConnector {
    override fun connect(url: String, listener: QqBotWebSocketListener): QqBotWebSocketSession {
        val webSocket = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(url), JavaNetListener(listener))
            .join()
        return JavaNetQqBotWebSocketSession(webSocket)
    }

    private class JavaNetListener(
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

internal class JavaNetQqBotWebSocketSession(
    private val webSocket: WebSocket,
) : QqBotWebSocketSession {
    override fun sendText(payload: String) {
        webSocket.sendText(payload, true).join()
    }

    override fun close(statusCode: Int, reason: String) {
        runCatching { webSocket.sendClose(statusCode, reason).join() }
    }
}

internal interface QqBotHeartbeatScheduler {
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
}

internal interface QqBotReconnectScheduler {
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
}
