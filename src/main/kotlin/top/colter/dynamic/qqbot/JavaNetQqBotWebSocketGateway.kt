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
) : QqBotGateway {
    @Volatile
    private var webSocket: QqBotWebSocketSession? = null
    @Volatile
    private var heartbeatTask: AutoCloseable? = null
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

    override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) {
        if (webSocket != null) return

        QqBotConfigForm.validate(config)
        incomingEventHandler = onIncomingEvent
        closing = false
        account = initialAccount(MessageSinkRouteState.UNAVAILABLE)

        withContext(Dispatchers.IO) {
            val endpoint = openApiClient.gatewayBotEndpoint()
            accessToken = openApiClient.accessToken().value
            logger.info {
                "准备连接 QQ 官方机器人 WebSocket：appId=${config.appId}，url=${endpoint.url}，shards=${endpoint.shards ?: "-"}"
            }
            webSocket = connector.connect(
                url = endpoint.url,
                listener = GatewayListener(),
            )
        }
    }

    override suspend fun availableAccounts(): List<QqBotRuntimeAccount> {
        return listOfNotNull(account)
    }

    override suspend fun sendUserMessage(
        accountId: String,
        openid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = notImplemented()

    override suspend fun sendGroupMessage(
        accountId: String,
        groupOpenid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = notImplemented()

    override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
        notImplemented<Unit>()
    }

    override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
        notImplemented<Unit>()
    }

    override fun close() {
        closing = true
        incomingEventHandler = null
        heartbeatTask?.closeQuietly()
        heartbeatTask = null
        val session = webSocket
        webSocket = null
        session?.close(1000, "shutdown")
        markUnavailable("QQ 官方机器人 WebSocket 已关闭")
    }

    private fun handleText(payload: String) {
        val frame = runCatching { QqBotWebSocketProtocol.parseFrame(payload) }
            .onFailure { error ->
                logger.warn(error) { "QQ 官方机器人 WebSocket 包解析失败" }
            }
            .getOrNull()
            ?: return

        frame.sequence?.let { latestSequence = it }

        when (frame.opcode) {
            QqBotOpcode.HELLO -> handleHello(payload)
            QqBotOpcode.DISPATCH -> handleDispatch(payload)
            QqBotOpcode.HEARTBEAT_ACK -> logger.debug { "QQ 官方机器人 WebSocket 心跳已确认" }
            QqBotOpcode.RECONNECT -> handleReconnectRequest()
            QqBotOpcode.INVALID_SESSION -> handleInvalidSession()
            else -> logger.debug { "忽略 QQ 官方机器人 WebSocket 包：op=${frame.opcode.code} type=${frame.type.orEmpty()}" }
        }
    }

    private fun handleHello(payload: String) {
        val hello = runCatching { QqBotWebSocketProtocol.parseHello(payload) }
            .onFailure { error -> logger.warn(error) { "QQ 官方机器人 Hello 包解析失败" } }
            .getOrNull()
            ?: return

        startHeartbeat(hello.heartbeatIntervalMillis)
        sendIdentify()
        markReady()
        logger.info { "QQ 官方机器人 WebSocket Hello 已处理：heartbeat=${hello.heartbeatIntervalMillis}ms" }
    }

    private fun handleDispatch(payload: String) {
        val event = QqBotWebSocketProtocol.incomingEventOrNull(payload) ?: return
        incomingEventHandler?.invoke(event)
    }

    private fun handleReconnectRequest() {
        markUnavailable("QQ 官方机器人 WebSocket 收到 Reconnect 指令")
        logger.warn { "QQ 官方机器人 WebSocket 收到 Reconnect 指令；自动重连将在后续版本补齐" }
    }

    private fun handleInvalidSession() {
        markUnavailable("QQ 官方机器人 WebSocket session 无效")
        logger.warn { "QQ 官方机器人 WebSocket session 无效；请检查 token、intent 与分片配置" }
    }

    private fun startHeartbeat(intervalMillis: Long) {
        heartbeatTask?.closeQuietly()
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

    private fun <T> notImplemented(): T {
        throw UnsupportedOperationException("QQ 官方机器人 OpenAPI 消息发送实现尚未接入")
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
            .onFailure { error -> logger.debug(error) { "QQ 官方机器人心跳任务关闭失败" } }
    }

    private inner class GatewayListener : QqBotWebSocketListener {
        override fun onOpen() {
            logger.info { "QQ 官方机器人 WebSocket 已打开：appId=${config.appId}" }
        }

        override fun onText(payload: String) {
            handleText(payload)
        }

        override fun onClosed(statusCode: Int, reason: String) {
            webSocket = null
            heartbeatTask?.closeQuietly()
            heartbeatTask = null
            markUnavailable("QQ 官方机器人 WebSocket 已关闭：code=$statusCode，reason=$reason")
        }

        override fun onError(error: Throwable) {
            markUnavailable(error.message?.takeIf { it.isNotBlank() } ?: "QQ 官方机器人 WebSocket 发生错误")
            logger.warn(error) { "QQ 官方机器人 WebSocket 发生错误" }
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
            { runCatching(task).onFailure { error -> logger.warn(error) { "QQ 官方机器人心跳发送失败" } } },
            initialDelayMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable { future.cancel(false) }
    }
}
