package top.colter.dynamic.qqbot

import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.TargetAddress

internal data class QqBotReplyContext(
    val traceId: String,
    val target: TargetAddress,
    val accountId: String?,
    val messageId: String,
    val createdAtMillis: Long,
)

internal class QqBotReplyContextStore(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val contexts = object : LinkedHashMap<String, QqBotReplyContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QqBotReplyContext>?): Boolean {
            return size > maxSize
        }
    }

    init {
        require(maxSize > 0) { "QQ bot reply context max size must be positive" }
        require(ttlMillis >= 0) { "QQ bot reply context TTL must not be negative" }
    }

    @Synchronized
    fun remember(traceId: String, incoming: IncomingMessage) {
        val normalizedTraceId = traceId.trim().takeIf { it.isNotBlank() } ?: return
        val messageId = incoming.messageId.trim().takeIf { it.isNotBlank() } ?: return
        val now = nowMillis()
        purgeExpired(now)
        contexts[normalizedTraceId] = QqBotReplyContext(
            traceId = normalizedTraceId,
            target = incoming.target,
            accountId = incoming.botAccountId?.trim()?.takeIf { it.isNotBlank() }
                ?: incoming.target.accountId?.trim()?.takeIf { it.isNotBlank() },
            messageId = messageId,
            createdAtMillis = now,
        )
    }

    @Synchronized
    fun findReplyMessageId(
        traceId: String,
        target: TargetAddress,
        accountId: String,
    ): String? {
        val normalizedTraceId = traceId.trim().takeIf { it.isNotBlank() } ?: return null
        val now = nowMillis()
        purgeExpired(now)
        val context = contexts[normalizedTraceId] ?: return null
        if (context.isExpired(now)) {
            contexts.remove(normalizedTraceId)
            return null
        }
        if (context.target.stableValue() != target.stableValue()) return null
        val normalizedAccountId = accountId.trim().takeIf { it.isNotBlank() }
        if (context.accountId != null && context.accountId != normalizedAccountId) return null
        return context.messageId
    }

    @Synchronized
    fun clear() {
        contexts.clear()
    }

    private fun purgeExpired(now: Long) {
        if (ttlMillis == Long.MAX_VALUE) return
        val iterator = contexts.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.isExpired(now)) {
                iterator.remove()
            }
        }
    }

    private fun QqBotReplyContext.isExpired(now: Long): Boolean {
        return ttlMillis != Long.MAX_VALUE && now - createdAtMillis > ttlMillis
    }

    private companion object {
        private const val DEFAULT_MAX_SIZE: Int = 512
        private const val DEFAULT_TTL_MILLIS: Long = 30L * 60L * 1_000L
    }
}
