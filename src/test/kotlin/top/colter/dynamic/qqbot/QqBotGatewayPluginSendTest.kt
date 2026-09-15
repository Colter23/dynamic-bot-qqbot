package top.colter.dynamic.qqbot

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdviceRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryConfidence
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryMethod
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeStatus
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class QqBotGatewayPluginSendTest {
    @Test
    fun `普通发送单聊文本并返回回执`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(TargetAddress.of("qq-official", TargetKind.USER, "user-openid"), "hello"),
            routeId = "qqbot:qq-official:app-id",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals("user-send-1", sent.sinkMessageId)
        assertEquals("qqbot:qq-official:app-id", sent.sinkRouteId)
        assertEquals("app-id", sent.sinkAccountId)
        assertEquals("qqbot", sent.sinkTransportId)
        assertEquals(listOf("app-id:user:user-openid:hello"), gateway.sendCalls)
    }

    @Test
    fun `普通发送群聊图片 URL 走富媒体消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(
                target = TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"),
                batches = listOf(
                    MessageBatch(listOf(MessageContent.Text("先看图"))),
                    MessageBatch(
                        listOf(
                            MessageContent.Image(
                                fallbackText = "图片说明",
                                image = MediaRef(uri = "https://example.com/a.png", kind = MediaKind.IMAGE),
                            ),
                        ),
                    ),
                ),
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals(listOf("group-send-1", "group-media-send-1"), sent.sinkMessageIds)
        assertEquals(
            listOf(
                "app-id:group:group-openid:先看图",
                "app-id:group-media:group-openid:IMAGE:https://example.com/a.png:图片说明",
            ),
            gateway.sendCalls,
        )
        assertEquals(QQBOT_MEDIA_MSG_TYPE, gateway.sentRequests.last().msgType)
    }

    @Test
    fun `同一批次中的图片和文本应合并为一条富媒体消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(
                target = TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"),
                batches = listOf(
                    MessageBatch(
                        listOf(
                            MessageContent.Image(
                                fallbackText = "",
                                image = MediaRef(
                                    uri = "https://example.com/b.png",
                                    kind = MediaKind.IMAGE,
                                ),
                            ),
                            MessageContent.Text("\n优妮-KokoroUni 发布了新动态\nhttps://t.bilibili.com/1215152419671900183"),
                        ),
                    ),
                    MessageBatch(listOf(MessageContent.Text("2026年06月18日 14:12:00"))),
                ),
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals(listOf("group-media-send-1", "group-send-1"), sent.sinkMessageIds)
        assertEquals(
            listOf(
                "app-id:group-media:group-openid:IMAGE:https://example.com/b.png:优妮-KokoroUni 发布了新动态\nhttps://t.bilibili.com/1215152419671900183",
                "app-id:group:group-openid:2026年06月18日 14:12:00",
            ),
            gateway.sendCalls,
        )
        assertEquals(QQBOT_MEDIA_MSG_TYPE, gateway.sentRequests[0].msgType)
        assertEquals(QQBOT_TEXT_MSG_TYPE, gateway.sentRequests[1].msgType)
    }

    @Test
    fun `普通发送本地图片时回退为文本`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(
                target = TargetAddress.of("qq-official", TargetKind.USER, "user-openid"),
                batches = listOf(
                    MessageBatch(
                        listOf(
                            MessageContent.Image(
                                fallbackText = "本地图片",
                                image = MediaRef(uri = "file:/tmp/a.png", kind = MediaKind.IMAGE),
                            ),
                        ),
                    ),
                ),
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        assertIs<MessageSendResult.Sent>(result)
        assertEquals(listOf("app-id:user:user-openid:本地图片"), gateway.sendCalls)
        assertEquals(emptyList(), gateway.mediaCalls)
    }

    @Test
    fun `普通发送本地图片走分片上传富媒体消息`() = runBlocking {
        val image = createTempImage()
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(
                target = TargetAddress.of("qq-official", TargetKind.USER, "user-openid"),
                batches = listOf(
                    MessageBatch(
                        listOf(
                            MessageContent.Image(
                                fallbackText = "图片说明",
                                image = MediaRef(uri = image.toUri().toString(), kind = MediaKind.IMAGE),
                            ),
                        ),
                    ),
                ),
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        assertIs<MessageSendResult.Sent>(result)
        assertEquals(image, gateway.mediaCalls.single().localFile)
        assertEquals(QQBOT_MEDIA_MSG_TYPE, gateway.sentRequests.single().msgType)
    }

    @Test
    fun `媒体投递建议支持本地文件并保留签名链接为未知`() = runBlocking {
        val image = createTempImage()
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        plugin.setPrivate("gateway", FakeGateway())
        plugin.setPrivate("running", true)

        val advice = plugin.adviseMediaDelivery(
            MessageSinkMediaDeliveryAdviceRequest(routeId = "qqbot:qq-official:app-id"),
        )
        val localProbe = plugin.probeMediaDelivery(
            MessageSinkMediaDeliveryProbeRequest(
                routeId = "qqbot:qq-official:app-id",
                method = MessageSinkMediaDeliveryMethod.LOCAL_FILE,
                uri = image.toUri().toString(),
            ),
        )
        val missingProbe = plugin.probeMediaDelivery(
            MessageSinkMediaDeliveryProbeRequest(
                routeId = "qqbot:qq-official:app-id",
                method = MessageSinkMediaDeliveryMethod.LOCAL_FILE,
                uri = "file:///qqbot/missing/image.png",
            ),
        )
        val signedUrlProbe = plugin.probeMediaDelivery(
            MessageSinkMediaDeliveryProbeRequest(
                routeId = "qqbot:qq-official:app-id",
                method = MessageSinkMediaDeliveryMethod.SIGNED_URL,
                uri = "http://127.0.0.1:8080/media/outbound/a",
            ),
        )

        assertEquals(MessageSinkMediaDeliveryConfidence.CONFIRMED, advice.localFileConfidence)
        assertEquals(MessageSinkMediaDeliveryProbeStatus.AVAILABLE, localProbe.status)
        assertEquals(MessageSinkMediaDeliveryProbeStatus.UNAVAILABLE, missingProbe.status)
        assertEquals(MessageSinkMediaDeliveryProbeStatus.UNKNOWN, signedUrlProbe.status)
    }

    @Test
    fun `消息出口路线带上机器人名称与头像`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway(
            avatar = MediaRef(uri = "https://thirdqq.qlogo.cn/g?b=oidb&k=abc&s=0", kind = MediaKind.AVATAR),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val route = plugin.listMessageSinkRoutes().single()

        assertEquals("qqbot:qq-official:app-id", route.routeId)
        assertEquals("app-id", route.accountId)
        assertEquals("测试 Bot", route.accountName)
        assertEquals("https://thirdqq.qlogo.cn/g?b=oidb&k=abc&s=0", route.accountAvatar?.uri)
        assertEquals(QQBOT_PLATFORM_ID, route.targetPlatformId)
        assertEquals(MessageSinkRouteState.READY, route.state)
    }

    @Test
    fun `发送命令结果到群聊并返回回执`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = sendRequest(
                target = TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"),
                batches = listOf(MessageBatch(listOf(MessageContent.Text("ok")))),
                replyToMessageId = "trace-id",
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals("group-send-1", sent.sinkMessageId)
        assertEquals(listOf("app-id:group:group-openid:ok"), gateway.sendCalls)
    }

    @Test
    fun `发送命令结果时使用请求回复目标作为官方被动回复 ID`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = sendRequest(
                target = TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"),
                batches = listOf(MessageBatch(listOf(MessageContent.Text("pong")))),
                replyToMessageId = "group-msg-1",
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        assertIs<MessageSendResult.Sent>(result)
        assertEquals("group-msg-1", gateway.sentRequests.single().replyToMessageId)
    }

    @Test
    fun `普通发送拒绝不可用账号且不发送`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway(state = MessageSinkRouteState.UNAVAILABLE)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(TargetAddress.of("qq-official", TargetKind.USER, "user-openid"), "hello"),
            routeId = "qqbot:qq-official:app-id",
        )

        val failed = assertIs<MessageSendResult.Failed>(result)
        assertEquals("QQ 官方机器人账号不可用：app-id", failed.reason)
        assertEquals(emptyList(), gateway.sendCalls)
    }

    @Test
    fun `发送超时上报状态未知而非可重试失败`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FailingGateway(java.net.http.HttpTimeoutException("请求超时"))
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"), "hello"),
            routeId = "qqbot:qq-official:app-id",
        )

        val uncertain = assertIs<MessageSendResult.Uncertain>(result)
        assertEquals("请求超时", uncertain.reason)
        assertEquals("qqbot", uncertain.sinkTransportId)
    }

    @Test
    fun `业务错误码判定为不可重试时透传终态失败`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FailingGateway(
            QqBotOpenApiException(
                action = "发送 QQ 官方机器人消息",
                statusCode = 400,
                code = "40034005",
                message = "回复消息msg_id已过期",
                body = "",
            ),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"), "hello"),
            routeId = "qqbot:qq-official:app-id",
        )

        val failed = assertIs<MessageSendResult.Failed>(result)
        assertEquals(false, failed.retryable)
        assertEquals(true, failed.reason.contains("回复消息msg_id已过期"))
    }

    @Test
    fun `官方发送异常码上报状态未知`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FailingGateway(
            QqBotOpenApiException(
                action = "发送 QQ 官方机器人消息",
                statusCode = 500,
                code = "50055002",
                message = "消息发送异常，请稍后重试",
                body = "",
            ),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendMessage(
            request = deliveryRequest(TargetAddress.of("qq-official", TargetKind.USER, "user-openid"), "hello"),
            routeId = "qqbot:qq-official:app-id",
        )

        assertIs<MessageSendResult.Uncertain>(result)
    }

    @Test
    fun `被动回复重试时按已用 msgSeq 续编`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val request = sendRequest(
            target = TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"),
            batches = listOf(MessageBatch(listOf(MessageContent.Text("回复")))),
            replyToMessageId = "group-msg-9",
        )
        plugin.sendMessage(request, routeId = "qqbot:qq-official:app-id")
        plugin.sendMessage(request, routeId = "qqbot:qq-official:app-id")

        assertEquals(listOf(1, 2), gateway.sentRequests.map { it.msgSeq })
        assertEquals(listOf("group-msg-9", "group-msg-9"), gateway.sentRequests.map { it.replyToMessageId })
    }

    @Test
    fun `撤回单聊消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq-official", TargetKind.USER, "user-openid"),
                sinkMessageId = "user-send-1",
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        assertIs<MessageRecallResult.Recalled>(result)
        assertEquals(listOf("app-id:user:user-openid:user-send-1"), gateway.recallCalls)
    }

    @Test
    fun `撤回群聊消息`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"),
                sinkMessageId = "group-send-1",
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        assertIs<MessageRecallResult.Recalled>(result)
        assertEquals(listOf("app-id:group:group-openid:group-send-1"), gateway.recallCalls)
    }

    @Test
    fun `撤回消息时拒绝空消息 ID`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway()
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq-official", TargetKind.GROUP, "group-openid"),
                sinkMessageId = "   ",
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        val failed = assertIs<MessageRecallResult.Failed>(result)
        assertEquals("QQ 官方机器人消息 ID 为空，无法撤回", failed.reason)
        assertEquals(emptyList(), gateway.recallCalls)
    }

    @Test
    fun `撤回消息时拒绝不可用账号`() = runBlocking {
        val plugin = QqBotGatewayPlugin().prepareSendTest()
        val gateway = FakeGateway(state = MessageSinkRouteState.UNAVAILABLE)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.recallMessage(
            request = MessageRecallRequest(
                target = TargetAddress.of("qq-official", TargetKind.USER, "user-openid"),
                sinkMessageId = "user-send-1",
            ),
            routeId = "qqbot:qq-official:app-id",
        )

        val failed = assertIs<MessageRecallResult.Failed>(result)
        assertEquals("QQ 官方机器人账号不可用：app-id", failed.reason)
        assertEquals(emptyList(), gateway.recallCalls)
    }

    private fun QqBotGatewayPlugin.prepareSendTest(): QqBotGatewayPlugin {
        setPrivate("config", QqBotConfig())
        return this
    }

    private fun createTempImage(): Path {
        val dir = Files.createTempDirectory("qqbot-send-test")
        val file = dir.resolve("image.png")
        Files.write(file, byteArrayOf(1, 2, 3, 4))
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        return file
    }

    private fun deliveryRequest(target: TargetAddress, text: String): MessageSendRequest {
        return sendRequest(
            target = target,
            batches = listOf(MessageBatch(listOf(MessageContent.Text(text)))),
        )
    }

    private fun deliveryRequest(target: TargetAddress, batches: List<MessageBatch>): MessageSendRequest {
        return sendRequest(target = target, batches = batches)
    }

    private fun sendRequest(
        target: TargetAddress,
        batches: List<MessageBatch>,
        replyToMessageId: String? = null,
    ): MessageSendRequest {
        val message = Message(
            id = "message-1",
            time = 1,
            targets = listOf(target),
            batches = batches,
            replyToMessageId = replyToMessageId,
        )
        return MessageSendRequest(
            message = message,
            target = target,
        )
    }

    private class FakeGateway(
        private val state: MessageSinkRouteState = MessageSinkRouteState.READY,
        private val avatar: MediaRef? = null,
    ) : QqBotGateway {
        val sendCalls: MutableList<String> = mutableListOf()
        val sentRequests: MutableList<QqBotSendRequest> = mutableListOf()
        val mediaCalls: MutableList<QqBotMediaResourceSource> = mutableListOf()
        val recallCalls: MutableList<String> = mutableListOf()

        override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) {
        }

        override suspend fun availableAccounts(): List<QqBotRuntimeAccount> {
            return listOf(
                QqBotRuntimeAccount(accountId = "app-id", name = "测试 Bot", state = state, avatar = avatar),
            )
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

        override suspend fun sendUserMediaMessage(
            accountId: String,
            openid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt {
            sentRequests += request
            mediaCalls += media
            sendCalls += "$accountId:user-media:$openid:${media.fileType}:${media.debugValue()}:${request.content}"
            return QqBotSendReceipt(messageId = "user-media-send-1")
        }

        override suspend fun sendGroupMediaMessage(
            accountId: String,
            groupOpenid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt {
            sentRequests += request
            mediaCalls += media
            sendCalls += "$accountId:group-media:$groupOpenid:${media.fileType}:${media.debugValue()}:${request.content}"
            return QqBotSendReceipt(messageId = "group-media-send-1")
        }

        override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
            recallCalls += "$accountId:user:$openid:$messageId"
        }

        override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
            recallCalls += "$accountId:group:$groupOpenid:$messageId"
        }

        override fun close() {
        }

        private fun QqBotMediaResourceSource.debugValue(): String {
            return url ?: localFile?.fileName?.toString().orEmpty()
        }
    }

    private class FailingGateway(
        private val error: Throwable,
    ) : QqBotGateway {
        override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) {
        }

        override suspend fun availableAccounts(): List<QqBotRuntimeAccount> {
            return listOf(QqBotRuntimeAccount(accountId = "app-id", name = "测试 Bot"))
        }

        override suspend fun sendUserMessage(
            accountId: String,
            openid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = throw error

        override suspend fun sendGroupMessage(
            accountId: String,
            groupOpenid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = throw error

        override suspend fun sendUserMediaMessage(
            accountId: String,
            openid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = throw error

        override suspend fun sendGroupMediaMessage(
            accountId: String,
            groupOpenid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = throw error

        override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
            throw error
        }

        override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
            throw error
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
