package top.colter.dynamic.qqbot

import java.security.MessageDigest
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
            assertEquals("msg-1", request.replyToMessageId)
            assertEquals("C2C_MESSAGE_CREATE:msg-1", request.sourceEventId)
            assertEquals("qqbot-gateway:bot-app-id:C2C_MESSAGE_CREATE:USER:user-openid:msg-1", request.dedupeKey)
            assertEquals("qqbot-gateway:${request.dedupeKey!!.sha256Hex()}", request.traceId)
            assertEquals(true, request.receivedAtEpochSeconds != null && request.receivedAtEpochSeconds!! > 0)
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
            assertEquals("group-msg-1", request.replyToMessageId)
            assertEquals("qqbot-gateway:${request.dedupeKey!!.sha256Hex()}", request.traceId)
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

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
