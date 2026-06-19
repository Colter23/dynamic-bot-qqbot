package top.colter.dynamic.qqbot

import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

internal sealed interface QqBotTarget {
    data class User(val openid: String) : QqBotTarget
    data class Group(val groupOpenid: String) : QqBotTarget
    data class Unsupported(val reason: String) : QqBotTarget

    companion object {
        fun fromAddress(address: TargetAddress): QqBotTarget {
            if (address.platformId != QQBOT_PLATFORM_ID) {
                return Unsupported("目标平台不是 QQ：${address.platformId.value}")
            }
            return when (address.kind) {
                TargetKind.USER -> User(address.externalId)
                TargetKind.GROUP -> Group(address.externalId)
                else -> Unsupported("QQ 官方机器人暂不支持目标类型：${address.kind}")
            }
        }
    }
}
