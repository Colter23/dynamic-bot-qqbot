package top.colter.dynamic.qqbot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.command.CommandPublishRequest
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class QqBotGatewayPluginIncomingTest {
    @Test
    fun `入站事件应异步发布`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        val published = CompletableDeferred<IncomingMessage>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { message ->
                publishStarted.complete(Unit)
                releasePublish.await()
                published.complete(message)
            },
        )

        try {
            plugin.setPrivate("running", true)
            val callbackReturned = async(Dispatchers.Default) {
                plugin.invokeIncoming(c2cEvent())
            }

            withTimeout(500) { callbackReturned.await() }
            withTimeout(500) { publishStarted.await() }
            assertFalse(published.isCompleted)

            releasePublish.complete(Unit)
            assertEquals("msg-1", withTimeout(500) { published.await() }.messageId)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `关闭自动发布时忽略入站事件`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessage>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { message -> published.complete(message) },
        )

        try {
            plugin.setPrivate("running", true)
            plugin.setPrivate("config", QqBotConfig(autoPublishIncoming = false))

            plugin.invokeIncoming(c2cEvent())

            assertNull(withTimeoutOrNull(200) { published.await() })
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `调试回显命中文本时发送官方被动回复`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessage>()
        val gateway = FakeGateway()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { message -> published.complete(message) },
        )

        try {
            plugin.setPrivate("running", true)
            plugin.setPrivate(
                "config",
                QqBotConfig(
                    autoPublishIncoming = false,
                    debugEchoText = "ping",
                    debugEchoReply = "pong",
                ),
            )
            plugin.setPrivate("gateway", gateway)

            plugin.invokeIncoming(c2cEvent(content = "ping"))

            val call = withTimeout(500) { gateway.userSend.await() }
            assertEquals("bot-app-id", call.accountId)
            assertEquals("user-openid", call.targetId)
            assertEquals("pong", call.request.content)
            assertEquals("msg-1", call.request.replyToMessageId)
            assertNull(withTimeoutOrNull(200) { published.await() })
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `调试回显支持群聊事件`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = FakeGateway()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = {},
        )

        try {
            plugin.setPrivate("running", true)
            plugin.setPrivate(
                "config",
                QqBotConfig(
                    debugEchoText = "@bot ping",
                    debugEchoReply = "pong",
                ),
            )
            plugin.setPrivate("gateway", gateway)

            plugin.invokeIncoming(groupEvent())

            val call = withTimeout(500) { gateway.groupSend.await() }
            assertEquals("bot-app-id", call.accountId)
            assertEquals("group-openid", call.targetId)
            assertEquals("pong", call.request.content)
            assertEquals("group-msg-1", call.request.replyToMessageId)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `入站事件应发布命令请求`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedIncoming = CompletableDeferred<IncomingMessage>()
        val publishedCommand = CompletableDeferred<CommandPublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { message -> publishedIncoming.complete(message) },
            publishCommand = { request -> publishedCommand.complete(request) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(groupEvent())

            val incoming = withTimeout(500) { publishedIncoming.await() }
            val command = withTimeout(500) { publishedCommand.await() }
            assertEquals("group-msg-1", incoming.messageId)
            assertEquals(QQBOT_PLUGIN_ID, command.sourcePlugin)
            assertEquals("@bot ping", command.rawText)
            assertTrue(command.traceId.isNotBlank())
            assertEquals("qq", command.context.platform)
            assertEquals(TargetKind.GROUP, command.context.chatType)
            assertEquals("group-openid", command.context.chatId)
            assertEquals("member-openid", command.context.senderId)
            assertEquals("bot-app-id", command.context.botAccountId)
            assertEquals("bot-app-id", command.context.target.accountId)
            assertEquals(setOf("bot-app-id"), command.context.mentionedAccountIds)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `空白入站文本不发布命令请求`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedIncoming = CompletableDeferred<IncomingMessage>()
        val publishedCommand = CompletableDeferred<CommandPublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { message -> publishedIncoming.complete(message) },
            publishCommand = { request -> publishedCommand.complete(request) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(c2cEvent(content = "   "))

            assertEquals("", withTimeout(500) { publishedIncoming.await() }.text)
            assertNull(withTimeoutOrNull(200) { publishedCommand.await() })
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `不支持的入站事件会被忽略`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessage>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { message -> published.complete(message) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(
                QqBotIncomingEvent(
                    type = "GROUP_ADD_ROBOT",
                    rawPayload = """{"op":0,"s":1,"t":"GROUP_ADD_ROBOT","d":{"group_openid":"g"}}""",
                ),
            )

            assertNull(withTimeoutOrNull(200) { published.await() })
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    private fun QqBotGatewayPlugin.prepareIncomingTest(
        scope: CoroutineScope,
        publishIncoming: suspend (IncomingMessage) -> Unit,
        publishCommand: suspend (CommandPublishRequest) -> Unit = {},
    ) {
        setPrivate("pluginId", QQBOT_PLUGIN_ID)
        setPrivate("config", QqBotConfig())
        setPrivate("pluginScope", scope)
        setPrivate("incomingMessagePublisher", IncomingMessagePublisher { message -> publishIncoming(message) })
        setPrivate("commandPublisher", CommandPublisher { request -> publishCommand(request) })
        callPrivate("startIncomingScope")
    }

    private data class SendCall(
        val accountId: String,
        val targetId: String,
        val request: QqBotSendRequest,
    )

    private class FakeGateway : QqBotGateway {
        val userSend: CompletableDeferred<SendCall> = CompletableDeferred()
        val groupSend: CompletableDeferred<SendCall> = CompletableDeferred()

        override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) {
        }

        override suspend fun availableAccounts(): List<QqBotRuntimeAccount> {
            return listOf(QqBotRuntimeAccount("bot-app-id", state = MessageSinkRouteState.READY))
        }

        override suspend fun sendUserMessage(
            accountId: String,
            openid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt {
            userSend.complete(SendCall(accountId, openid, request))
            return QqBotSendReceipt("user-send-1")
        }

        override suspend fun sendGroupMessage(
            accountId: String,
            groupOpenid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt {
            groupSend.complete(SendCall(accountId, groupOpenid, request))
            return QqBotSendReceipt("group-send-1")
        }

        override suspend fun sendUserMediaMessage(
            accountId: String,
            openid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = unsupported()

        override suspend fun sendGroupMediaMessage(
            accountId: String,
            groupOpenid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = unsupported()

        override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
            unsupported<Unit>()
        }

        override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
            unsupported<Unit>()
        }

        override fun close() {
        }

        private fun <T> unsupported(): T {
            throw UnsupportedOperationException("测试网关未实现该操作")
        }
    }

    private fun c2cEvent(content: String = "hello"): QqBotIncomingEvent {
        return QqBotIncomingEvent(
            type = QQBOT_C2C_MESSAGE_CREATE,
            botAccountId = "bot-app-id",
            rawPayload = """
                {
                  "op":0,
                  "s":1,
                  "t":"C2C_MESSAGE_CREATE",
                  "d":{
                    "id":"msg-1",
                    "author":{"user_openid":"user-openid"},
                    "content":"$content"
                  }
                }
            """.trimIndent(),
        )
    }

    private fun groupEvent(): QqBotIncomingEvent {
        return QqBotIncomingEvent(
            type = QQBOT_GROUP_AT_MESSAGE_CREATE,
            botAccountId = "bot-app-id",
            rawPayload = """
                {
                  "op":0,
                  "s":2,
                  "t":"GROUP_AT_MESSAGE_CREATE",
                  "d":{
                    "id":"group-msg-1",
                    "group_openid":"group-openid",
                    "author":{"member_openid":"member-openid"},
                    "content":"@bot ping"
                  }
                }
            """.trimIndent(),
        )
    }

    private fun QqBotGatewayPlugin.invokeIncoming(event: QqBotIncomingEvent) {
        onIncomingEvent(event)
    }

    private fun Any.callPrivate(name: String) {
        val method = javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(this)
    }

    private fun Any.setPrivate(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }
}
