package top.colter.dynamic.qqbot

import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class QqBotWebSocketGatewayTest {
    @Test
    fun `连接时获取接入点并打开 WebSocket`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)

        gateway.connect { }

        assertEquals("wss://api.bot.qq.com/websocket", connector.connectedUrl)
        assertEquals(
            QqBotRuntimeAccount(
                accountId = "app-id",
                name = "QQ 官方机器人 app-id",
                state = MessageSinkRouteState.UNAVAILABLE,
            ),
            gateway.availableAccounts().single(),
        )
    }

    @Test
    fun `收到 Hello 后发送 Identify 并安排心跳`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, heartbeatScheduler = heartbeatScheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")

        val identifyPayload = connector.session.sentTexts.single()
        val identifyRoot = Json.parseToJsonElement(identifyPayload).jsonObject
        val identifyData = identifyRoot.getValue("d").jsonObject

        assertEquals(QqBotOpcode.IDENTIFY.code, identifyRoot.getValue("op").jsonPrimitive.int)
        assertEquals("QQBot token-a", identifyData.getValue("token").jsonPrimitive.content)
        assertEquals(1 shl 25, identifyData.getValue("intents").jsonPrimitive.int)
        assertEquals(listOf(0, 1), identifyData.getValue("shard").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(45_000, heartbeatScheduler.scheduled.single().initialDelayMillis)
        assertEquals(45_000, heartbeatScheduler.scheduled.single().intervalMillis)
        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
    }

    @Test
    fun `连接过程中收到 Hello 也会补发 Identify`() = runTest {
        val connector = FakeQqBotWebSocketConnector(
            initialTexts = listOf("""{"op":10,"d":{"heartbeat_interval":45000}}"""),
        )
        val gateway = testGateway(connector = connector)

        gateway.connect { }

        val identifyRoot = Json.parseToJsonElement(connector.session.sentTexts.single()).jsonObject

        assertEquals(QqBotOpcode.IDENTIFY.code, identifyRoot.getValue("op").jsonPrimitive.int)
    }

    @Test
    fun `READY 事件记录会话并标记账号就绪`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)
    }

    @Test
    fun `心跳使用最新 Dispatch 序号`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, heartbeatScheduler = heartbeatScheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")
        connector.listener.onText("""{"op":0,"s":123,"t":"C2C_MESSAGE_CREATE","d":{"id":"msg-1"}}""")
        heartbeatScheduler.scheduled.single().task()

        val heartbeatPayload = connector.session.sentTexts.last()
        val heartbeatRoot = Json.parseToJsonElement(heartbeatPayload).jsonObject

        assertEquals(QqBotOpcode.HEARTBEAT.code, heartbeatRoot.getValue("op").jsonPrimitive.int)
        assertEquals(123, heartbeatRoot.getValue("d").jsonPrimitive.int)
    }

    @Test
    fun `收到网关心跳请求时立即回应最新序号`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")
        connector.listener.onText("""{"op":1,"d":null}""")

        val heartbeatRoot = Json.parseToJsonElement(connector.session.sentTexts.last()).jsonObject

        assertEquals(QqBotOpcode.HEARTBEAT.code, heartbeatRoot.getValue("op").jsonPrimitive.int)
        assertEquals(88, heartbeatRoot.getValue("d").jsonPrimitive.int)
    }

    @Test
    fun `Dispatch 负载会转发给入站处理器`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)
        val events = mutableListOf<QqBotIncomingEvent>()

        gateway.connect { events += it }
        connector.listener.onText("""{"op":0,"s":7,"t":"GROUP_AT_MESSAGE_CREATE","d":{"id":"msg-1"}}""")

        assertEquals(1, events.size)
        assertEquals("GROUP_AT_MESSAGE_CREATE", events.single().type)
    }

    @Test
    fun `关闭时取消心跳并关闭 WebSocket`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val gateway = testGateway(connector = connector, heartbeatScheduler = heartbeatScheduler)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        gateway.close()

        assertEquals(true, heartbeatScheduler.scheduled.single().closed)
        assertEquals(1000, connector.session.closedStatusCode)
        assertEquals("插件停止", connector.session.closedReason)
        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
    }

    @Test
    fun `远端关闭后标记账号不可用`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val gateway = testGateway(connector = connector)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")
        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)

        connector.listener.onClosed(4009, "expired")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
    }

    @Test
    fun `远端关闭后安排重连并打开新 WebSocket`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val transport = reconnectTransport()
        val gateway = testGateway(
            connector = connector,
            heartbeatScheduler = heartbeatScheduler,
            reconnectScheduler = reconnectScheduler,
            transport = transport,
        )

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")
        connector.listener.onClosed(4009, "expired")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
        assertEquals(true, heartbeatScheduler.scheduled.single().closed)
        assertEquals(1_000L, reconnectScheduler.scheduled.single().delayMillis)

        reconnectScheduler.scheduled.single().task()
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":30000}}""")
        val resumeRoot = Json.parseToJsonElement(connector.session.sentTexts.single()).jsonObject
        val resumeData = resumeRoot.getValue("d").jsonObject
        connector.listener.onText("""{"op":0,"s":124,"t":"RESUMED","d":{}}""")

        assertEquals(
            listOf("wss://api.bot.qq.com/websocket", "wss://api.bot.qq.com/websocket-reconnect"),
            connector.connectedUrls,
        )
        assertEquals(2, connector.sessions.size)
        assertEquals(MessageSinkRouteState.READY, gateway.availableAccounts().single().state)
        assertEquals(30_000, heartbeatScheduler.scheduled.last().intervalMillis)
        assertEquals(QqBotOpcode.RESUME.code, resumeRoot.getValue("op").jsonPrimitive.int)
        assertEquals("session-a", resumeData.getValue("session_id").jsonPrimitive.content)
        assertEquals(88, resumeData.getValue("seq").jsonPrimitive.int)
    }

    @Test
    fun `INVALID_SESSION 后下一次重连退回 Identify`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val transport = reconnectTransport()
        val gateway = testGateway(
            connector = connector,
            heartbeatScheduler = heartbeatScheduler,
            reconnectScheduler = reconnectScheduler,
            transport = transport,
        )

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")
        connector.listener.onText("""{"op":9,"d":false}""")

        reconnectScheduler.scheduled.single().task()
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":30000}}""")

        val identifyRoot = Json.parseToJsonElement(connector.session.sentTexts.single()).jsonObject

        assertEquals(QqBotOpcode.IDENTIFY.code, identifyRoot.getValue("op").jsonPrimitive.int)
    }

    @Test
    fun `RECONNECT 操作码会关闭当前 WebSocket 并安排重连`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val heartbeatScheduler = FakeQqBotHeartbeatScheduler()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val gateway = testGateway(
            connector = connector,
            heartbeatScheduler = heartbeatScheduler,
            reconnectScheduler = reconnectScheduler,
        )

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")
        connector.listener.onText("""{"op":7,"d":null}""")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
        assertEquals(true, heartbeatScheduler.scheduled.single().closed)
        assertEquals(4000, connector.session.closedStatusCode)
        assertEquals("准备重连", connector.session.closedReason)
        assertEquals(1_000L, reconnectScheduler.scheduled.single().delayMillis)
    }

    @Test
    fun `关闭时取消等待中的重连`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val gateway = testGateway(connector = connector, reconnectScheduler = reconnectScheduler)

        gateway.connect { }
        connector.listener.onClosed(4009, "expired")
        gateway.close()

        assertEquals(true, reconnectScheduler.scheduled.single().closed)
    }

    @Test
    fun `禁用重连时远端关闭不会重连`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val reconnectScheduler = FakeQqBotReconnectScheduler()
        val gateway = testGateway(
            connector = connector,
            reconnectScheduler = reconnectScheduler,
            reconnect = false,
        )

        gateway.connect { }
        connector.listener.onClosed(4009, "expired")

        assertEquals(MessageSinkRouteState.UNAVAILABLE, gateway.availableAccounts().single().state)
        assertEquals(0, reconnectScheduler.scheduled.size)
    }

    @Test
    fun `就绪网关通过 OpenAPI 发送单聊和群聊消息`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"id":"user-send-1"}"""),
                    QqBotHttpResponse(200, """{"id":"group-send-1"}"""),
                ),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}""")),
            ),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        val userReceipt = gateway.sendUserMessage(
            accountId = "app-id",
            openid = "user-openid",
            request = QqBotSendRequest(content = "hello"),
        )
        val groupReceipt = gateway.sendGroupMessage(
            accountId = "app-id",
            groupOpenid = "group-openid",
            request = QqBotSendRequest(content = "group hello"),
        )

        assertEquals("user-send-1", userReceipt.messageId)
        assertEquals("group-send-1", groupReceipt.messageId)
        assertEquals("https://api.bot.qq.com/v2/users/user-openid/messages", transport.posts[1].url)
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/messages", transport.posts[2].url)
    }

    @Test
    fun `就绪网关先登记富媒体再发送群聊媒体消息`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(200, """{"file_info":"file-info-1","ttl":3600}"""),
                    QqBotHttpResponse(200, """{"id":"group-media-send-1"}"""),
                ),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}""")),
            ),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        val receipt = gateway.sendGroupMediaMessage(
            accountId = "app-id",
            groupOpenid = "group-openid",
            media = QqBotMediaResourceSource(QqBotMediaFileType.IMAGE, url = "https://example.com/a.png"),
            request = QqBotSendRequest(content = "图片说明", msgType = QQBOT_MEDIA_MSG_TYPE),
        )

        assertEquals("group-media-send-1", receipt.messageId)
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/files", transport.posts[1].url)
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/messages", transport.posts[2].url)
        val sendBody = Json.parseToJsonElement(transport.posts[2].body).jsonObject
        assertEquals(7, sendBody.getValue("msg_type").jsonPrimitive.int)
        assertEquals("file-info-1", sendBody.getValue("media").jsonObject.getValue("file_info").jsonPrimitive.content)
    }

    @Test
    fun `就绪网关对本地文件走分片上传再发送媒体消息`() = runTest {
        val image = createTempMediaFile()
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}"""),
                    QqBotHttpResponse(
                        200,
                        """
                            {
                              "upload_id":"upload_1",
                              "block_size":"16",
                              "parts":[{"index":0,"presigned_url":"https://cos.example.com/p0","block_size":"16"}],
                              "upload_config":{"concurrency":1,"retry_timeout":300,"retry_delay":1}
                            }
                        """.trimIndent(),
                    ),
                    QqBotHttpResponse(200, ""),
                    QqBotHttpResponse(200, """{"file_info":"file-info-1","ttl":3600}"""),
                    QqBotHttpResponse(200, """{"id":"user-media-send-1"}"""),
                ),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}""")),
            ),
            putResponses = ArrayDeque(listOf(QqBotHttpResponse(200, ""))),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        val receipt = gateway.sendUserMediaMessage(
            accountId = "app-id",
            openid = "user-openid",
            media = QqBotMediaResourceSource(
                fileType = QqBotMediaFileType.IMAGE,
                localFile = image,
                fileName = "image.png",
            ),
            request = QqBotSendRequest(content = "图片说明", msgType = QQBOT_MEDIA_MSG_TYPE),
        )

        assertEquals("user-media-send-1", receipt.messageId)
        assertEquals("https://api.bot.qq.com/v2/users/user-openid/upload_prepare", transport.posts[1].url)
        assertEquals("https://api.bot.qq.com/v2/users/user-openid/upload_part_finish", transport.posts[2].url)
        assertEquals("https://api.bot.qq.com/v2/users/user-openid/files", transport.posts[3].url)
        assertEquals("https://cos.example.com/p0", transport.puts.single().url)
        assertEquals(16, transport.puts.single().bytes.size)
        val mergeBody = Json.parseToJsonElement(transport.posts[3].body).jsonObject
        assertEquals("upload_1", mergeBody.getValue("upload_id").jsonPrimitive.content)
        assertEquals("image.png", mergeBody.getValue("file_name").jsonPrimitive.content)
        val sendBody = Json.parseToJsonElement(transport.posts[4].body).jsonObject
        assertEquals("file-info-1", sendBody.getValue("media").jsonObject.getValue("file_info").jsonPrimitive.content)
    }

    @Test
    fun `媒体登记超时包装为媒体准备异常且不会调用消息接口`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}""")),
            ),
            postExceptionAtCallIndex = 1,
            postException = HttpTimeoutException("请求超时"),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        val error = assertFailsWith<QqBotMediaPreparationException> {
            gateway.sendGroupMediaMessage(
                accountId = "app-id",
                groupOpenid = "group-openid",
                media = QqBotMediaResourceSource(
                    fileType = QqBotMediaFileType.IMAGE,
                    url = "https://example.com/a.png",
                ),
                request = QqBotSendRequest(content = "图片", msgType = QQBOT_MEDIA_MSG_TYPE),
            )
        }

        assertIs<HttpTimeoutException>(error.cause)
        assertEquals("QQ 官方机器人富媒体资源准备失败：请求超时", error.message)
        assertEquals(2, transport.posts.size)
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/files", transport.posts[1].url)
    }

    @Test
    fun `就绪网关通过 OpenAPI 撤回单聊和群聊消息`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}""")),
            ),
            deleteResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(204, ""),
                    QqBotHttpResponse(204, ""),
                ),
            ),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        gateway.recallUserMessage(
            accountId = "app-id",
            openid = "user-openid",
            messageId = "user-send-1",
        )
        gateway.recallGroupMessage(
            accountId = "app-id",
            groupOpenid = "group-openid",
            messageId = "group-send-1",
        )

        assertEquals("https://api.bot.qq.com/v2/users/user-openid/messages/user-send-1", transport.deletes[0].url)
        assertEquals("https://api.bot.qq.com/v2/groups/group-openid/messages/group-send-1", transport.deletes[1].url)
    }

    @Test
    fun `就绪后拉取机器人资料并在账号上带出名称与头像`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}"""),
                    QqBotHttpResponse(
                        200,
                        """
                            {
                              "id":"2664901145947526480",
                              "username":"PuniBot-测试中",
                              "avatar":"https://thirdqq.qlogo.cn/g?b=oidb&k=abc&s=0",
                              "bot":true
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        val account = gateway.availableAccounts().single()

        assertEquals("PuniBot-测试中", account.name)
        assertEquals("https://thirdqq.qlogo.cn/g?b=oidb&k=abc&s=0", account.avatar?.uri)
        assertEquals(MessageSinkRouteState.READY, account.state)
        // 接入点 + 机器人资料各一次
        assertEquals(2, transport.gets.size)
        assertEquals("https://api.bot.qq.com/users/@me", transport.gets.last().url)

        // 资料已缓存：后续调用不再请求
        assertEquals("PuniBot-测试中", gateway.availableAccounts().single().name)
        assertEquals(2, transport.gets.size)
    }

    @Test
    fun `机器人资料拉取失败时回退默认展示名且进入冷却`() = runTest {
        val connector = FakeQqBotWebSocketConnector()
        val transport = FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}"""),
                    QqBotHttpResponse(500, """{"message":"boom"}"""),
                ),
            ),
        )
        val gateway = testGateway(connector = connector, transport = transport)

        gateway.connect { }
        connector.listener.onText("""{"op":10,"d":{"heartbeat_interval":45000}}""")
        connector.listener.onText("""{"op":0,"s":88,"t":"READY","d":{"session_id":"session-a"}}""")

        val account = gateway.availableAccounts().single()

        // 回退默认展示名，且连接状态不受资料拉取失败影响
        assertEquals("QQ 官方机器人 app-id", account.name)
        assertNull(account.avatar)
        assertEquals(MessageSinkRouteState.READY, account.state)

        // 冷却期内不再请求（响应队列已耗尽，若仍发起请求 gets 会多出一条）
        assertEquals("QQ 官方机器人 app-id", gateway.availableAccounts().single().name)
        assertEquals(2, transport.gets.size)
    }

    private fun testGateway(
        connector: FakeQqBotWebSocketConnector = FakeQqBotWebSocketConnector(),
        heartbeatScheduler: FakeQqBotHeartbeatScheduler = FakeQqBotHeartbeatScheduler(),
        reconnectScheduler: FakeQqBotReconnectScheduler = FakeQqBotReconnectScheduler(),
        reconnect: Boolean = true,
        transport: FakeQqBotHttpTransport = defaultTransport(),
    ): QqBotWebSocketGateway {
        val config = QqBotConfig(
            enabled = true,
            appId = "app-id",
            appSecret = "secret",
            reconnect = reconnect,
        )
        return QqBotWebSocketGateway(
            config = config,
            openApiClient = QqBotOpenApiClient(config = config, transport = transport),
            connector = connector,
            heartbeatScheduler = heartbeatScheduler,
            reconnectScheduler = reconnectScheduler,
        )
    }

    private fun createTempMediaFile(): Path {
        val dir = Files.createTempDirectory("qqbot-gateway-test")
        val file = dir.resolve("image.png")
        Files.write(file, ByteArray(16) { it.toByte() })
        file.toFile().deleteOnExit()
        dir.toFile().deleteOnExit()
        return file
    }

    private fun defaultTransport(): FakeQqBotHttpTransport {
        return FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}""")),
            ),
        )
    }

    private fun reconnectTransport(): FakeQqBotHttpTransport {
        return FakeQqBotHttpTransport(
            postResponses = ArrayDeque(
                listOf(QqBotHttpResponse(200, """{"access_token":"token-a","expires_in":7200}""")),
            ),
            getResponses = ArrayDeque(
                listOf(
                    QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket","shards":1}"""),
                    QqBotHttpResponse(200, """{"url":"wss://api.bot.qq.com/websocket-reconnect","shards":1}"""),
                ),
            ),
        )
    }

    private class FakeQqBotWebSocketConnector(
        private val initialTexts: List<String> = emptyList(),
    ) : QqBotWebSocketConnector {
        val sessions: MutableList<FakeQqBotWebSocketSession> = mutableListOf()
        val listeners: MutableList<QqBotWebSocketListener> = mutableListOf()
        val connectedUrls: MutableList<String> = mutableListOf()
        val session: FakeQqBotWebSocketSession
            get() = sessions.last()
        val listener: QqBotWebSocketListener
            get() = listeners.last()
        val connectedUrl: String?
            get() = connectedUrls.lastOrNull()

        override fun connect(url: String, listener: QqBotWebSocketListener): QqBotWebSocketSession {
            val session = FakeQqBotWebSocketSession()
            sessions += session
            listeners += listener
            connectedUrls += url
            listener.onOpen()
            initialTexts.forEach(listener::onText)
            return session
        }
    }

    private class FakeQqBotWebSocketSession : QqBotWebSocketSession {
        val sentTexts: MutableList<String> = mutableListOf()
        var closedStatusCode: Int? = null
        var closedReason: String? = null

        override fun sendText(payload: String) {
            sentTexts += payload
        }

        override fun close(statusCode: Int, reason: String) {
            closedStatusCode = statusCode
            closedReason = reason
        }
    }

    private data class ScheduledHeartbeat(
        val initialDelayMillis: Long,
        val intervalMillis: Long,
        val task: () -> Unit,
        var closed: Boolean = false,
    )

    private class FakeQqBotHeartbeatScheduler : QqBotHeartbeatScheduler {
        val scheduled: MutableList<ScheduledHeartbeat> = mutableListOf()

        override fun scheduleAtFixedRate(
            initialDelayMillis: Long,
            intervalMillis: Long,
            task: () -> Unit,
        ): AutoCloseable {
            val scheduledTask = ScheduledHeartbeat(
                initialDelayMillis = initialDelayMillis,
                intervalMillis = intervalMillis,
                task = task,
            )
            scheduled += scheduledTask
            return AutoCloseable { scheduledTask.closed = true }
        }
    }

    private data class ScheduledReconnect(
        val delayMillis: Long,
        val task: () -> Unit,
        var closed: Boolean = false,
    )

    private class FakeQqBotReconnectScheduler : QqBotReconnectScheduler {
        val scheduled: MutableList<ScheduledReconnect> = mutableListOf()

        override fun schedule(delayMillis: Long, task: () -> Unit): AutoCloseable {
            val scheduledTask = ScheduledReconnect(
                delayMillis = delayMillis,
                task = task,
            )
            scheduled += scheduledTask
            return AutoCloseable { scheduledTask.closed = true }
        }
    }

    private data class RecordedPost(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class RecordedGet(
        val url: String,
        val headers: Map<String, String>,
    )

    private data class RecordedDelete(
        val url: String,
        val headers: Map<String, String>,
    )

    private data class RecordedPut(
        val url: String,
        val bytes: ByteArray,
    )

    private class FakeQqBotHttpTransport(
        private val postResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
        private val getResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
        private val deleteResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
        private val putResponses: ArrayDeque<QqBotHttpResponse> = ArrayDeque(),
        private val postExceptionAtCallIndex: Int? = null,
        private val postException: Throwable? = null,
    ) : QqBotHttpTransport {
        val posts: MutableList<RecordedPost> = mutableListOf()
        val gets: MutableList<RecordedGet> = mutableListOf()
        val deletes: MutableList<RecordedDelete> = mutableListOf()
        val puts: MutableList<RecordedPut> = mutableListOf()
        private var postCallIndex: Int = 0

        override fun postJson(url: String, headers: Map<String, String>, body: String): QqBotHttpResponse {
            posts += RecordedPost(url = url, headers = headers, body = body)
            val callIndex = postCallIndex++
            if (callIndex == postExceptionAtCallIndex) {
                throw requireNotNull(postException) { "postException must be set when an exception index is configured" }
            }
            return postResponses.removeFirst()
        }

        override fun get(url: String, headers: Map<String, String>): QqBotHttpResponse {
            gets += RecordedGet(url = url, headers = headers)
            return getResponses.removeFirst()
        }

        override fun delete(url: String, headers: Map<String, String>): QqBotHttpResponse {
            deletes += RecordedDelete(url = url, headers = headers)
            return deleteResponses.removeFirst()
        }

        override fun putBytes(url: String, bytes: ByteArray): QqBotHttpResponse {
            puts += RecordedPut(url = url, bytes = bytes)
            return putResponses.removeFirst()
        }
    }
}
