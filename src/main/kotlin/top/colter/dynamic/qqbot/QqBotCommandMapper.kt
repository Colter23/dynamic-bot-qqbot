package top.colter.dynamic.qqbot

import java.util.UUID
import top.colter.dynamic.core.command.CommandPublishRequest
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingMessage

internal object QqBotCommandMapper {
    fun toCommandRequest(
        sourcePlugin: String,
        incoming: IncomingMessage,
    ): CommandPublishRequest? {
        if (incoming.text.isBlank()) return null

        return CommandPublishRequest(
            sourcePlugin = sourcePlugin,
            context = CommandContext.of(
                platform = QQBOT_PLATFORM_ID.value,
                kind = incoming.target.kind,
                externalId = incoming.target.externalId,
                senderId = incoming.senderId,
                accountId = incoming.target.accountId,
                botAccountId = incoming.botAccountId,
                mentionedAccountIds = incoming.mentions,
            ),
            rawText = incoming.text,
            traceId = UUID.randomUUID().toString(),
        )
    }
}
