package top.colter.dynamic.qqbot

import java.util.UUID
import top.colter.dynamic.core.command.CommandPublishRequest
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.TargetKind

internal object QqBotCommandMapper {
    fun toCommandRequest(
        sourcePlugin: String,
        incoming: IncomingMessage,
    ): CommandPublishRequest? {
        val commandText = incoming.toCommandText()
        if (commandText.isBlank()) return null

        return CommandPublishRequest(
            sourcePlugin = sourcePlugin,
            context = CommandContext.of(
                platform = QQBOT_PLATFORM_ID.value,
                kind = incoming.target.kind,
                externalId = incoming.target.externalId,
                senderId = incoming.senderId,
                accountId = incoming.target.accountId,
                botAccountId = incoming.botAccountId,
                mentionedAccountIds = incoming.commandMentionedAccountIds(),
            ),
            rawText = commandText,
            traceId = UUID.randomUUID().toString(),
        )
    }

    private fun IncomingMessage.toCommandText(): String {
        val normalized = text.trim()
        val botId = botAccountId?.trim()?.takeIf { it.isNotBlank() } ?: return normalized
        if (botId !in mentions) return normalized

        return normalized
            .replace(QQBOT_LEADING_OFFICIAL_MENTION_PATTERN, "")
            .replace(QQBOT_LEADING_PLAIN_MENTION_PATTERN, "")
            .trimStart()
    }

    private fun IncomingMessage.commandMentionedAccountIds(): Set<String> {
        val botId = botAccountId?.trim()?.takeIf { it.isNotBlank() }
        if (target.kind != TargetKind.USER || botId == null) return mentions

        return mentions + botId
    }
}

private val QQBOT_LEADING_OFFICIAL_MENTION_PATTERN: Regex = Regex("""^(?:\s*<@[^>]+>\s*)+""")
private val QQBOT_LEADING_PLAIN_MENTION_PATTERN: Regex = Regex("""^(?:\s*@\S+\s*)+""")
