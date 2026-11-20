package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef

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

        val request = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        assertTrue(ledger.recordInfo(request, "测试群"))

        assertEquals("测试群", ledger.listCandidates(TargetKind.GROUP).single().name)
        assertEquals("测试群", ledger.findCandidate(TargetAddress.of("qq-official", TargetKind.GROUP, "group-1"))?.name)
        // 重复写入同一名称不落盘
        assertFalse(ledger.recordInfo(request, "测试群"))
        // 后续 sighting 不能把已补全的名称清空
        ledger.record(TargetKind.GROUP, "group-1")
        assertEquals("测试群", ledger.listCandidates(TargetKind.GROUP).single().name)
    }

    @Test
    fun `群和用户缺少资料时需要补全且失败后进入内存冷却`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")
        ledger.record(TargetKind.USER, "user-1")

        // 群和用户缺少资料时补全；未知目标不请求
        assertTrue(ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 100L))
        assertTrue(ledger.needsInfo(TargetKind.USER, "user-1", nowEpochSeconds = 100L))
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "missing", nowEpochSeconds = 100L))

        // 拉取失败后要等到重试时间才允许再请求
        val firstRequest = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1", nowEpochSeconds = 100L))
        assertFalse(ledger.recordInfo(firstRequest, name = null, retryAfterEpochSeconds = 500L))
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 300L))
        assertEquals(true, ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 500L))

        // 拿到名称后不再请求
        val retry = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1", nowEpochSeconds = 500L))
        ledger.recordInfo(retry, "测试群")
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "group-1", nowEpochSeconds = 999_999_999L))
    }

    @Test
    fun `资料冷却只存内存重启后可重试`() {
        val store = FakePluginDataStore()
        QqBotTargetLedger(dataStore = store).apply {
            record(TargetKind.GROUP, "group-1")
            val request = assertNotNull(tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
            recordInfo(request, name = null, retryAfterEpochSeconds = Long.MAX_VALUE)
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
            val request = assertNotNull(tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
            recordInfo(request, "测试群")
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
        assertNotNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        assertNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "group-1"))
        assertNotNull(ledger.tryBeginInfoRequest(TargetKind.USER, "user-1"))
        assertNull(ledger.tryBeginInfoRequest(TargetKind.USER, "user-1"))
    }

    @Test
    fun `资料请求结束后释放名额`() {
        // 成功路径：写入名称并释放名额，之后因已有名称不再请求
        val success = ledger()
        success.record(TargetKind.GROUP, "group-1")
        val successRequest = assertNotNull(success.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        assertTrue(success.recordInfo(successRequest, "测试群"))
        assertNull(success.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))

        // 失败路径：释放名额，但冷却期内仍不允许再次请求
        val failure = ledger()
        failure.record(TargetKind.GROUP, "group-2")
        val failureRequest = assertNotNull(failure.tryBeginInfoRequest(TargetKind.GROUP, "group-2"))
        failure.recordInfo(failureRequest, name = null, retryAfterEpochSeconds = 500L)
        assertNull(failure.tryBeginInfoRequest(TargetKind.GROUP, "group-2", nowEpochSeconds = 300L))
        assertNotNull(failure.tryBeginInfoRequest(TargetKind.GROUP, "group-2", nowEpochSeconds = 500L))
    }

    @Test
    fun `被取消的资料请求会释放名额`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1")

        val request = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
        ledger.abandonInfoRequest(request)

        assertEquals(true, ledger.needsInfo(TargetKind.GROUP, "group-1"))
        assertNotNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "group-1"))
    }

    @Test
    fun `删除后重新添加目标时旧请求不能更新资料或清除新占位`() {
        for (kind in listOf(TargetKind.GROUP, TargetKind.USER)) {
            val ledger = ledger()
            val targetId = "target-1"
            val avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR)
            ledger.record(kind, targetId)
            val oldRequest = assertNotNull(ledger.tryBeginInfoRequest(kind, targetId))

            assertTrue(ledger.markRemoved(kind, targetId))
            ledger.record(kind, targetId)
            val newRequest = assertNotNull(ledger.tryBeginInfoRequest(kind, targetId))

            assertFalse(ledger.shouldContinueInfoRequest(oldRequest))
            assertFalse(ledger.recordInfo(oldRequest, name = null, retryAfterEpochSeconds = Long.MAX_VALUE))
            assertTrue(ledger.shouldContinueInfoRequest(newRequest))
            assertFalse(ledger.recordInfo(oldRequest, "旧名称", avatar = avatar, unionOpenId = "old-union"))
            ledger.abandonInfoRequest(oldRequest)
            assertTrue(ledger.shouldContinueInfoRequest(newRequest))
            val candidate = ledger.listCandidates(kind).single()
            assertEquals(targetId, candidate.name)
            assertNull(candidate.avatar)

            assertTrue(ledger.recordInfo(newRequest, "新名称", avatar = avatar))
            assertEquals("新名称", ledger.listCandidates(kind).single().name)
            assertFalse(ledger.needsInfo(kind, targetId))
        }
    }

    @Test
    fun `用户资料和群统一标识持久化且删除时清理`() {
        val store = FakePluginDataStore()
        val ledger = QqBotTargetLedger(store)
        val avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR)
        ledger.record(TargetKind.USER, "user-1", name = "好友昵称", avatar = avatar)
        ledger.record(TargetKind.GROUP, "group-1", unionOpenId = "union-group")

        val reloaded = QqBotTargetLedger(store)
        val candidate = reloaded.listCandidates(TargetKind.USER).single()
        assertEquals("好友昵称", candidate.name)
        assertEquals(avatar, candidate.avatar)
        assertEquals(avatar, reloaded.findCandidate(candidate.address)?.avatar)
        assertEquals(false, reloaded.needsInfo(TargetKind.USER, "user-1"))
        val saved = store.savedValue(STORE_NAME) as QqBotTargetLedgerState
        assertEquals("union-group", saved.groupUnionOpenIds["group-1"])

        reloaded.markRemoved(TargetKind.USER, "user-1")
        reloaded.markRemoved(TargetKind.GROUP, "group-1")
        val removed = store.savedValue(STORE_NAME) as QqBotTargetLedgerState
        assertTrue(removed.userAvatars.isEmpty())
        assertTrue(removed.groupUnionOpenIds.isEmpty())
    }

    @Test
    fun `接口资料不会覆盖请求期间收到的新资料且缺头像时有冷却`() {
        val ledger = ledger()
        ledger.record(TargetKind.USER, "user-1")
        val request = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.USER, "user-1"))
        ledger.record(TargetKind.USER, "user-1", name = "新昵称")
        ledger.recordInfo(request, "旧昵称", retryAfterEpochSeconds = 500L)
        assertEquals("新昵称", ledger.listCandidates(TargetKind.USER).single().name)
        assertEquals(false, ledger.needsInfo(TargetKind.USER, "user-1", nowEpochSeconds = 300L))
        assertTrue(ledger.needsInfo(TargetKind.USER, "user-1", nowEpochSeconds = 500L))

        val avatar = MediaRef("https://example.com/new.png", MediaKind.AVATAR)
        val retry = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.USER, "user-1", nowEpochSeconds = 500L))
        ledger.record(TargetKind.USER, "user-1", name = "最新昵称", avatar = avatar)
        ledger.recordInfo(retry, "旧昵称", avatar = MediaRef("https://example.com/old.png"))
        val candidate = ledger.listCandidates(TargetKind.USER).single()
        assertEquals("最新昵称", candidate.name)
        assertEquals(avatar, candidate.avatar)
        assertEquals(false, ledger.needsInfo(TargetKind.USER, "user-1"))
    }

    @Test
    fun `相同 OpenID 的用户和群不共享在途请求和冷却`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "same-id")
        ledger.record(TargetKind.USER, "same-id")
        val groupRequest = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.GROUP, "same-id"))
        val userRequest = assertNotNull(ledger.tryBeginInfoRequest(TargetKind.USER, "same-id"))
        ledger.recordInfo(groupRequest, name = null, retryAfterEpochSeconds = 500L)
        ledger.abandonInfoRequest(userRequest)
        assertTrue(ledger.needsInfo(TargetKind.USER, "same-id", nowEpochSeconds = 300L))
        assertEquals(false, ledger.needsInfo(TargetKind.GROUP, "same-id", nowEpochSeconds = 300L))
    }

    @Test
    fun `入站事件提取目标 sighting`() {
        val groupMessage = QqBotIncomingEvent(
            type = QQBOT_GROUP_MESSAGE_CREATE,
            rawPayload = """
                {"op":0,"s":1,"t":"GROUP_MESSAGE_CREATE","d":{
                  "id":"msg-1","group_openid":"group-1","union_group_openid":"union-group-1","timestamp":"2026-07-01T00:00:00Z",
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
        assertEquals("union-group-1", groupSighting?.unionOpenId)
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
