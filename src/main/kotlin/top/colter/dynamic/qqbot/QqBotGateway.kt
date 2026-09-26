package top.colter.dynamic.qqbot

import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.plugin.MessageSinkRouteState

internal interface QqBotGateway : AutoCloseable {
    suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit)

    suspend fun availableAccounts(): List<QqBotRuntimeAccount>

    suspend fun sendUserMessage(
        accountId: String,
        openid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt

    suspend fun sendGroupMessage(
        accountId: String,
        groupOpenid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt

    suspend fun sendUserMediaMessage(
        accountId: String,
        openid: String,
        media: QqBotMediaResourceSource,
        request: QqBotSendRequest,
    ): QqBotSendReceipt

    suspend fun sendGroupMediaMessage(
        accountId: String,
        groupOpenid: String,
        media: QqBotMediaResourceSource,
        request: QqBotSendRequest,
    ): QqBotSendReceipt

    suspend fun recallUserMessage(accountId: String, openid: String, messageId: String)

    suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String)

    /**
     * 获取群基本信息。
     *
     * 官方只有群资料接口，没有好友/用户资料接口。默认返回 null 表示该网关不提供资料查询。
     */
    suspend fun groupInfo(accountId: String, groupOpenid: String): QqBotGroupInfo? = null

    override fun close()
}

internal data class QqBotRuntimeAccount(
    val accountId: String,
    val name: String = accountId,
    val state: MessageSinkRouteState = MessageSinkRouteState.READY,
    /** 机器人头像，取自 `GET /users/@me`；拉取失败时为空。 */
    val avatar: MediaRef? = null,
)

internal data class QqBotIncomingEvent(
    val type: String,
    val rawPayload: String,
    val botAccountId: String? = null,
)

/**
 * 富媒体资源尚未登记成功，消息接口还没有被调用。
 *
 * 这类失败可以安全交给主项目重试；它不能和消息接口本身的网络超时混为一谈，
 * 后者可能已经被 QQ 受理，继续重试会有重复发送风险。
 */
internal class QqBotMediaPreparationException(
    cause: Throwable,
) : RuntimeException(
    "QQ 官方机器人富媒体资源准备失败：${cause.message ?: cause::class.simpleName.orEmpty()}",
    cause,
) {
    val retryable: Boolean
        get() = (cause as? QqBotOpenApiException)?.retryable ?: true
}

internal class NoopQqBotGateway : QqBotGateway {
    override suspend fun connect(onIncomingEvent: (QqBotIncomingEvent) -> Unit) {
    }

    override suspend fun availableAccounts(): List<QqBotRuntimeAccount> = emptyList()

    override suspend fun sendUserMessage(
        accountId: String,
        openid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = notImplemented()

    override suspend fun sendGroupMessage(
        accountId: String,
        groupOpenid: String,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = notImplemented()

    override suspend fun sendUserMediaMessage(
        accountId: String,
        openid: String,
        media: QqBotMediaResourceSource,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = notImplemented()

    override suspend fun sendGroupMediaMessage(
        accountId: String,
        groupOpenid: String,
        media: QqBotMediaResourceSource,
        request: QqBotSendRequest,
    ): QqBotSendReceipt = notImplemented()

    override suspend fun recallUserMessage(accountId: String, openid: String, messageId: String) {
        notImplemented<Unit>()
    }

    override suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String) {
        notImplemented<Unit>()
    }

    override fun close() {
    }

    private fun <T> notImplemented(): T {
        throw UnsupportedOperationException("QQ 官方机器人网关实现尚未接入")
    }
}
