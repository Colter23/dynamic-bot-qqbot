package top.colter.dynamic.qqbot

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

    suspend fun recallUserMessage(accountId: String, openid: String, messageId: String)

    suspend fun recallGroupMessage(accountId: String, groupOpenid: String, messageId: String)

    override fun close()
}

internal data class QqBotRuntimeAccount(
    val accountId: String,
    val name: String = accountId,
    val state: MessageSinkRouteState = MessageSinkRouteState.READY,
)

internal data class QqBotIncomingEvent(
    val type: String,
    val rawPayload: String,
)

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
