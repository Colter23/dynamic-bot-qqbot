package top.colter.dynamic.qqbot

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class QqBotOpenApiClient(
    private val config: QqBotConfig,
    private val transport: QqBotHttpTransport = JavaNetQqBotHttpTransport(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private var cachedToken: QqBotAccessToken? = null

    fun accessToken(): QqBotAccessToken {
        val now = nowMillis()
        cachedToken
            ?.takeIf { token -> now + ACCESS_TOKEN_REFRESH_SKEW_MILLIS < token.expiresAtMillis }
            ?.let { return it }

        val appId = config.appId.trim()
        val appSecret = config.appSecret.trim()
        require(appId.isNotBlank()) { "QQ 官方机器人 AppID 不能为空" }
        require(appSecret.isNotBlank()) { "QQ 官方机器人 AppSecret 不能为空" }

        val response = transport.postJson(
            url = APP_ACCESS_TOKEN_URL,
            headers = mapOf("Accept" to "application/json"),
            body = json.encodeToString(
                buildJsonObject {
                    put("appId", JsonPrimitive(appId))
                    put("clientSecret", JsonPrimitive(appSecret))
                },
            ),
        )
        ensureSuccess("获取 QQ 官方机器人访问令牌", response)

        val root = parseResponseObject("获取 QQ 官方机器人访问令牌", response.body)
        val tokenValue = root["access_token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val expiresInSeconds = root["expires_in"]?.jsonPrimitive?.longOrNull
            ?: throw QqBotOpenApiException(
                action = "获取 QQ 官方机器人访问令牌",
                statusCode = response.statusCode,
                message = "响应缺少 expires_in",
                body = response.body,
            )
        if (tokenValue.isBlank()) {
            throw QqBotOpenApiException(
                action = "获取 QQ 官方机器人访问令牌",
                statusCode = response.statusCode,
                message = "响应缺少 access_token",
                body = response.body,
            )
        }
        if (expiresInSeconds <= 0) {
            throw QqBotOpenApiException(
                action = "获取 QQ 官方机器人访问令牌",
                statusCode = response.statusCode,
                message = "expires_in 无效：$expiresInSeconds",
                body = response.body,
            )
        }

        return QqBotAccessToken(
            value = tokenValue,
            expiresAtMillis = now + expiresInSeconds * MILLIS_PER_SECOND,
        ).also { cachedToken = it }
    }

    fun gatewayBotEndpoint(): QqBotGatewayEndpoint {
        val token = accessToken()
        val response = transport.get(
            url = "$OPEN_API_BASE_URL/gateway/bot",
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to qqBotAuthorization(token.value),
            ),
        )
        ensureSuccess("获取 QQ 官方机器人 WebSocket 接入点", response)

        val root = parseResponseObject("获取 QQ 官方机器人 WebSocket 接入点", response.body)
        val url = root["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isBlank()) {
            throw QqBotOpenApiException(
                action = "获取 QQ 官方机器人 WebSocket 接入点",
                statusCode = response.statusCode,
                message = "响应缺少 url",
                body = response.body,
            )
        }

        return QqBotGatewayEndpoint(
            url = url,
            shards = root["shards"]?.jsonPrimitive?.intOrNull,
            sessionStartLimit = root["session_start_limit"]?.let(::parseSessionStartLimit),
        )
    }

    fun sendUserMessage(openid: String, request: QqBotSendRequest): QqBotSendReceipt {
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/users/${openid.pathSegment()}/messages",
            headers = authorizedHeaders(),
            body = encodeSendRequest(request),
        )
        ensureSuccess("发送 QQ 官方机器人单聊消息", response)
        return parseSendReceipt("发送 QQ 官方机器人单聊消息", response)
    }

    fun sendGroupMessage(groupOpenid: String, request: QqBotSendRequest): QqBotSendReceipt {
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/groups/${groupOpenid.pathSegment()}/messages",
            headers = authorizedHeaders(),
            body = encodeSendRequest(request),
        )
        ensureSuccess("发送 QQ 官方机器人群聊消息", response)
        return parseSendReceipt("发送 QQ 官方机器人群聊消息", response)
    }

    fun prepareUserMedia(openid: String, request: QqBotMediaPrepareRequest): QqBotMediaResource {
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/users/${openid.pathSegment()}/files",
            headers = authorizedHeaders(),
            body = encodeMediaPrepareRequest(request),
        )
        ensureSuccess("登记 QQ 官方机器人单聊富媒体资源", response)
        return parseMediaResource("登记 QQ 官方机器人单聊富媒体资源", response)
    }

    fun prepareGroupMedia(groupOpenid: String, request: QqBotMediaPrepareRequest): QqBotMediaResource {
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/groups/${groupOpenid.pathSegment()}/files",
            headers = authorizedHeaders(),
            body = encodeMediaPrepareRequest(request),
        )
        ensureSuccess("登记 QQ 官方机器人群聊富媒体资源", response)
        return parseMediaResource("登记 QQ 官方机器人群聊富媒体资源", response)
    }

    fun recallUserMessage(openid: String, messageId: String) {
        val response = transport.delete(
            url = "$OPEN_API_BASE_URL/v2/users/${openid.pathSegment()}/messages/${messageId.pathSegment()}",
            headers = authorizedHeaders(),
        )
        ensureSuccess("撤回 QQ 官方机器人单聊消息", response)
    }

    fun recallGroupMessage(groupOpenid: String, messageId: String) {
        val response = transport.delete(
            url = "$OPEN_API_BASE_URL/v2/groups/${groupOpenid.pathSegment()}/messages/${messageId.pathSegment()}",
            headers = authorizedHeaders(),
        )
        ensureSuccess("撤回 QQ 官方机器人群聊消息", response)
    }

    private fun parseSessionStartLimit(element: JsonElement): QqBotSessionStartLimit {
        val root = element.jsonObject
        return QqBotSessionStartLimit(
            total = root["total"]?.jsonPrimitive?.intOrNull,
            remaining = root["remaining"]?.jsonPrimitive?.intOrNull,
            resetAfterMillis = root["reset_after"]?.jsonPrimitive?.longOrNull,
            maxConcurrency = root["max_concurrency"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun ensureSuccess(action: String, response: QqBotHttpResponse) {
        if (response.statusCode in 200..299) return
        val errorMessage = runCatching {
            val root = json.parseToJsonElement(response.body).jsonObject
            root["message"]?.jsonPrimitive?.contentOrNull
                ?: root["msg"]?.jsonPrimitive?.contentOrNull
                ?: root["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val errorCode = runCatching {
            val root = json.parseToJsonElement(response.body).jsonObject
            root["code"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        throw QqBotOpenApiException(
            action = action,
            statusCode = response.statusCode,
            code = errorCode,
            message = errorMessage ?: "HTTP 状态码 ${response.statusCode}",
            body = response.body,
        )
    }

    private fun parseResponseObject(action: String, body: String) = runCatching {
        json.parseToJsonElement(body).jsonObject
    }.getOrElse { error ->
        throw QqBotOpenApiException(
            action = action,
            statusCode = 200,
            message = "响应不是有效 JSON 对象：${error.message.orEmpty()}",
            body = body,
        )
    }

    private fun authorizedHeaders(): Map<String, String> {
        val token = accessToken()
        return mapOf(
            "Accept" to "application/json",
            "Authorization" to qqBotAuthorization(token.value),
        )
    }

    private fun encodeSendRequest(request: QqBotSendRequest): String {
        require(request.msgType >= 0) { "QQ 官方机器人消息类型不能为负数" }
        require(request.msgSeq >= 1) { "QQ 官方机器人 msg_seq 必须大于 0" }
        val mediaFileInfo = request.mediaFileInfo?.trim()?.takeIf { it.isNotBlank() }
        require(request.msgType != QQBOT_MEDIA_MSG_TYPE || mediaFileInfo != null) {
            "QQ 官方机器人富媒体消息必须包含 file_info"
        }
        require(mediaFileInfo == null || request.msgType == QQBOT_MEDIA_MSG_TYPE) {
            "QQ 官方机器人只有富媒体消息可以携带 file_info"
        }
        val replyTo = request.replyToMessageId?.trim()?.takeIf { it.isNotBlank() }
        return json.encodeToString(
            buildJsonObject {
                put("content", JsonPrimitive(request.content.ifBlank { " " }))
                put("msg_type", JsonPrimitive(request.msgType))
                if (mediaFileInfo != null) {
                    put(
                        "media",
                        buildJsonObject {
                            put("file_info", JsonPrimitive(mediaFileInfo))
                        },
                    )
                }
                if (replyTo != null) {
                    put("msg_id", JsonPrimitive(replyTo))
                    put("msg_seq", JsonPrimitive(request.msgSeq))
                }
            },
        )
    }

    private fun encodeMediaPrepareRequest(request: QqBotMediaPrepareRequest): String {
        val url = request.url?.trim()?.takeIf { it.isNotBlank() }
        val fileDataBase64 = request.fileDataBase64?.trim()?.takeIf { it.isNotBlank() }
        require(url != null || fileDataBase64 != null) {
            "QQ 官方机器人富媒体资源 URL 或 file_data 不能同时为空"
        }
        return json.encodeToString(
            buildJsonObject {
                put("file_type", JsonPrimitive(request.fileType.code))
                if (url != null) {
                    put("url", JsonPrimitive(url))
                }
                if (fileDataBase64 != null) {
                    put("file_data", JsonPrimitive(fileDataBase64))
                }
                put("srv_send_msg", JsonPrimitive(request.srvSendMsg))
            },
        )
    }

    private fun parseSendReceipt(action: String, response: QqBotHttpResponse): QqBotSendReceipt {
        val root = parseResponseObject(action, response.body)
        val messageId = root["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (messageId.isBlank()) {
            throw QqBotOpenApiException(
                action = action,
                statusCode = response.statusCode,
                message = "响应缺少 id",
                body = response.body,
            )
        }
        return QqBotSendReceipt(
            messageId = messageId,
            rawPayload = response.body,
        )
    }

    private fun parseMediaResource(action: String, response: QqBotHttpResponse): QqBotMediaResource {
        val root = parseResponseObject(action, response.body)
        val fileInfo = root["file_info"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (fileInfo.isBlank()) {
            throw QqBotOpenApiException(
                action = action,
                statusCode = response.statusCode,
                message = "响应缺少 file_info",
                body = response.body,
            )
        }
        return QqBotMediaResource(
            fileInfo = fileInfo,
            fileUuid = root["file_uuid"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
            ttlSeconds = root["ttl"]?.jsonPrimitive?.longOrNull,
            rawPayload = response.body,
        )
    }

    private fun String.pathSegment(): String {
        val value = trim()
        require(value.isNotBlank()) { "QQ 官方机器人 OpenAPI 路径参数不能为空" }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    companion object {
        private const val APP_ACCESS_TOKEN_URL: String = "https://bots.qq.com/app/getAppAccessToken"
        private const val OPEN_API_BASE_URL: String = "https://api.sgroup.qq.com"
        private const val ACCESS_TOKEN_REFRESH_SKEW_MILLIS: Long = 60_000L
        private const val MILLIS_PER_SECOND: Long = 1_000L

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

internal data class QqBotAccessToken(
    val value: String,
    val expiresAtMillis: Long,
)

internal data class QqBotGatewayEndpoint(
    val url: String,
    val shards: Int? = null,
    val sessionStartLimit: QqBotSessionStartLimit? = null,
)

internal data class QqBotSessionStartLimit(
    val total: Int? = null,
    val remaining: Int? = null,
    val resetAfterMillis: Long? = null,
    val maxConcurrency: Int? = null,
)

internal data class QqBotMediaPrepareRequest(
    val fileType: QqBotMediaFileType,
    val url: String? = null,
    val fileDataBase64: String? = null,
    val srvSendMsg: Boolean = false,
)

internal data class QqBotMediaResource(
    val fileInfo: String,
    val fileUuid: String? = null,
    val ttlSeconds: Long? = null,
    val rawPayload: String = "",
)

internal class QqBotOpenApiException(
    val action: String,
    val statusCode: Int,
    val code: String? = null,
    override val message: String,
    val body: String,
) : RuntimeException(
    buildString {
        append(action)
        append("失败：状态码=")
        append(statusCode)
        if (!code.isNullOrBlank()) {
            append(" 业务码=")
            append(code)
        }
        append(" 消息=")
        append(message)
    },
)

internal interface QqBotHttpTransport {
    fun postJson(url: String, headers: Map<String, String>, body: String): QqBotHttpResponse

    fun get(url: String, headers: Map<String, String>): QqBotHttpResponse

    fun delete(url: String, headers: Map<String, String>): QqBotHttpResponse
}

internal data class QqBotHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal class JavaNetQqBotHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : QqBotHttpTransport {
    override fun postJson(url: String, headers: Map<String, String>, body: String): QqBotHttpResponse {
        val request = baseRequest(url, headers)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return send(request)
    }

    override fun get(url: String, headers: Map<String, String>): QqBotHttpResponse {
        val request = baseRequest(url, headers)
            .GET()
            .build()
        return send(request)
    }

    override fun delete(url: String, headers: Map<String, String>): QqBotHttpResponse {
        val request = baseRequest(url, headers)
            .DELETE()
            .build()
        return send(request)
    }

    private fun baseRequest(url: String, headers: Map<String, String>): HttpRequest.Builder {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
            }
    }

    private fun send(request: HttpRequest): QqBotHttpResponse {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return QqBotHttpResponse(
            statusCode = response.statusCode(),
            body = response.body().orEmpty(),
        )
    }
}

internal fun qqBotAuthorization(accessToken: String): String {
    val token = accessToken.trim()
    require(token.isNotBlank()) { "QQ 官方机器人访问令牌不能为空" }
    return "QQBot $token"
}
