package top.colter.dynamic.qqbot

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.MessageDelivery
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class QqBotGatewayPluginSendTest {
    @Test
    fun `sendMessage 发送单聊文本并返回回执`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(TargetAddress.of("qq", TargetKind.USER, "user-openid"), "hello"),
            routeId = "qqbot:qq:app-id",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals("user-send-1", sent.sinkMessageId)
        assertEquals("qqbot:qq:app-id", sent.sinkRouteId)
        assertEquals("app-id", sent.sinkAccountId)
        assertEquals("qqbot", sent.sinkTransportId)
        assertEquals(listOf("app-id:user:user-openid:hello"), gateway.sendCalls)
    }

    @Test
    fun `sendCommandResult 发送群聊文本并返回回执`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendCommandResult(
            request = CommandResultSendRequest(
                target = CommandTarget(TargetAddress.of("qq", TargetKind.GROUP, "group-openid"), senderId = "member-openid"),
                chain = listOf(MessageBatch(listOf(MessageContent.Text("ok")))),
                inReplyTo = "trace-id",
            ),
            routeId = "qqbot:qq:app-id",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals("group-send-1", sent.sinkMessageId)
        assertEquals(listOf("app-id:group:group-openid:ok"), gateway.sendCalls)
    }

    @Test
    fun `sendCommandResult 使用 trace 回复上下文作为官方被动回复 id`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        val replyContexts = QqBotReplyContextStore(nowMillis = { 1_000L })
        replyContexts.remember(
            traceId = "trace-id",
            incoming = IncomingMessage(
                platformId = QQBOT_PLATFORM_ID,
                target = TargetAddress.of("qq", TargetKind.GROUP, "group-openid", accountId = "app-id"),
                senderId = "member-openid",
                botAccountId = "app-id",
                messageId = "group-msg-1",
                text = "@bot ping",
            ),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("replyContextStore", replyContexts)
        plugin.setPrivate("running", true)

        val result = plugin.sendCommandResult(
            request = CommandResultSendRequest(
                target = CommandTarget(TargetAddress.of("qq", TargetKind.GROUP, "group-openid"), senderId = "member-openid"),
                chain = listOf(MessageBatch(listOf(MessageContent.Text("pong")))),
                inReplyTo = "trace-id",
            ),
            routeId = "qqbot:qq:app-id",
        )

        assertIs<MessageSendResult.Sent>(result)
        assertEquals("group-msg-1", gateway.sentRequests.single().replyToMessageId)
    }

    @Test
    fun `sendMessage 拒绝不可用账号且不发送`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway(state = MessageSinkRouteState.UNAVAILABLE)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(TargetAddress.of("qq", TargetKind.USER, "user-openid"), "hello"),
            routeId = "qqbot:qq:app-id",
        )

        val failed = assertIs<MessageSendResult.Failed>(result)
        assertEquals("QQ 官方机器人账号不可用：app-id", failed.reason)
        assertEquals(emptyList(), gateway.sendCalls)
    }

    @Test
    fun `recallMessage 撤回单聊消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq", TargetKind.USER, "user-openid"),
                sinkMessageId = "user-send-1",
            ),
            routeId = "qqbot:qq:app-id",
        )

        assertIs<MessageRecallResult.Recalled>(result)
        assertEquals(listOf("app-id:user:user-openid:user-send-1"), gateway.recallCalls)
    }

    @Test
    fun `recallMessage 撤回群聊消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq", TargetKind.GROUP, "group-openid"),
                sinkMessageId = "group-send-1",
            ),
            routeId = "qqbot:qq:app-id",
        )

        assertIs<MessageRecallResult.Recalled>(result)
        assertEquals(listOf("app-id:group:group-openid:group-send-1"), gateway.recallCalls)
    }

    @Test
    fun `recallMessage 拒绝空消息 id`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq", TargetKind.GROUP, "group-openid"),
                sinkMessageId = "   ",
            ),
            routeId = "qqbot:qq:app-id",
        )

        val failed = assertIs<MessageRecallResult.Failed>(result)
        assertEquals("QQ 官方机器人消息 ID 为空，无法撤回", failed.reason)
        assertEquals(emptyList(), gateway.recallCalls)
    }

    @Test
    fun `recallMessage 拒绝不可用账号`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway(state = MessageSinkRouteState.UNAVAILABLE)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq", TargetKind.USER, "user-openid"),
                sinkMessageId = "user-send-1",
            ),
            routeId = "qqbot:qq:app-id",
        )

        val failed = assertIs<MessageRecallResult.Failed>(result)
        assertEquals("QQ 官方机器人账号不可用：app-id", failed.reason)
        assertEquals(emptyList(), gateway.recallCalls)
    }

    private fun QqBotGatewayPlugin.prepareSendTest(): QqBotGatewayPlugin {
        setPrivate("config", QqBotConfig())
        return this
    }

    private fun deliveryRequest(target: TargetAddress, text: String): MessageDeliveryRequest {
        val message = Message(
            id = "message-1",
            time = 1,
            targets = listOf(target),
            batches = listOf(MessageBatch(listOf(MessageContent.Text(text)))),
        )
        return MessageDeliveryRequest(
            delivery = MessageDelivery(
                id = 1,
                messageId = message.id,
                target = target,
                status = DeliveryStatus.SENDING,
                attempts = 1,
                createdAtEpochSeconds = 1,
                updatedAtEpochSeconds = 1,
            ),
            message = message,
        )
    }

    private class FakeGateway(
        private val state: MessageSinkRouteState = MessageSinkRouteState.READY,
    ) : QqBotGateway {
        val sendCalls: MutableList<String> = mutableListOf()
        val sentRequests: MutableList<QqBotSendRequest> = mutableListOf()
        val recallCalls: MutableList<String> = mutableListOf()

        override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) {
        }

        override suspend fun availableAccounts(): List<QqBotRuntimeAccount> {
            return listOf(QqBotRuntimeAccount(accountId = "app-id", name = "测试 Bot", state = state))
        }

        override suspend fun sendUserMessage(
            accountId: String,
            openid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt {
            sentRequests += request
            sendCalls += "$accountId:user:$openid:${request.content}"
            return QqBotSendReceipt(messageId = "user-send-1")
        }

        override suspend fun sendGroupMessage(
            accountId: String,
            groupOpenid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt {
            sentRequests += request
            sendCalls += "$accountId:group:$groupOpenid:${request.content}"
            return QqBotSendReceipt(messageId = "group-send-1")
        }

        override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
            recallCalls += "$accountId:user:$openid:$messageId"
        }

        override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
            recallCalls += "$accountId:group:$groupOpenid:$messageId"
        }

        override fun close() {
        }
    }
}

private fun Any.setPrivate(name: String, value: Any?) {
    val field = javaClass.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
}
