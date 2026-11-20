package top.colter.dynamic.qqbot

import kotlinx.serialization.Serializable
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<QqBotTargetLedger>()

/**
 * 目标账本状态：群与好友**分开存放**，值就是展示名（未知时为空串）。
 *
 * 设计取舍：
 * - 用 `Map` 的 key 承载 openid，不再冗余存一份 id，也不再用 `kind` 字段区分类型
 *   （由所在集合表达），后台取数时也就无需再按类型过滤一遍。
 * - 不存"首次/最后活跃时间""来源事件"这类易变字段：它们会让**每条消息**都触发一次
 *   落盘（写放大），而展示与选择目标都不需要这些信息。
 * - 目标失联（移出群/删除好友）直接删除 key，不再用 `:REMOVED` 后缀把布尔语义塞进
 *   字符串字段。
 *
 * 重复 sighting 是空操作，只有新目标或资料变化时才落盘。
 */
@Serializable
internal data class QqBotTargetLedgerState(
    /** 群 openid -> 群名称（未知时为空串）。 */
    val groups: MutableMap<String, String> = mutableMapOf(),
    /** 用户 openid -> 昵称（未知时为空串）。 */
    val users: MutableMap<String, String> = mutableMapOf(),
    val userAvatars: MutableMap<String, MediaRef> = mutableMapOf(),
    /** 群 OpenID -> 跨应用统一标识；发送消息仍使用群 OpenID。 */
    val groupUnionOpenIds: MutableMap<String, String> = mutableMapOf(),
)

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
 *
 * 注意：必须直接存 [QqBotTargetLedgerState] **对象**，不能存序列化后的字符串——
 * 主程序 `YamlDocumentStore` 要求 YAML 根节点是映射（对象），存字符串会写成标量，
 * 读取时直接报"根节点必须是对象"。
 */
internal class QqBotTargetLedger(
    private val dataStore: PluginDataStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    class InfoRequest internal constructor(val address: TargetAddress)

    private val lock = Any()

    private var state: QqBotTargetLedgerState = QqBotTargetLedgerState()

    /**
     * 资料拉取失败后的重试时间，**仅存内存**。
     *
     * 不落盘是有意的：这个值只用来避免同一次运行内反复打平台接口，重启后允许每个
     * 目标再试一次，省掉一个持久化字段。
     */
    private val infoRetryAfterEpochSeconds: MutableMap<TargetAddress, Long> = mutableMapOf()

    /**
     * 正在进行中的资料请求，**仅存内存**。
     *
     * 没有它就挡不住并发重复请求：新目标入账后名称还是空的，从"首次记录"到"首次请求返回"
     * 这段网络往返里，该群每来一条消息都会再触发一次请求（群资料接口是白名单能力，有频控）。
     * 由 [tryBeginInfoRequest] 占位，[recordInfo] / [abandonInfoRequest] 释放。
     */
    private val infoRequestsInFlight: MutableMap<TargetAddress, InfoRequest> = mutableMapOf()

    init {
        state = loadState()
    }

    /** 记录事件自带的最新资料；返回是否为新目标，重复资料不落盘。 */
    fun record(
        kind: TargetKind,
        externalId: String,
        name: String? = null,
        avatar: MediaRef? = null,
        unionOpenId: String? = null,
    ): Boolean {
        if (!isSupported(kind)) return false
        val id = externalId.trim()
        require(id.isNotBlank()) { "目标 OpenID 不能为空" }
        synchronized(lock) {
            val targets = targetsOf(kind)
            val added = !targets.containsKey(id)
            if (added) targets[id] = ""
            var changed = added
            val resolvedName = name?.trim()?.takeIf(String::isNotBlank)
            if (resolvedName != null && targets[id] != resolvedName) {
                targets[id] = resolvedName
                changed = true
            }
            if (kind == TargetKind.USER && avatar != null && state.userAvatars[id] != avatar) {
                state.userAvatars[id] = avatar
                changed = true
            }
            if (kind == TargetKind.GROUP && recordUnionOpenIdLocked(id, unionOpenId)) changed = true
            if (changed) persistLocked()
            return added
        }
    }

    /** 目标失联（被移出群/删除好友）：直接移除，返回是否存在过。 */
    fun markRemoved(kind: TargetKind, externalId: String): Boolean {
        if (!isSupported(kind)) return false
        val id = externalId.trim()
        synchronized(lock) {
            val targets = targetsOf(kind)
            if (targets.remove(id) == null) return false
            val key = infoKey(kind, id)
            infoRetryAfterEpochSeconds.remove(key)
            infoRequestsInFlight.remove(key)
            if (kind == TargetKind.USER) state.userAvatars.remove(id)
            if (kind == TargetKind.GROUP) state.groupUnionOpenIds.remove(id)
            persistLocked()
            return true
        }
    }

    /**
     * 候选目标。因为群和好友分开存放，这里**不需要再按类型过滤**。
     *
     * [kind] 为 null 时返回全部；[TargetKind.CHANNEL] / [TargetKind.OTHER] 没有对应存储，返回空列表。
     */
    fun listCandidates(kind: TargetKind?): List<MessageTargetCandidate> {
        synchronized(lock) {
            return when (kind) {
                TargetKind.GROUP -> state.groups.toCandidates(TargetKind.GROUP)
                TargetKind.USER -> state.users.toCandidates(TargetKind.USER)
                null -> state.groups.toCandidates(TargetKind.GROUP) + state.users.toCandidates(TargetKind.USER)
                else -> emptyList()
            }
        }
    }

    fun findCandidate(address: TargetAddress): MessageTargetCandidate? {
        if (address.platformId != QQBOT_PLATFORM_ID) return null
        if (!isSupported(address.kind)) return null
        val id = address.externalId.trim()
        synchronized(lock) {
            val name = targetsOf(address.kind)[id] ?: return null
            return MessageTargetCandidate(
                address = address.copy(accountId = address.accountId?.trim()?.takeIf { it.isNotBlank() }),
                name = name.displayNameOr(id),
                avatar = if (address.kind == TargetKind.USER) state.userAvatars[id] else null,
            )
        }
    }

    fun size(): Int = synchronized(lock) { state.groups.size + state.users.size }

    /**
     * 该目标是否还需要拉取资料（只读判断，不占位）。
     *
     * 群需要名称，好友需要昵称和头像；已有请求在途或仍在失败冷却期内返回 false。
     */
    fun needsInfo(kind: TargetKind, externalId: String, nowEpochSeconds: Long? = null): Boolean {
        if (!isSupported(kind)) return false
        val id = externalId.trim()
        val now = nowEpochSeconds ?: this.nowEpochSeconds()
        synchronized(lock) {
            return needsInfoLocked(kind, id, now)
        }
    }

    /**
     * 抢占一个资料请求名额。
     *
     * 返回本次请求的令牌；目标资料完整、已有请求在途或仍在冷却时返回 null。
     * 请求结束后用同一令牌调用 [recordInfo] 或 [abandonInfoRequest] 释放名额。
     */
    fun tryBeginInfoRequest(
        kind: TargetKind,
        externalId: String,
        nowEpochSeconds: Long? = null,
    ): InfoRequest? {
        if (!isSupported(kind)) return null
        val id = externalId.trim()
        val now = nowEpochSeconds ?: this.nowEpochSeconds()
        synchronized(lock) {
            if (!needsInfoLocked(kind, id, now)) return null
            val key = infoKey(kind, id)
            return InfoRequest(key).also { infoRequestsInFlight[key] = it }
        }
    }

    /** 放弃一个已抢占的资料请求名额（请求被取消、没有结果可登记时使用）。 */
    fun abandonInfoRequest(request: InfoRequest) {
        synchronized(lock) {
            infoRequestsInFlight.remove(request.address, request)
        }
    }

    /** 限频等待结束后复查已占位的请求；忽略该请求自身的在途标记。 */
    fun shouldContinueInfoRequest(request: InfoRequest): Boolean {
        val key = request.address
        synchronized(lock) {
            return infoRequestsInFlight[key] === request &&
                needsInfoLocked(key.kind, key.externalId, nowEpochSeconds(), ignoreInFlight = true)
        }
    }

    /** [needsInfo] 与 [tryBeginInfoRequest] 共用的判断；调用方必须持有 [lock]。 */
    private fun needsInfoLocked(kind: TargetKind, id: String, now: Long, ignoreInFlight: Boolean = false): Boolean {
        val name = targetsOf(kind)[id] ?: return false
        if (name.isNotBlank() && (kind == TargetKind.GROUP || state.userAvatars[id] != null)) return false
        val key = infoKey(kind, id)
        if (!ignoreInFlight && infoRequestsInFlight.contains(key)) return false
        val retryAfter = infoRetryAfterEpochSeconds[key] ?: return true
        return now >= retryAfter
    }

    /**
     * 记录一次资料拉取结果，返回落盘内容是否变化。
     *
     * 只补充缺少的昵称/头像，避免覆盖请求期间收到的事件资料；群统一标识独立保存。
     * 未补齐资料时登记内存冷却，避免部分资料缺失导致每条消息都查询接口。
     *
     * 只有当前令牌能释放名额并更新资料或冷却时间。
     */
    fun recordInfo(
        request: InfoRequest,
        name: String?,
        retryAfterEpochSeconds: Long? = null,
        avatar: MediaRef? = null,
        unionOpenId: String? = null,
    ): Boolean {
        val key = request.address
        val kind = key.kind
        val id = key.externalId
        val resolved = name?.trim()?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            if (!infoRequestsInFlight.remove(key, request)) return false
            val targets = targetsOf(kind)
            val previous = targets[id] ?: return false
            var changed = false
            if (resolved != null && previous.isBlank()) {
                targets[id] = resolved
                changed = true
            }
            if (kind == TargetKind.USER && avatar != null && !state.userAvatars.containsKey(id)) {
                state.userAvatars[id] = avatar.copy(kind = MediaKind.AVATAR)
                changed = true
            }
            if (kind == TargetKind.GROUP && recordUnionOpenIdLocked(id, unionOpenId)) changed = true
            if (targets[id].orEmpty().isNotBlank() && (kind == TargetKind.GROUP || state.userAvatars[id] != null)) {
                infoRetryAfterEpochSeconds.remove(key)
            } else {
                infoRetryAfterEpochSeconds[key] = retryAfterEpochSeconds ?: Long.MAX_VALUE
            }
            if (changed) persistLocked()
            return changed
        }
    }

    private fun Map<String, String>.toCandidates(kind: TargetKind): List<MessageTargetCandidate> {
        return map { (id, name) ->
            MessageTargetCandidate(
                address = TargetAddress.of(
                    platformId = QQBOT_PLATFORM_ID.value,
                    kind = kind,
                    externalId = id,
                ),
                name = name.displayNameOr(id),
                avatar = if (kind == TargetKind.USER) state.userAvatars[id] else null,
            )
        }
    }

    /** 展示名未知时回退为 openid，保证后台至少能显示一条可辨识的记录。 */
    private fun String.displayNameOr(fallback: String): String = takeIf { it.isNotBlank() } ?: fallback

    private fun infoKey(kind: TargetKind, id: String): TargetAddress =
        TargetAddress.of(QQBOT_PLATFORM_ID.value, kind, id)

    private fun recordUnionOpenIdLocked(id: String, unionOpenId: String?): Boolean {
        val resolved = unionOpenId?.trim()?.takeIf(String::isNotBlank) ?: return false
        if (state.groupUnionOpenIds[id] == resolved) return false
        state.groupUnionOpenIds[id] = resolved
        return true
    }

    private fun targetsOf(kind: TargetKind): MutableMap<String, String> = when (kind) {
        TargetKind.GROUP -> state.groups
        TargetKind.USER -> state.users
        else -> throw IllegalArgumentException("QQ 官方机器人目标账本不支持的类型：$kind")
    }

    private fun isSupported(kind: TargetKind): Boolean =
        kind == TargetKind.GROUP || kind == TargetKind.USER

    private fun loadState(): QqBotTargetLedgerState {
        if (!dataStore.exists(STORE_NAME)) return QqBotTargetLedgerState()
        return runCatching {
            dataStore.loadOrCreate(STORE_NAME, QqBotTargetLedgerState::class) { QqBotTargetLedgerState() }
        }.onFailure { error ->
            logger.warn(error) { "QQ 官方机器人目标账本读取失败，将从空账本重新积累：name=$STORE_NAME" }
        }.getOrDefault(QqBotTargetLedgerState())
    }

    private fun persistLocked() {
        runCatching {
            dataStore.save(STORE_NAME, state)
        }.onFailure { error ->
            logger.warn(error) { "QQ 官方机器人目标账本写入失败：name=$STORE_NAME" }
        }
    }

    private companion object {
        private const val STORE_NAME: String = "message-target-ledger"
    }
}
