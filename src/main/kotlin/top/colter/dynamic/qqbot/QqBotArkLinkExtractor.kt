package top.colter.dynamic.qqbot

import java.net.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.colter.dynamic.core.data.IncomingMessageSegment

/**
 * 把 QQ 官方事件中的 `ark_data` 结构化卡片标准化为通用入站链接段。
 *
 * 官方 `ark_data.fields` 覆盖 miniapp（B 站视频分享卡等）、video_share、tuwen、
 * music_together 等卡片类型，`jump_url` 是卡片的明确跳转地址。用户把分享卡转发到
 * 群里是链接解析的真实入口，提取后主项目链接管线可以直接解析 B 站等平台内容。
 *
 * 与 OneBot 插件的 JSON 卡片提取器语义一致：只提取"明确的跳转链接"，
 * 不猜测、不解析正文 URL。
 */
internal object QqBotArkLinkExtractor {
    private val urlKeys: List<String> = listOf(
        "jump_url",
        "jumpUrl",
        "qqdocurl",
        "url",
        "share_url",
        "shareUrl",
        "target_url",
        "targetUrl",
    )
    private val titleKeys: List<String> = listOf("title", "desc", "name", "prompt")

    fun extract(arkData: JsonElement?, rawPayload: String): IncomingMessageSegment.Link? {
        val card = arkData as? JsonObject ?: return null
        // 官方 `ark_data` 的业务字段在 `fields` 对象里；`prompt`/`ark_name` 在顶层
        val fields = card["fields"] as? JsonObject
        val url = urlKeys.asSequence()
            .mapNotNull { key -> fields?.stringValue(key) ?: card.stringValue(key) }
            .mapNotNull(::normalizeHttpUrl)
            .firstOrNull()
            ?: return null
        val title = titleKeys.asSequence()
            .mapNotNull { key -> fields?.stringValue(key) ?: card.stringValue(key) }
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?.take(MAX_TITLE_LENGTH)

        return IncomingMessageSegment.Link(
            url = url,
            title = title,
            rawPayload = rawPayload,
        )
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeHttpUrl(value: String): String? {
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        }
    }

    private const val MAX_URL_LENGTH: Int = 2_048
    private const val MAX_TITLE_LENGTH: Int = 200
}
