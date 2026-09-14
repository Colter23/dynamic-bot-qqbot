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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * QQ 官方机器人 OpenAPI 作用域。
 *
 * 官方单聊与群聊接口路径结构一致（`/v2/users/{openid}/...` 与 `/v2/groups/{group_openid}/...`），
 * 用该类型统一表达，避免为每个接口写两份用户/群聊版本。
 */
internal sealed interface QqBotOpenApiScope {
    /** 路径中的资源段，`users` 或 `groups`。 */
    val resource: String

    /** 路径中的 openid。 */
    val id: String

    data class User(val openid: String) : QqBotOpenApiScope {
        override val resource: String get() = "users"
        override val id: String get() = openid
    }

    data class Group(val groupOpenid: String) : QqBotOpenApiScope {
        override val resource: String get() = "groups"
        override val id: String get() = groupOpenid
    }
}

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
        val tokenValue = root["access_token"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
        val expiresInSeconds = root["expires_in"]?.asPrimitiveOrNull()?.longOrNull
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
        val url = root["url"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
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
            shards = root["shards"]?.asPrimitiveOrNull()?.intOrNull,
            sessionStartLimit = root["session_start_limit"]?.let(::parseSessionStartLimit),
        )
    }

    fun sendMessage(scope: QqBotOpenApiScope, request: QqBotSendRequest): QqBotSendReceipt {
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/${scope.resource}/${scope.id.pathSegment()}/messages",
            headers = authorizedHeaders(),
            body = encodeSendRequest(request),
        )
        ensureSuccess("发送 QQ 官方机器人消息", response)
        return parseSendReceipt("发送 QQ 官方机器人消息", response)
    }

    /**
     * 登记富媒体资源，返回可复用的 `file_info`。
     *
     * 传 [QqBotMediaPrepareRequest.url] 走平台下载转存；
     * 传 [QqBotMediaPrepareRequest.uploadId] 走分片上传合并。
     */
    fun prepareMedia(scope: QqBotOpenApiScope, request: QqBotMediaPrepareRequest): QqBotMediaResource {
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/${scope.resource}/${scope.id.pathSegment()}/files",
            headers = authorizedHeaders(),
            body = encodeMediaPrepareRequest(request),
        )
        ensureSuccess("登记 QQ 官方机器人富媒体资源", response)
        return parseMediaResource("登记 QQ 官方机器人富媒体资源", response)
    }

    fun recallMessage(scope: QqBotOpenApiScope, messageId: String) {
        val response = transport.delete(
            url = "$OPEN_API_BASE_URL/v2/${scope.resource}/${scope.id.pathSegment()}/messages/${messageId.pathSegment()}",
            headers = authorizedHeaders(),
        )
        ensureSuccess("撤回 QQ 官方机器人消息", response)
    }

    /**
     * 获取群基本信息。
     *
     * 官方标注该接口为白名单能力，机器人无权限时返回业务码 `11253`。
     * 官方**没有**对应的好友/用户资料接口，只有群可以补全名称。
     */
    fun groupInfo(groupOpenid: String): QqBotGroupInfo {
        val action = "获取 QQ 官方机器人群信息"
        val response = transport.get(
            url = "$OPEN_API_BASE_URL/v2/groups/${groupOpenid.pathSegment()}/info",
            headers = authorizedHeaders(),
        )
        ensureSuccess(action, response)

        val root = parseResponseObject(action, response.body)
        return QqBotGroupInfo(
            groupOpenid = root["group_openid"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty(),
            name = root["group_name"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty(),
            memberCount = root["group_member_num"]?.asPrimitiveOrNull()?.intOrNull,
        )
    }

    /** 分片上传第一步：预上传，返回 `upload_id` 与各分片预签名 URL。 */
    fun prepareUpload(scope: QqBotOpenApiScope, request: QqBotUploadPrepareRequest): QqBotUploadTicket {
        val action = "准备 QQ 官方机器人分片上传"
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/${scope.resource}/${scope.id.pathSegment()}/upload_prepare",
            headers = authorizedHeaders(),
            body = json.encodeToString(
                buildJsonObject {
                    put("file_type", JsonPrimitive(request.fileType.code))
                    put("file_size", JsonPrimitive(request.fileSizeBytes.toString()))
                    put("file_name", JsonPrimitive(request.fileName))
                    put("md5", JsonPrimitive(request.md5))
                    put("sha1", JsonPrimitive(request.sha1))
                    put("md5_10m", JsonPrimitive(request.md5Prefix))
                },
            ),
        )
        ensureSuccess(action, response)

        val root = parseResponseObject(action, response.body)
        val uploadId = root["upload_id"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
        if (uploadId.isBlank()) {
            throw QqBotOpenApiException(
                action = action,
                statusCode = response.statusCode,
                message = "响应缺少 upload_id",
                body = response.body,
            )
        }
        val defaultBlockSize = root["block_size"]?.asPrimitiveOrNull()?.contentOrNull?.toLongOrNull()
            ?: DEFAULT_BLOCK_SIZE_BYTES
        val parts = root["parts"]?.asArrayOrNull()?.mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val index = item["index"]?.asPrimitiveOrNull()?.intOrNull ?: return@mapNotNull null
            val presignedUrl = item["presigned_url"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
            if (presignedUrl.isBlank()) return@mapNotNull null
            QqBotUploadPart(
                index = index,
                presignedUrl = presignedUrl,
                blockSizeBytes = item["block_size"]?.asPrimitiveOrNull()?.contentOrNull?.toLongOrNull()
                    ?: defaultBlockSize,
            )
        }.orEmpty()
        if (parts.isEmpty()) {
            throw QqBotOpenApiException(
                action = action,
                statusCode = response.statusCode,
                message = "响应缺少分片列表",
                body = response.body,
            )
        }

        val uploadConfig = root["upload_config"]?.asObjectOrNull()
        return QqBotUploadTicket(
            uploadId = uploadId,
            blockSizeBytes = defaultBlockSize,
            parts = parts.sortedBy { it.index },
            concurrency = uploadConfig?.get("concurrency")?.asPrimitiveOrNull()?.intOrNull ?: DEFAULT_UPLOAD_CONCURRENCY,
            retryTimeoutSeconds = uploadConfig?.get("retry_timeout")?.asPrimitiveOrNull()?.longOrNull
                ?: DEFAULT_UPLOAD_RETRY_TIMEOUT_SECONDS,
            retryDelaySeconds = uploadConfig?.get("retry_delay")?.asPrimitiveOrNull()?.longOrNull
                ?: DEFAULT_UPLOAD_RETRY_DELAY_SECONDS,
        )
    }

    /** 分片上传第二步：把单个分片 PUT 到预签名 URL（不携带机器人鉴权头）。 */
    fun putUploadPart(presignedUrl: String, bytes: ByteArray) {
        val url = presignedUrl.trim()
        require(url.isNotBlank()) { "QQ 官方机器人分片预签名 URL 不能为空" }
        val response = transport.putBytes(url, bytes)
        if (response.statusCode !in 200..299) {
            throw QqBotOpenApiException(
                action = "上传 QQ 官方机器人分片",
                statusCode = response.statusCode,
                message = "HTTP 状态码 ${response.statusCode}",
                body = response.body,
            )
        }
    }

    /** 分片上传第三步：通知服务端该分片已完成。 */
    fun finishUploadPart(scope: QqBotOpenApiScope, request: QqBotUploadPartFinishRequest) {
        val response = transport.postJson(
            url = "$OPEN_API_BASE_URL/v2/${scope.resource}/${scope.id.pathSegment()}/upload_part_finish",
            headers = authorizedHeaders(),
            body = json.encodeToString(
                buildJsonObject {
                    put("upload_id", JsonPrimitive(request.uploadId))
                    put("part_index", JsonPrimitive(request.partIndex))
                    put("block_size", JsonPrimitive(request.blockSizeBytes.toString()))
                    put("md5", JsonPrimitive(request.md5))
                },
            ),
        )
        ensureSuccess("确认 QQ 官方机器人分片", response)
    }

    private fun parseSessionStartLimit(element: JsonElement): QqBotSessionStartLimit {
        val root = element.asObjectOrNull() ?: return QqBotSessionStartLimit()
        return QqBotSessionStartLimit(
            total = root["total"]?.asPrimitiveOrNull()?.intOrNull,
            remaining = root["remaining"]?.asPrimitiveOrNull()?.intOrNull,
            resetAfterMillis = root["reset_after"]?.asPrimitiveOrNull()?.longOrNull,
            maxConcurrency = root["max_concurrency"]?.asPrimitiveOrNull()?.intOrNull,
        )
    }

    private fun ensureSuccess(action: String, response: QqBotHttpResponse) {
        if (response.statusCode in 200..299) return
        // 错误响应体只解析一次；非 JSON 或根节点不是对象时退化为仅凭状态码报错
        val errorBody = runCatching {
            json.parseToJsonElement(response.body).asObjectOrNull()
        }.getOrNull()
        val errorMessage = errorBody?.let { root ->
            root["message"]?.asPrimitiveOrNull()?.contentOrNull
                ?: root["msg"]?.asPrimitiveOrNull()?.contentOrNull
                ?: root["error"]?.asPrimitiveOrNull()?.contentOrNull
        }
        val errorCode = errorBody?.let { root ->
            (root["err_code"] ?: root["code"])?.asPrimitiveOrNull()?.contentOrNull
        }

        throw QqBotOpenApiException(
            action = action,
            statusCode = response.statusCode,
            code = errorCode,
            message = errorMessage ?: "HTTP 状态码 ${response.statusCode}",
            body = response.body,
        )
    }

    private fun parseResponseObject(action: String, body: String): JsonObject = runCatching {
        json.parseToJsonElement(body).asObjectOrNull() ?: error("响应根节点不是 JSON 对象")
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
        val uploadId = request.uploadId?.trim()?.takeIf { it.isNotBlank() }
        require(url != null || uploadId != null) {
            "QQ 官方机器人富媒体资源 URL 或 upload_id 不能同时为空"
        }
        return json.encodeToString(
            buildJsonObject {
                put("file_type", JsonPrimitive(request.fileType.code))
                if (url != null) {
                    put("url", JsonPrimitive(url))
                }
                if (uploadId != null) {
                    put("upload_id", JsonPrimitive(uploadId))
                }
                request.fileName?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                    put("file_name", JsonPrimitive(name))
                }
                put("srv_send_msg", JsonPrimitive(request.srvSendMsg))
            },
        )
    }

    private fun parseSendReceipt(action: String, response: QqBotHttpResponse): QqBotSendReceipt {
        val root = parseResponseObject(action, response.body)
        val messageId = root["id"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
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
        val fileInfo = root["file_info"]?.asPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
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
            fileUuid = root["file_uuid"]?.asPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
            ttlSeconds = root["ttl"]?.asPrimitiveOrNull()?.longOrNull,
            rawPayload = response.body,
        )
    }

    private fun String.pathSegment(): String {
        val value = trim()
        require(value.isNotBlank()) { "QQ 官方机器人 OpenAPI 路径参数不能为空" }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    companion object {
        /** 官方自 2026-08-10 起统一接口域名为 `api.bot.qq.com`。 */
        private const val APP_ACCESS_TOKEN_URL: String = "https://api.bot.qq.com/app/getAppAccessToken"
        private const val OPEN_API_BASE_URL: String = "https://api.bot.qq.com"
        private const val ACCESS_TOKEN_REFRESH_SKEW_MILLIS: Long = 60_000L
        private const val MILLIS_PER_SECOND: Long = 1_000L
        private const val DEFAULT_BLOCK_SIZE_BYTES: Long = 5L * 1024 * 1024
        private const val DEFAULT_UPLOAD_CONCURRENCY: Int = 1
        private const val DEFAULT_UPLOAD_RETRY_TIMEOUT_SECONDS: Long = 300L
        private const val DEFAULT_UPLOAD_RETRY_DELAY_SECONDS: Long = 1L

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

/**
 * 富媒体资源登记请求。
 *
 * - [url]：URL 上传，平台下载转存。
 * - [uploadId]：分片上传合并，需先完成 `upload_prepare` / 分片 PUT / `upload_part_finish`。
 */
internal data class QqBotMediaPrepareRequest(
    val fileType: QqBotMediaFileType,
    val url: String? = null,
    val uploadId: String? = null,
    val fileName: String? = null,
    val srvSendMsg: Boolean = false,
)

internal data class QqBotMediaResource(
    val fileInfo: String,
    val fileUuid: String? = null,
    val ttlSeconds: Long? = null,
    val rawPayload: String = "",
)

/** 群基本信息（`GET /v2/groups/{group_openid}/info`）。 */
internal data class QqBotGroupInfo(
    val groupOpenid: String,
    val name: String,
    val memberCount: Int? = null,
)

internal data class QqBotUploadPrepareRequest(
    val fileType: QqBotMediaFileType,
    val fileSizeBytes: Long,
    val fileName: String,
    val md5: String,
    val sha1: String,
    val md5Prefix: String,
)

internal data class QqBotUploadTicket(
    val uploadId: String,
    val blockSizeBytes: Long,
    val parts: List<QqBotUploadPart>,
    val concurrency: Int = 1,
    val retryTimeoutSeconds: Long = 300L,
    val retryDelaySeconds: Long = 1L,
)

internal data class QqBotUploadPart(
    val index: Int,
    val presignedUrl: String,
    val blockSizeBytes: Long,
)

internal data class QqBotUploadPartFinishRequest(
    val uploadId: String,
    val partIndex: Int,
    val blockSizeBytes: Long,
    val md5: String,
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
) {
    /**
     * 该错误是否值得自动重试。
     *
     * 官方业务码语义参见 qqbot-wiki-md 错误码表；未识别的码默认可重试，
     * 已知不可恢复的码（回复窗口过期、参数无效、内容违规、拒收、禁言等）直接终态失败，
     * 避免主项目 outbox 反复撞同一面墙。
     */
    val retryable: Boolean
        get() = when (code) {
            // 回复窗口/事件回复过期或超限：重试必然再失败
            "304103", "40034005", "40034024", "40034025", "40034026", "40034027", "40034128" -> false
            // 参数与内容类错误：修正前重试无意义
            "22006", "304061", "304080", "40054007", "40054010", "40054018", "50059" -> false
            // 内容违规、用户拒收、机器人被禁言/移出群：需要人工介入
            "40034006", "40054002", "40054003", "40054013", "40034101" -> false
            // 好友关系不存在：非重试可解
            "40054004" -> false
            // 消息被去重（msg_id+msg_seq 重复）：换 seq 重发才有意义，主项目重试无解
            "40054005" -> false
            // 上传大小超限、格式不支持：终态失败
            "850019", "850031" -> false
            else -> true
        }

    /** 官方明示"发送异常，请稍后重试"的码：无法确认服务器是否已受理，按状态未知处理。 */
    val outcomeUnknown: Boolean
        get() = code in setOf("50055001", "50055002", "50055006")

    /** 上传/下载通道的临时故障：官方建议重试。 */
    val transientUploadFailure: Boolean
        get() = code in setOf("40093001", "850026", "850027")
}

internal interface QqBotHttpTransport {
    fun postJson(url: String, headers: Map<String, String>, body: String): QqBotHttpResponse

    fun get(url: String, headers: Map<String, String>): QqBotHttpResponse

    fun delete(url: String, headers: Map<String, String>): QqBotHttpResponse

    /** 分片上传：把分片字节 PUT 到预签名 URL。 */
    fun putBytes(url: String, bytes: ByteArray): QqBotHttpResponse
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

    override fun putBytes(url: String, bytes: ByteArray): QqBotHttpResponse {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
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
