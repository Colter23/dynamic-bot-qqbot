package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class QqBotTargetLedgerTest {
    @Test
    fun `记录群与单聊目标并按类型分开存放`() {
        val ledger = ledger()

        ledger.record(TargetKind.GROUP, "group-1")
        ledger.record(TargetKind.USER, "user-1")

        assertEquals(listOf("group-1"), ledger.listCandidates(TargetKind.GROUP).map { it.address.externalId })
        assertEquals(listOf("user-1"), ledger.listCandidates(TargetKind.USER).map { it.address.externalId })
        assertEquals(2, ledger.listCandidates(null).size)
        assertEquals(2, ledger.size())
        // 群与好友分开存储，后台按类型取数时无需再过滤
        assertEquals(emptyList(), ledger.listCandidates(TargetKind.CHANNEL))
        // 平台 ID 与 OneBot 插件的 qq 区分，避免路由候选池混用
        assertEquals("qq-official", ledger.listCandidates(TargetKind.GROUP).single().address.platformId.value)
    }

    @Test
    fun `重复 sighting 不改变状态也不落盘`() {
        val store = FakePluginDataStore()
        val ledger = QqBotTargetLedger(dataStore = store)

        assertEquals(true, ledger.record(TargetKind.GROUP, "group-1"))
        val writesAfterFirst = store.saveCount

        // 同一条消息会反复触发 sighting：已存在的目标应完全无副作用
        assertEquals(false, ledger.record(TargetKind.GROUP, "group-1"))
        assertEquals(false, ledger.record(TargetKind.GROUP, "group-1"))

        assertEquals(writesAfterFirst, store.saveCount)
        assertEquals(1, ledger.size())
    }

    @Test
    fun `移出群与删除好友后目标被移除`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        ledger.record(TargetKind.USER, "user-1")

        assertEquals(true, ledger.markRemoved(TargetKind.GROUP, "group-1"))
        assertEquals(true, ledger.markRemoved(TargetKind.USER, "user-1"))
        // 未记录过的目标无需移除
        assertEquals(false, ledger.markRemoved(TargetKind.GROUP, "missing"))

        assertEquals(0, ledger.listCandidates(TargetKind.GROUP).size)
        assertEquals(0, ledger.listCandidates(TargetKind.USER).size)
        assertEquals(0, ledger.size())
    }

    @Test
    fun `移出后重新加入会重新出现`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        ledger.markRemoved(TargetKind.GROUP, "group-1")

        assertEquals(true, ledger.record(TargetKind.GROUP, "group-1"))
        assertEquals(listOf("group-1"), ledger.listCandidates(TargetKind.GROUP).map { it.address.externalId })
    }

    @Test
    fun `按地址查找目标并透传账号`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        ledger.record(TargetKind.USER, "user-1")

        val found = ledger.findCandidate(
            TargetAddress.of("qq-official", TargetKind.GROUP, "group-1", accountId = "app-id"),
        )
        val missing = ledger.findCandidate(TargetAddress.of("qq-official", TargetKind.GROUP, "missing"))
        val wrongPlatform = ledger.findCandidate(TargetAddress.of("other", TargetKind.GROUP, "group-1"))
        val unsupportedKind = ledger.findCandidate(TargetAddress.of("qq-official", TargetKind.CHANNEL, "channel-1"))

        assertEquals("group-1", found?.address?.externalId)
        assertEquals("app-id", found?.address?.accountId)
        assertNull(missing)
        assertNull(wrongPlatform)
        assertNull(unsupportedKind)
    }

    @Test
    fun `状态以对象形式持久化并在重载后保留`() {
        val store = FakePluginDataStore()
        val first = QqBotTargetLedger(dataStore = store)
        first.record(TargetKind.GROUP, "group-1")
        first.record(TargetKind.USER, "user-1")
        first.markRemoved(TargetKind.USER, "user-1")

        val reloaded = QqBotTargetLedger(dataStore = store)

        assertEquals(listOf("group-1"), reloaded.listCandidates(TargetKind.GROUP).map { it.address.externalId })
        assertEquals(0, reloaded.listCandidates(TargetKind.USER).size)
        assertEquals(1, reloaded.size())
        // 存的是对象而不是序列化字符串：YAML 根节点必须是映射
        assertTrue(store.savedValue(STORE_NAME) is QqBotTargetLedgerState)
    }

    @Test
    fun `补全资料后展示名使用群名称`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        assertEquals("group-1", ledger.listCandidates(TargetKind.GROUP).single().name)

        assertEquals(true, ledger.recordInfo(TargetKind.GROUP, "group-1", "测试群"))

        assertEquals("测试群", ledger.listCandidates(TargetKind.GROUP).single().name)
        assertEquals("测试群", ledger.findCandidate(TargetAddress.of("qq-official", TargetKind.GROUP, "group-1"))?.name)
        // 重复写入同一名称不落盘
        assertEquals(false, ledger.recordInfo(TargetKind.GROUP, "group-1", "测试群"))
        // 后续 sighting 不能把已补全的名称清空
        ledger.record(TargetKind.GROUP, "group-1")
        assertEquals("测试群", ledger.listCandidates(TargetKind.GROUP).single().name)
    }

    @Test
    fun `只有群需要补全资料且失败后进入内存冷却`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        ledger.record(TargetKind.USER, "user-1")

        // 群缺名需要补全；用户目标官方无资料接口；未知目标不请求
        assertTrue(ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 100L))
        assertEquals(false, ledger.needsInfo(TargetKind.USER, "user-1", nowEpochSeconds = 100L))
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "missing", nowEpochSeconds = 100L))

        // 拉取失败后要等到重试时间才允许再请求
        assertEquals(false, ledger.recordInfo(TargetKind.GROUP, "group-1", name = null, retryAfterEpochSeconds = 500L))
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 300L))
        assertEquals(true, ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 500L))

        // 拿到名称后不再请求
        ledger.recordInfo(TargetKind.GROUP, "group-1", "测试群")
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 999_999_999L))
    }

    @Test
    fun `资料冷却只存内存重启后可重试`() {
        val store = FakePluginDataStore()
        QqBotTargetLedger(dataStore = store).apply {
            record(TargetKind.GROUP, "group-1")
            recordInfo(TargetKind.GROUP, "group-1", name = null, retryAfterEpochSeconds = Long.MAX_VALUE)
        }

        // 重启后冷却不保留，允许再试一次（代价只有一次请求）
        val reloaded = QqBotTargetLedger(dataStore = store)
        assertTrue(reloaded.needsInfo(TargetKind.GROUP, "group-1"))
    }

    @Test
    fun `补全的资料随账本持久化`() {
        val store = FakePluginDataStore()
        QqBotTargetLedger(dataStore = store).apply {
            record(TargetKind.GROUP, "group-1")
            recordInfo(TargetKind.GROUP, "group-1", "测试群")
        }

        val reloaded = QqBotTargetLedger(dataStore = store)

        assertEquals("测试群", reloaded.listCandidates(TargetKind.GROUP).single().name)
        assertEquals(false, reloaded.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 999_999_999L))
    }

    @Test
    fun `同一群的资料请求在途时不会重复抢占`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        ledger.record(TargetKind.USER, "user-1")

        // 首次抢占成功；在途期间后续消息不得再次发起请求
        assertEquals(true, ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        assertEquals(false, ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "group-1"))
        // 用户目标没有资料接口，不占用名额
        assertEquals(false, ledger.tryBeginInfoRequest(TargetKind.USER, "user-1"))
    }

    @Test
    fun `资料请求结束后释放名额`() {
        // 成功路径：写入名称并释放名额，之后因已有名称不再请求
        val success = ledger()
        success.record(TargetKind.GROUP, "group-1")
        assertEquals(true, success.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        assertEquals(true, success.recordInfo(TargetKind.GROUP, "group-1", "测试群"))
        assertEquals(false, success.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))

        // 失败路径：释放名额，但冷却期内仍不允许再次请求
        val failure = ledger()
        failure.record(TargetKind.GROUP, "group-2")
        assertEquals(true, failure.tryBeginInfoRequest(TargetKind.GROUP, "group-2"))
        failure.recordInfo(TargetKind.GROUP, "group-2", name = null, retryAfterEpochSeconds = 500L)
        assertEquals(false, failure.tryBeginInfoRequest(TargetKind.GROUP, "group-2", nowEpochSeconds = 300L))
        assertEquals(true, failure.tryBeginInfoRequest(TargetKind.GROUP, "group-2", nowEpochSeconds = 500L))
    }

    @Test
    fun `被取消的资料请求会释放名额`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")

        assertEquals(true, ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        ledger.abandonInfoRequest(TargetKind.GROUP, "group-1")

        assertEquals(true, ledger.needsInfo(TargetKind.GROUP, "group-1"))
        assertEquals(true, ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
    }

    @Test
    fun `目标被移除时同时清理在途名额`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1")

        ledger.markRemoved(TargetKind.GROUP, "group-1")
        ledger.record(TargetKind.GROUP, "group-1")

        // 重新加入后应能再次抢占（旧的在途名额已随移除清理）
        assertEquals(true, ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
    }

    @Test
    fun `入站事件提取目标 sighting`() {
        val groupMessage = QqBotIncomingEvent(
            type = QQBOT_GROUP_MESSAGE_CREATE,
            rawPayload = """
                {"op":0,"s":1,"t":"GROUP_MESSAGE_CREATE","d":{
                  "id":"msg-1","group_openid":"group-1","timestamp":"2026-07-01T00:00:00Z",
                  "author":{"member_openid":"member-1"},"content":"hi"}}
            """.trimIndent(),
        )
        val addRobot = QqBotIncomingEvent(
            type = QQBOT_GROUP_ADD_ROBOT,
            rawPayload = """{"op":0,"s":2,"t":"GROUP_ADD_ROBOT","d":{"group_openid":"group-2"}}""",
        )
        val friendAdd = QqBotIncomingEvent(
            type = QQBOT_FRIEND_ADD,
            rawPayload = """{"op":0,"s":3,"t":"FRIEND_ADD","d":{"openid":"user-2"}}""",
        )
        val unknown = QqBotIncomingEvent(
            type = "DIRECT_MESSAGE_CREATE",
            rawPayload = """{"op":0,"s":4,"t":"DIRECT_MESSAGE_CREATE","d":{}}""",
        )

        val groupSighting = QqBotIncomingMessageMapper.toTargetSighting(groupMessage)
        val addSighting = QqBotIncomingMessageMapper.toTargetSighting(addRobot)
        val friendSighting = QqBotIncomingMessageMapper.toTargetSighting(friendAdd)

        assertEquals(TargetKind.GROUP, groupSighting?.kind)
        assertEquals("group-1", groupSighting?.externalId)
        assertEquals(TargetKind.GROUP, addSighting?.kind)
        assertEquals("group-2", addSighting?.externalId)
        assertEquals(TargetKind.USER, friendSighting?.kind)
        assertEquals("user-2", friendSighting?.externalId)
        assertNull(QqBotIncomingMessageMapper.toTargetSighting(unknown))
    }

    private fun ledger(): QqBotTargetLedger = QqBotTargetLedger(dataStore = FakePluginDataStore())

    private companion object {
        private const val STORE_NAME: String = "message-target-ledger"
    }
}
