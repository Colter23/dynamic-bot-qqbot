package top.colter.dynamic.qqbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageTargetCandidate

/**
 * 消息目标账本：从入站事件中积累机器人可触达的群/用户。
 *
 * QQ 官方 API 不提供"列出机器人所在群/好友"的接口，普通用户也拿不到
 * group_openid / user_openid。插件从 WebSocket 事件中把见过的 openid 记入账本，
 * `listMessageTargets` 从这里出，让后台可以直接选择目标而不是手填长串 ID。
 *
 * 事件来源：
 * - `GROUP_MESSAGE_CREATE` / `GROUP_AT_MESSAGE_CREATE`：活跃群
 * - `C2C_MESSAGE_CREATE`：活跃单聊用户
 * - `GROUP_ADD_ROBOT` / `GROUP_DEL_ROBOT`：群加入/移出
 * - `FRIEND_ADD` / `FRIEND_DEL`：好友添加/删除
 * - `C2C_MSG_RECEIVE` / `C2C_MSG_REJECT`：用户主动消息开关
 * - `GROUP_MSG_RECEIVE` / `GROUP_MSG_REJECT`：群通知开关
 *
 * 状态经 [PluginDataStore] 持久化到 `data/plugins/{pluginId}/`，重启不丢。
 */
internal class QqBotTargetLedger(
    private val dataStore: PluginDataStore,
    private val pluginId: String = QQBOT_PLUGIN_ID,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    @Serializable
    internal data class LedgerEntry(
        val externalId: String,
        val kind: String,
        val firstSeenAtEpochSeconds: Long,
        val lastSeenAtEpochSeconds: Long,
        val lastEvent: String,
    )

    @Serializable
    private data class LedgerState(
        val entries: MutableMap<String, LedgerEntry> = mutableMapOf(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private var state: LedgerState = loadState()

    /** 记录一次目标 sighting；返回是否为新目标。 */
    fun record(
        kind: TargetKind,
        externalId: String,
        event: String,
        timestampEpochSeconds: Long = nowEpochSeconds(),
    ): Boolean {
        val id = externalId.trim()
        require(id.isNotBlank()) { "目标 OpenID 不能为空" }
        synchronized(lock) {
            val key = entryKey(kind, id)
            val now = nowEpochSeconds()
            val existing = state.entries[key]
            val isNew = existing == null
            val effectiveTime = if (timestampEpochSeconds > 0) timestampEpochSeconds else now
            val lastSeen = if (existing != null) maxOf(existing.lastSeenAtEpochSeconds, effectiveTime) else effectiveTime
            state.entries[key] = LedgerEntry(
                externalId = id,
                kind = kind.name,
                firstSeenAtEpochSeconds = existing?.firstSeenAtEpochSeconds ?: lastSeen,
                lastSeenAtEpochSeconds = lastSeen,
                lastEvent = event,
            )
            persistLocked()
            return isNew
        }
    }

    /** 标记目标失联（被移出群/删除好友）；物理保留记录便于诊断，从候选列表隐藏。 */
    fun markRemoved(kind: TargetKind, externalId: String, event: String): Boolean {
        val id = externalId.trim()
        synchronized(lock) {
            val key = entryKey(kind, id)
            val existing = state.entries[key] ?: return false
            state.entries[key] = existing.copy(
                lastEvent = event,
                lastSeenAtEpochSeconds = nowEpochSeconds(),
                kind = "${existing.kind}$REMOVED_MARK",
            )
            persistLocked()
            return true
        }
    }

    fun listCandidates(kind: TargetKind?): List<MessageTargetCandidate> {
        synchronized(lock) {
            return state.entries.values
                .filter { entry -> !entry.kind.endsWith(REMOVED_MARK) }
                .filter { entry -> kind == null || entry.kind == kind.name }
                .sortedWith(
                    compareByDescending<LedgerEntry> { it.lastSeenAtEpochSeconds }
                        .thenBy { it.externalId },
                )
                .map { entry -> entry.toCandidate() }
        }
    }

    fun findCandidate(address: TargetAddress): MessageTargetCandidate? {
        if (address.platformId != QQBOT_PLATFORM_ID) return null
        synchronized(lock) {
            val key = entryKey(address.kind, address.externalId)
            return state.entries[key]
                ?.takeIf { it.kind.removeSuffix(REMOVED_MARK) == address.kind.name }
                ?.toCandidate(accountId = address.accountId)
        }
    }

    fun size(): Int = synchronized(lock) {
        state.entries.values.count { !it.kind.endsWith(REMOVED_MARK) }
    }

    private fun LedgerEntry.toCandidate(accountId: String? = null): MessageTargetCandidate {
        return MessageTargetCandidate(
            address = TargetAddress.of(
                platformId = QQBOT_PLATFORM_ID.value,
                kind = TargetKind.valueOf(kind.removeSuffix(REMOVED_MARK)),
                externalId = externalId,
                accountId = accountId,
            ),
            name = externalId,
        )
    }

    private fun entryKey(kind: TargetKind, externalId: String): String = "${kind.name}:$externalId"

    private fun loadState(): LedgerState {
        return runCatching {
            if (!dataStore.exists(STORE_NAME)) return LedgerState()
            val raw = dataStore.loadOrCreate(STORE_NAME, String::class) { "" }
            if (raw.isBlank()) LedgerState() else json.decodeFromString<LedgerState>(raw)
        }.getOrElse {
            LedgerState()
        }
    }

    private fun persistLocked() {
        runCatching {
            dataStore.save(STORE_NAME, json.encodeToString(state))
        }
    }

    private companion object {
        private const val STORE_NAME: String = "message-target-ledger"
        private const val REMOVED_MARK: String = ":REMOVED"
    }
}
