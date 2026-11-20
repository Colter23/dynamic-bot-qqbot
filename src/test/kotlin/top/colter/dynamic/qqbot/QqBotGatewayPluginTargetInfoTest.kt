package top.colter.dynamic.qqbot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkRouteState

/**
 * 群资料补全的端到端行为：从入站事件到账本落名。
 *
 * 用 [Dispatchers.Unconfined] 起插件作用域，让 `scope.launch` 内的补全流程可预期地推进，
 * 再用 [BlockingTargetInfoGateway] 构造"请求在途"的时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QqBotGatewayPluginTargetInfoTest {
    @Test
    fun `限频排队期间资料补齐后不再查询群和用户接口`() = runTest {
        for (kind in listOf(TargetKind.GROUP, TargetKind.USER)) {
            val gateway = BlockingTargetInfoGateway(info = null)
            val ledger = QqBotTargetLedger(FakePluginDataStore())
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val plugin = QqBotGatewayPlugin()
            plugin.prepareTargetInfoTest(scope, gateway, ledger)
            try {
                plugin.onIncomingEvent(messageEvent(kind, "first"))
                runCurrent()
                assertTrue(gateway.started.isCompleted)
                plugin.onIncomingEvent(messageEvent(kind, "queued"))
                runCurrent()
                assertFalse(ledger.needsInfo(kind, "queued"))

                if (kind == TargetKind.USER) {
                    plugin.onIncomingEvent(userMessageEvent(withProfile = true, openid = "queued"))
                } else {
                    ledger.record(kind, "queued", name = "群名称")
                }
                gateway.release()
                advanceUntilIdle()

                val requested = if (kind == TargetKind.USER) gateway.requestedUsers else gateway.requestedGroups
                assertEquals(listOf("first"), requested)
                assertEquals(false, ledger.needsInfo(kind, "queued"))
            } finally {
                plugin.callPrivate("stopIncomingScope")
                scope.cancel()
            }
        }
    }

    @Test
    fun `限频排队期间删除目标后不再查询且重新发现时没有冷却`() = runTest {
        for (kind in listOf(TargetKind.GROUP, TargetKind.USER)) {
            val gateway = BlockingTargetInfoGateway(info = null)
            val ledger = QqBotTargetLedger(FakePluginDataStore())
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val plugin = QqBotGatewayPlugin()
            plugin.prepareTargetInfoTest(scope, gateway, ledger)
            try {
                plugin.onIncomingEvent(messageEvent(kind, "first"))
                runCurrent()
                plugin.onIncomingEvent(messageEvent(kind, "queued"))
                runCurrent()
                assertFalse(ledger.needsInfo(kind, "queued"))

                ledger.markRemoved(kind, "queued")
                gateway.release()
                advanceUntilIdle()

                val requested = if (kind == TargetKind.USER) gateway.requestedUsers else gateway.requestedGroups
                assertEquals(listOf("first"), requested)
                assertEquals(listOf("first"), ledger.listCandidates(kind).map { it.address.externalId })
                ledger.record(kind, "queued")
                assertNotNull(ledger.tryBeginInfoRequest(kind, "queued"))
            } finally {
                plugin.callPrivate("stopIncomingScope")
                scope.cancel()
            }
        }
    }

    @Test
    fun `插件停止会释放限频排队中的资料请求占位`() = runTest {
        val gateway = BlockingTargetInfoGateway(info = null)
        val ledger = QqBotTargetLedger(FakePluginDataStore())
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val plugin = QqBotGatewayPlugin()
        plugin.prepareTargetInfoTest(scope, gateway, ledger)
        try {
            plugin.onIncomingEvent(userMessageEvent(openid = "first"))
            runCurrent()
            plugin.onIncomingEvent(userMessageEvent(openid = "queued"))
            runCurrent()
            assertFalse(ledger.needsInfo(TargetKind.USER, "queued"))

            plugin.callPrivate("stopIncomingScope")
            runCurrent()

            assertEquals(listOf("first"), gateway.requestedUsers)
            assertTrue(ledger.needsInfo(TargetKind.USER, "first"))
            assertTrue(ledger.needsInfo(TargetKind.USER, "queued"))
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `单聊事件自带完整资料时直接入账且不查询用户接口`() = runBlocking {
        val gateway = BlockingTargetInfoGateway(info = null)
        val ledger = QqBotTargetLedger(FakePluginDataStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val plugin = QqBotGatewayPlugin()
        plugin.prepareTargetInfoTest(scope, gateway, ledger)
        try {
            plugin.onIncomingEvent(userMessageEvent(withProfile = true))
            plugin.onIncomingEvent(userMessageEvent(withProfile = true))

            val candidate = ledger.listCandidates(TargetKind.USER).single()
            assertEquals("好友昵称", candidate.name)
            assertEquals("https://thirdqq.qlogo.cn/avatar", candidate.avatar?.uri)
            assertEquals(emptyList(), gateway.requestedUsers)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `单聊并发事件只查询一次且接口结果不覆盖新的事件资料`() = runBlocking {
        val gateway = BlockingTargetInfoGateway(info = null, user = QqBotUserInfo("旧昵称", "https://example.com/old.png"))
        val ledger = QqBotTargetLedger(FakePluginDataStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val plugin = QqBotGatewayPlugin()
        plugin.prepareTargetInfoTest(scope, gateway, ledger)
        try {
            plugin.onIncomingEvent(userMessageEvent())
            withTimeout(REQUEST_TIMEOUT_MILLIS) { gateway.started.await() }
            plugin.onIncomingEvent(userMessageEvent())
            assertEquals(listOf("user-openid"), gateway.requestedUsers)
            plugin.onIncomingEvent(userMessageEvent(withProfile = true))
            gateway.release()

            val candidate = ledger.listCandidates(TargetKind.USER).single()
            assertEquals("好友昵称", candidate.name)
            assertEquals(MediaRef("https://thirdqq.qlogo.cn/avatar", MediaKind.AVATAR), candidate.avatar)
            assertEquals(false, ledger.needsInfo(TargetKind.USER, "user-openid"))
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `用户接口只返回昵称时保留昵称并冷却后再补头像`() = runBlocking {
        val gateway = BlockingTargetInfoGateway(info = null, user = QqBotUserInfo("好友昵称"))
        val ledger = QqBotTargetLedger(FakePluginDataStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val plugin = QqBotGatewayPlugin()
        plugin.prepareTargetInfoTest(scope, gateway, ledger)
        try {
            plugin.onIncomingEvent(userMessageEvent())
            withTimeout(REQUEST_TIMEOUT_MILLIS) { gateway.started.await() }
            gateway.release()
            awaitUntil { ledger.listCandidates(TargetKind.USER).single().name == "好友昵称" }
            plugin.onIncomingEvent(userMessageEvent())

            assertEquals(listOf("user-openid"), gateway.requestedUsers)
            assertEquals(false, ledger.needsInfo(TargetKind.USER, "user-openid"))
            assertEquals(true, ledger.needsInfo(TargetKind.USER, "user-openid", nowEpochSeconds = FAR_FUTURE))
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `同一群的并发入站事件只发起一次资料请求`() = runBlocking {
        val gateway = BlockingTargetInfoGateway(QqBotGroupInfo(GROUP_OPENID, "测试群", 5))
        val ledger = QqBotTargetLedger(dataStore = FakePluginDataStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val plugin = QqBotGatewayPlugin()
        plugin.prepareTargetInfoTest(scope = scope, gateway = gateway, ledger = ledger)

        try {
            plugin.invokeIncoming(groupMessageEvent())
            withTimeout(REQUEST_TIMEOUT_MILLIS) { gateway.started.await() }

            // 首次请求尚未返回时，同一群再来消息不得再次发起请求
            plugin.invokeIncoming(groupMessageEvent())
            plugin.invokeIncoming(groupMessageEvent())
            assertEquals(listOf(GROUP_OPENID), gateway.requestedGroups)

            gateway.release()
            awaitUntil { ledger.listCandidates(TargetKind.GROUP).singleOrNull()?.name == "测试群" }

            assertEquals("测试群", ledger.listCandidates(TargetKind.GROUP).single().name)
            assertEquals(listOf(GROUP_OPENID), gateway.requestedGroups)
            // 已拿到名称后不再请求
            assertEquals(false, ledger.needsInfo(TargetKind.GROUP, GROUP_OPENID))
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `群信息未返回名称时按取不到处理而不是补全成功`() = runBlocking {
        val gateway = BlockingTargetInfoGateway(QqBotGroupInfo(GROUP_OPENID, "   ", 5))
        val ledger = QqBotTargetLedger(dataStore = FakePluginDataStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val plugin = QqBotGatewayPlugin()
        plugin.prepareTargetInfoTest(scope = scope, gateway = gateway, ledger = ledger)

        try {
            plugin.invokeIncoming(groupMessageEvent())
            withTimeout(REQUEST_TIMEOUT_MILLIS) { gateway.started.await() }
            gateway.release()

            // 展示名回退 openid（空名称不能当成"已补全"）
            assertEquals(GROUP_OPENID, ledger.listCandidates(TargetKind.GROUP).single().name)

            // 关键断言：登记的是"有限的冷却"而不是"本次运行内不再重试"。
            // 旧实现把空名称当成功，走 recordInfo(name = "") 落成 Long.MAX_VALUE，
            // 于是 farFuture 也仍是 false。
            awaitUntil { ledger.needsInfo(TargetKind.GROUP, GROUP_OPENID, nowEpochSeconds = FAR_FUTURE) }
            // 冷却未到之前不重复请求
            assertEquals(false, ledger.needsInfo(TargetKind.GROUP, GROUP_OPENID))
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `网关不支持资料查询时不会重复请求`() = runBlocking {
        val gateway = BlockingTargetInfoGateway(info = null)
        val ledger = QqBotTargetLedger(dataStore = FakePluginDataStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val plugin = QqBotGatewayPlugin()
        plugin.prepareTargetInfoTest(scope = scope, gateway = gateway, ledger = ledger)

        try {
            plugin.invokeIncoming(groupMessageEvent())
            withTimeout(REQUEST_TIMEOUT_MILLIS) { gateway.started.await() }
            gateway.release()
            awaitUntil { ledger.needsInfo(TargetKind.GROUP, GROUP_OPENID, nowEpochSeconds = FAR_FUTURE) }

            plugin.invokeIncoming(groupMessageEvent())

            assertEquals(listOf(GROUP_OPENID), gateway.requestedGroups)
            assertEquals(GROUP_OPENID, ledger.listCandidates(TargetKind.GROUP).single().name)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    private fun QqBotGatewayPlugin.prepareTargetInfoTest(
        scope: CoroutineScope,
        gateway: QqBotGateway,
        ledger: QqBotTargetLedger,
    ) {
        setPrivate("pluginId", QQBOT_PLUGIN_ID)
        setPrivate("config", QqBotConfig(appId = ACCOUNT_ID, autoPublishIncoming = false))
        setPrivate("pluginScope", scope)
        setPrivate("gateway", gateway)
        setPrivate("targetLedger", ledger)
        setPrivate("running", true)
        callPrivate("startIncomingScope")
    }

    private fun groupMessageEvent(openid: String = GROUP_OPENID): QqBotIncomingEvent {
        return QqBotIncomingEvent(
            type = QQBOT_GROUP_MESSAGE_CREATE,
            botAccountId = ACCOUNT_ID,
            rawPayload = """
                {
                  "op":0,
                  "s":3,
                  "t":"GROUP_MESSAGE_CREATE",
                  "d":{
                    "id":"group-msg-1",
                    "group_openid":"$openid",
                    "author":{"member_openid":"member-openid","member_nickname":"群成员昵称"},
                    "content":"测试",
                    "message_type":0
                  }
                }
            """.trimIndent(),
        )
    }

    private fun userMessageEvent(withProfile: Boolean = false, openid: String = "user-openid"): QqBotIncomingEvent {
        val profile = if (withProfile) """, "username":"好友昵称", "avatar":"http://thirdqq.qlogo.cn/avatar"""" else ""
        return QqBotIncomingEvent(
            type = QQBOT_C2C_MESSAGE_CREATE,
            botAccountId = ACCOUNT_ID,
            rawPayload = """{"op":0,"s":1,"t":"C2C_MESSAGE_CREATE","d":{"id":"user-msg","author":{"user_openid":"$openid"$profile},"content":"测试"}}""",
        )
    }

    private fun messageEvent(kind: TargetKind, openid: String): QqBotIncomingEvent = when (kind) {
        TargetKind.USER -> userMessageEvent(openid = openid)
        TargetKind.GROUP -> groupMessageEvent(openid)
        else -> error("不支持的测试目标类型：$kind")
    }

    private fun QqBotGatewayPlugin.invokeIncoming(event: QqBotIncomingEvent) {
        onIncomingEvent(event)
    }

    private suspend fun awaitUntil(timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        error("等待条件超时")
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

    /**
     * 群资料查询可控的假网关：[groupInfo] 会登记调用并挂起，直到 [release] 被调用，
     * 便于构造"请求在途"的时序。
     */
    private class BlockingTargetInfoGateway(
        private val info: QqBotGroupInfo?,
        private val user: QqBotUserInfo? = null,
    ) : QqBotGateway {
        val requestedGroups: MutableList<String> = mutableListOf()
        val requestedUsers: MutableList<String> = mutableListOf()
        val started: CompletableDeferred<Unit> = CompletableDeferred()
        private val released: CompletableDeferred<Unit> = CompletableDeferred()

        fun release() {
            released.complete(Unit)
        }

        override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) = Unit

        override suspend fun availableAccounts(): List<QqBotRuntimeAccount> =
            listOf(QqBotRuntimeAccount(accountId = ACCOUNT_ID, state = MessageSinkRouteState.READY))

        override suspend fun groupInfo(accountId: String, groupOpenid: String): QqBotGroupInfo? {
            requestedGroups += groupOpenid
            started.complete(Unit)
            released.await()
            return info
        }

        override suspend fun userInfo(accountId: String, userOpenid: String): QqBotUserInfo? {
            requestedUsers += userOpenid
            started.complete(Unit)
            released.await()
            return user
        }

        override suspend fun sendUserMessage(
            accountId: String,
            openid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = notUsed()

        override suspend fun sendGroupMessage(
            accountId: String,
            groupOpenid: String,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = notUsed()

        override suspend fun sendUserMediaMessage(
            accountId: String,
            openid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = notUsed()

        override suspend fun sendGroupMediaMessage(
            accountId: String,
            groupOpenid: String,
            media: QqBotMediaResourceSource,
            request: QqBotSendRequest,
        ): QqBotSendReceipt = notUsed()

        override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
            notUsed<Unit>()
        }

        override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
            notUsed<Unit>()
        }

        override fun close() = Unit

        private fun <T> notUsed(): T = error("本测试不应调用该网关的发送/撤回能力")
    }

    private companion object {
        private const val ACCOUNT_ID: String = "app-id"
        private const val GROUP_OPENID: String = "group-openid"
        private const val REQUEST_TIMEOUT_MILLIS: Long = 1_000L

        /** 远到超过任何合理冷却、但远小于 `Long.MAX_VALUE`，用于区分"有限冷却"与"不再重试"。 */
        private val FAR_FUTURE: Long = System.currentTimeMillis() / 1000 + 10L * 24 * 60 * 60
    }
}
