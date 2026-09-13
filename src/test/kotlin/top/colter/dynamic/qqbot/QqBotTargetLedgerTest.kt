package top.colter.dynamic.qqbot

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class QqBotTargetLedgerTest {
    @Test
    fun `记录群与单聊目标并按类型过滤`() {
        val ledger = ledger()

        ledger.record(TargetKind.GROUP, "group-1", QQBOT_GROUP_ADD_ROBOT, 100L)
        ledger.record(TargetKind.USER, "user-1", QQBOT_C2C_MESSAGE_CREATE, 200L)

        val groups = ledger.listCandidates(TargetKind.GROUP)
        val users = ledger.listCandidates(TargetKind.USER)
        val all = ledger.listCandidates(null)

        assertEquals(listOf("group-1"), groups.map { it.address.externalId })
        assertEquals(listOf("user-1"), users.map { it.address.externalId })
        assertEquals(2, all.size)
        assertEquals("qq", all[0].address.platformId.value)
    }

    @Test
    fun `最近活跃的目标排在前面`() {
        val ledger = ledger()

        ledger.record(TargetKind.GROUP, "old-group", QQBOT_GROUP_ADD_ROBOT, 100L)
        ledger.record(TargetKind.GROUP, "new-group", QQBOT_GROUP_MESSAGE_CREATE, 300L)
        ledger.record(TargetKind.GROUP, "mid-group", QQBOT_GROUP_AT_MESSAGE_CREATE, 200L)

        val groups = ledger.listCandidates(TargetKind.GROUP)

        assertEquals(listOf("new-group", "mid-group", "old-group"), groups.map { it.address.externalId })
    }

    @Test
    fun `移出群与删除好友后目标从列表隐藏`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1", QQBOT_GROUP_ADD_ROBOT, 100L)
        ledger.record(TargetKind.USER, "user-1", QQBOT_FRIEND_ADD, 100L)

        ledger.markRemoved(TargetKind.GROUP, "group-1", QQBOT_GROUP_DEL_ROBOT)
        ledger.markRemoved(TargetKind.USER, "user-1", QQBOT_FRIEND_DEL)

        assertEquals(0, ledger.listCandidates(TargetKind.GROUP).size)
        assertEquals(0, ledger.listCandidates(TargetKind.USER).size)
        assertEquals(0, ledger.size())
    }

    @Test
    fun `同一目标重复记录只保留一条并更新最近时间`() {
        val ledger = ledger()

        ledger.record(TargetKind.GROUP, "group-1", QQBOT_GROUP_ADD_ROBOT, 100L)
        ledger.record(TargetKind.GROUP, "group-1", QQBOT_GROUP_MESSAGE_CREATE, 500L)

        val groups = ledger.listCandidates(TargetKind.GROUP)
        assertEquals(1, groups.size)
        assertEquals(1, ledger.size())
    }

    @Test
    fun `按地址查找目标并透传账号`() {
        val ledger = ledger()
        ledger.record(TargetKind.GROUP, "group-1", QQBOT_GROUP_ADD_ROBOT, 100L)
        ledger.record(TargetKind.USER, "user-1", QQBOT_C2C_MESSAGE_CREATE, 100L)

        val found = ledger.findCandidate(
            TargetAddress.of("qq", TargetKind.GROUP, "group-1", accountId = "app-id"),
        )
        val missing = ledger.findCandidate(
            TargetAddress.of("qq", TargetKind.GROUP, "missing"),
        )
        val wrongPlatform = ledger.findCandidate(
            TargetAddress.of("other", TargetKind.GROUP, "group-1"),
        )

        assertEquals("group-1", found?.address?.externalId)
        assertEquals("app-id", found?.address?.accountId)
        assertNull(missing)
        assertNull(wrongPlatform)
    }

    @Test
    fun `状态经 PluginDataStore 持久化并在重载后保留`() {
        val store = FakeDataStore()
        val first = QqBotTargetLedger(dataStore = store)
        first.record(TargetKind.GROUP, "group-1", QQBOT_GROUP_ADD_ROBOT, 100L)
        first.markRemoved(TargetKind.USER, "user-1", QQBOT_FRIEND_DEL)

        val reloaded = QqBotTargetLedger(dataStore = store)

        assertEquals(listOf("group-1"), reloaded.listCandidates(TargetKind.GROUP).map { it.address.externalId })
        assertEquals(0, reloaded.listCandidates(TargetKind.USER).size)
        assertEquals(1, reloaded.size())
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
            rawPayload = """{"op":0,"s":2,"t":"GROUP_ADD_ROBOT","d":{"group_openid":"group-2","timestamp":100}}""",
        )
        val friendAdd = QqBotIncomingEvent(
            type = QQBOT_FRIEND_ADD,
            rawPayload = """{"op":0,"s":3,"t":"FRIEND_ADD","d":{"openid":"user-2","timestamp":200}}""",
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
        assertEquals(QQBOT_GROUP_MESSAGE_CREATE, groupSighting?.event)
        assertEquals(TargetKind.GROUP, addSighting?.kind)
        assertEquals("group-2", addSighting?.externalId)
        assertEquals(100L, addSighting?.timestampEpochSeconds)
        assertEquals(TargetKind.USER, friendSighting?.kind)
        assertEquals("user-2", friendSighting?.externalId)
        assertNull(QqBotIncomingMessageMapper.toTargetSighting(unknown))
    }

    private fun ledger(): QqBotTargetLedger = QqBotTargetLedger(dataStore = FakeDataStore())

    private class FakeDataStore : top.colter.dynamic.core.config.PluginDataStore {
        private val files = mutableMapOf<String, String>()

        override val dataDir: java.nio.file.Path = Files.createTempDirectory("qqbot-ledger-test")

        override fun <T : Any> loadOrCreate(
            name: String,
            clazz: kotlin.reflect.KClass<T>,
            migrations: List<top.colter.dynamic.core.config.ConfigMigration>,
            defaultProvider: () -> T,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return (files[name] as? T) ?: defaultProvider()
        }

        override fun <T : Any> save(name: String, value: T) {
            files[name] = value.toString()
        }

        override fun <T : Any> reload(
            name: String,
            clazz: kotlin.reflect.KClass<T>,
            migrations: List<top.colter.dynamic.core.config.ConfigMigration>,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return files[name] as T
        }

        override fun exists(name: String): Boolean = files.containsKey(name)

        override fun delete(name: String): Boolean = files.remove(name) != null

        override fun resolvePath(name: String): java.nio.file.Path = dataDir.resolve(name)
    }
}
