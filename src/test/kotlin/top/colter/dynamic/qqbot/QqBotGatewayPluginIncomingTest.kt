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
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class QqBotGatewayPluginIncomingTest {
    @Test
    fun `入站事件应异步发布`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request ->
                publishStarted.complete(Unit)
                releasePublish.await()
                published.complete(request)
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
            val request = withTimeout(500) { published.await() }
            assertEquals("msg-1", request.message.messageId)
            assertEquals("msg-1", request.traceId)
            assertEquals("msg-1", request.replyToMessageId)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `关闭自动发布时忽略入站事件`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> published.complete(request) },
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
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        val gateway = FakeGateway()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> published.complete(request) },
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
    fun `调试回显支持群普通消息的开头 at 文本`() = runBlocking {
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
                    debugEchoText = "测试",
                    debugEchoReply = "连通性正常",
                ),
            )
            plugin.setPrivate("gateway", gateway)

            plugin.invokeIncoming(groupMessageEvent(content = "<@bot-member-openid> 测试"))

            val call = withTimeout(500) { gateway.groupSend.await() }
            assertEquals("bot-app-id", call.accountId)
            assertEquals("group-openid", call.targetId)
            assertEquals("连通性正常", call.request.content)
            assertEquals("group-msg-2", call.request.replyToMessageId)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `调试图片回显命中文本时发送群聊富媒体回复`() = runBlocking {
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
                    autoPublishIncoming = false,
                    debugImageEchoText = "发图",
                    debugImageEchoSource = "base64://aW1hZ2UtYnl0ZXM=",
                    debugImageEchoCaption = "图片发送测试",
                ),
            )
            plugin.setPrivate("gateway", gateway)

            plugin.invokeIncoming(groupMessageEvent(content = "<@bot-member-openid> 发图"))

            val call = withTimeout(500) { gateway.groupMediaSend.await() }
            assertEquals("bot-app-id", call.accountId)
            assertEquals("group-openid", call.targetId)
            assertEquals(QqBotMediaFileType.IMAGE, call.media.fileType)
            assertEquals("aW1hZ2UtYnl0ZXM=", call.media.fileDataBase64)
            assertEquals("图片发送测试", call.request.content)
            assertEquals(QQBOT_MEDIA_MSG_TYPE, call.request.msgType)
            assertEquals("group-msg-2", call.request.replyToMessageId)
            assertEquals(1, call.request.msgSeq)
            assertNull(withTimeoutOrNull(200) { gateway.groupSend.await() })
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `文字和图片调试回显共用触发词时使用不同回复序号`() = runBlocking {
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
                    autoPublishIncoming = false,
                    debugEchoText = "测试",
                    debugEchoReply = "文字正常",
                    debugImageEchoText = "测试",
                    debugImageEchoSource = "https://example.com/a.jpg",
                    debugImageEchoCaption = "图片正常",
                ),
            )
            plugin.setPrivate("gateway", gateway)

            plugin.invokeIncoming(c2cEvent(content = "测试"))

            val textCall = withTimeout(500) { gateway.userSend.await() }
            val imageCall = withTimeout(500) { gateway.userMediaSend.await() }
            assertEquals(1, textCall.request.msgSeq)
            assertEquals(2, imageCall.request.msgSeq)
            assertEquals("msg-1", imageCall.request.replyToMessageId)
            assertEquals("https://example.com/a.jpg", imageCall.media.url)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `群 at 事件应发布标准入站消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedIncoming = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> publishedIncoming.complete(request) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(groupEvent())

            val request = withTimeout(500) { publishedIncoming.await() }
            val incoming = request.message
            assertEquals("group-msg-1", incoming.messageId)
            assertEquals("group-msg-1", request.traceId)
            assertEquals("group-msg-1", request.replyToMessageId)
            assertEquals("@bot ping", incoming.text)
            assertEquals("group-openid", incoming.target.externalId)
            assertEquals("member-openid", incoming.senderId)
            assertEquals("bot-app-id", incoming.botAccountId)
            assertEquals("bot-app-id", incoming.target.accountId)
            assertEquals(setOf("bot-app-id"), incoming.mentions)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `群普通消息 at 当前机器人时应发布可被主项目解析的命令文本`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedIncoming = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> publishedIncoming.complete(request) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(groupMessageEvent(content = "<@bot-member-openid> /db help"))

            val incoming = withTimeout(500) { publishedIncoming.await() }.message
            assertEquals("<@bot-member-openid> /db help", incoming.text)
            assertEquals("bot-app-id", incoming.botAccountId)
            assertEquals("bot-app-id", incoming.target.accountId)
            assertEquals(setOf("bot-app-id"), incoming.mentions)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `私聊命令上下文应视为发给当前机器人`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedIncoming = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> publishedIncoming.complete(request) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(c2cEvent(content = "/db help"))

            val incoming = withTimeout(500) { publishedIncoming.await() }.message
            assertEquals("/db help", incoming.text)
            assertEquals("bot-app-id", incoming.botAccountId)
            assertEquals("bot-app-id", incoming.target.accountId)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `空白入站文本仍发布入站消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedIncoming = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> publishedIncoming.complete(request) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(c2cEvent(content = "   "))

            assertEquals("", withTimeout(500) { publishedIncoming.await() }.message.text)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `不支持的入站事件会被忽略`() = runBlocking {
        val plugin = QqBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> published.complete(request) },
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
        publishIncoming: suspend (IncomingMessagePublishRequest) -> Unit,
    ) {
        setPrivate("pluginId", QQBOT_PLUGIN_ID)
        setPrivate("config", QqBotConfig())
        setPrivate("pluginScope", scope)
        setPrivate("incomingMessagePublisher", IncomingMessagePublisher { request -> publishIncoming(request) })
        callPrivate("startIncomingScope")
    }

    private data class SendCall(
        val accountId: String,
        val targetId: String,
        val request: QqBotSendRequest,
    )

    private data class MediaSendCall(
        val accountId: String,
        val targetId: String,
        val media: QqBotMediaResourceSource,
        val request: QqBotSendRequest,
    )

    private class FakeGateway : QqBotGateway {
        val userSend: CompletableDeferred<SendCall> = CompletableDeferred()
        val groupSend: CompletableDeferred<SendCall> = CompletableDeferred()
        val userMediaSend: CompletableDeferred<MediaSendCall> = CompletableDeferred()
        val groupMediaSend: CompletableDeferred<MediaSendCall> = CompletableDeferred()

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
        ): QqBotSendReceipt {
            userMediaSend.complete(MediaSendCall(accountId, openid, media, request))
            return QqBotSendReceipt("user-media-send-1")
        }

        override suspend fun sendGroupMediaMessage(
            accountId: String,
            groupOpenid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt {
            groupMediaSend.complete(MediaSendCall(accountId, groupOpenid, media, request))
            return QqBotSendReceipt("group-media-send-1")
        }

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

    private fun groupMessageEvent(content: String = "测试"): QqBotIncomingEvent {
        return QqBotIncomingEvent(
            type = QQBOT_GROUP_MESSAGE_CREATE,
            botAccountId = "bot-app-id",
            rawPayload = """
                {
                  "op":0,
                  "s":3,
                  "t":"GROUP_MESSAGE_CREATE",
                  "d":{
                    "id":"group-msg-2",
                    "group_openid":"group-openid",
                    "author":{"member_openid":"member-openid"},
                    "content":"$content",
                    "mentions":[
                      {"bot":true,"id":"bot-member-openid","is_you":true,"member_openid":"bot-member-openid"}
                    ],
                    "message_type":0
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
