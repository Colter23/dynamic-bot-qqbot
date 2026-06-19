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
    private val connector: QqBotWebSocketConnector = JavaNetQqBotWebSocketConnector(),
    private val heartbeatScheduler: QqBotHeartbeatScheduler = ExecutorQqBotHeartbeatScheduler(),
    private val reconnectScheduler: QqBotReconnectScheduler = ExecutorQqBotReconnectScheduler(),
) : QqBotGateway {
    private val lifecycleLock = Any()

    @Volatile
    private var webSocket: QqBotWebSocketSession? = null

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
        account = initialAccount(MessageSinkRouteState.UNAVAILABLE)

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
    ): QqBotSendReceipt = withContext(Dispatchers.IO) {
        requireAccount(accountId)
        openApiClient.sendUserMessage(openid, request)
    }

    override suspend fun sendGroupMessage(
        accountId: String,
        groupOpenid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = withContext(Dispatchers.IO) {
        requireAccount(accountId)
        openApiClient.sendGroupMessage(groupOpenid, request)
    }

    override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
        notImplemented<Unit>()
    }

    override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
        notImplemented<Unit>()
    }

    override fun close() {
        closing = true
        incomingEventHandler = null
        heartbeatTask.closeAndClear { heartbeatTask = null }
        reconnectTask.closeAndClear { reconnectTask = null }
        reconnecting = false
        val session = synchronized(lifecycleLock) {
            connectionGeneration += 1
            val current = webSocket
            webSocket = null
            current
        }
        session?.close(1000, "shutdown")
        markUnavailable("QQ official bot WebSocket closed")
    }

    private fun openConnection(): Long {
        val endpoint = openApiClient.gatewayBotEndpoint()
        accessToken = openApiClient.accessToken().value
        val generation = nextConnectionGeneration()
        logger.info {
            "Connecting QQ official bot WebSocket: appId=${config.appId} url=${endpoint.url} shards=${endpoint.shards ?: "-"}"
        }
        val session = connector.connect(
            url = endpoint.url,
            listener = GatewayListener(generation),
        )
        synchronized(lifecycleLock) {
            if (closing || generation != connectionGeneration) {
                session.close(1000, "stale connection")
            } else {
                webSocket = session
            }
        }
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
            val session = webSocket
            webSocket = null
            session
        }
    }

    private fun scheduleReconnect(reason: String, closeCurrentSession: Boolean) {
        if (closing || !config.reconnect) return

        val session = if (closeCurrentSession) invalidateCurrentConnection() else null
        session?.close(4000, "reconnect")

        val delayMillis = synchronized(lifecycleLock) {
            if (closing || reconnecting) return
            reconnecting = true
            reconnectAttempts += 1
            reconnectDelayMillis(reconnectAttempts)
        }
        logger.warn { "$reason; reconnecting in ${delayMillis}ms" }
        reconnectTask = reconnectScheduler.schedule(delayMillis) {
            runReconnectAttempt()
        }
    }

    private fun invalidateCurrentConnection(): QqBotWebSocketSession? {
        return synchronized(lifecycleLock) {
            connectionGeneration += 1
            heartbeatTask.closeAndClear { heartbeatTask = null }
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
            markUnavailable(error.message?.takeIf { it.isNotBlank() } ?: "QQ official bot reconnect failed")
            scheduleReconnect("QQ official bot reconnect failed", closeCurrentSession = false)
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return 1_000L * (1L shl exponent)
    }

    private fun handleText(payload: String) {
        val frame = runCatching { QqBotWebSocketProtocol.parseFrame(payload) }
            .onFailure { error -> logger.warn(error) { "QQ official bot WebSocket frame parse failed" } }
            .getOrNull()
            ?: return

        frame.sequence?.let { latestSequence = it }

        when (frame.opcode) {
            QqBotOpcode.HELLO -> handleHello(payload)
            QqBotOpcode.DISPATCH -> handleDispatch(payload)
            QqBotOpcode.HEARTBEAT_ACK -> logger.debug { "QQ official bot heartbeat acknowledged" }
            QqBotOpcode.RECONNECT -> handleReconnectRequest()
            QqBotOpcode.INVALID_SESSION -> handleInvalidSession()
            else -> logger.debug { "Ignoring QQ official bot WebSocket frame: op=${frame.opcode.code} type=${frame.type.orEmpty()}" }
        }
    }

    private fun handleHello(payload: String) {
        val hello = runCatching { QqBotWebSocketProtocol.parseHello(payload) }
            .onFailure { error -> logger.warn(error) { "QQ official bot Hello parse failed" } }
            .getOrNull()
            ?: return

        startHeartbeat(hello.heartbeatIntervalMillis)
        sendIdentify()
        markReady()
        logger.info { "QQ official bot WebSocket Hello handled: heartbeat=${hello.heartbeatIntervalMillis}ms" }
    }

    private fun handleDispatch(payload: String) {
        val event = QqBotWebSocketProtocol.incomingEventOrNull(
            payload = payload,
            botAccountId = config.appId.trim().takeIf { it.isNotBlank() },
        ) ?: return
        incomingEventHandler?.invoke(event)
    }

    private fun handleReconnectRequest() {
        markUnavailable("QQ official bot gateway requested reconnect")
        scheduleReconnect("QQ official bot gateway requested reconnect", closeCurrentSession = true)
    }

    private fun handleInvalidSession() {
        markUnavailable("QQ official bot session invalid")
        scheduleReconnect("QQ official bot session invalid", closeCurrentSession = true)
    }

    private fun startHeartbeat(intervalMillis: Long) {
        heartbeatTask.closeAndClear { heartbeatTask = null }
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
            initialDelayMillis = intervalMillis,
            intervalMillis = intervalMillis,
        ) {
            val payload = QqBotWebSocketProtocol.heartbeatPayload(latestSequence)
            webSocket?.sendText(payload)
        }
    }

    private fun sendIdentify() {
        val payload = QqBotWebSocketProtocol.identifyPayload(
            accessToken = accessToken,
            intentMask = config.enabledIntentMask(),
        )
        webSocket?.sendText(payload)
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
            name = "QQ official bot $appId",
            state = state,
        )
    }

    private fun requireAccount(accountId: String) {
        val current = account ?: error("QQ official bot account is not initialized")
        require(current.accountId == accountId) {
            "QQ official bot account mismatch: expected=${current.accountId} actual=$accountId"
        }
        require(current.state == MessageSinkRouteState.READY) { "QQ official bot account is unavailable: $accountId" }
    }

    private fun <T> notImplemented(): T {
        throw UnsupportedOperationException("QQ official bot recall OpenAPI is not implemented yet")
    }

    private fun AutoCloseable?.closeAndClear(clear: () -> Unit) {
        val closeable = this ?: return
        runCatching { closeable.close() }
            .onFailure { error -> logger.debug(error) { "QQ official bot closeable cleanup failed" } }
        clear()
    }

    private inner class GatewayListener(
        private val generation: Long,
    ) : QqBotWebSocketListener {
        override fun onOpen() {
            if (!isCurrentConnection(generation)) return
            logger.info { "QQ official bot WebSocket opened: appId=${config.appId}" }
        }

        override fun onText(payload: String) {
            if (!isCurrentConnection(generation)) return
            handleText(payload)
        }

        override fun onClosed(statusCode: Int, reason: String) {
            if (!isCurrentConnection(generation)) return
            clearTransport(generation)
            markUnavailable("QQ official bot WebSocket closed: code=$statusCode reason=$reason")
            scheduleReconnect("QQ official bot WebSocket closed: code=$statusCode reason=$reason", closeCurrentSession = false)
        }

        override fun onError(error: Throwable) {
            if (!isCurrentConnection(generation)) return
            markUnavailable(error.message?.takeIf { it.isNotBlank() } ?: "QQ official bot WebSocket error")
            logger.warn(error) { "QQ official bot WebSocket error" }
            scheduleReconnect("QQ official bot WebSocket error", closeCurrentSession = true)
        }
    }
}

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
            { runCatching(task).onFailure { error -> logger.warn(error) { "QQ official bot heartbeat send failed" } } },
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
            { runCatching(task).onFailure { error -> logger.warn(error) { "QQ official bot reconnect task failed" } } },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable { future.cancel(false) }
    }
}
