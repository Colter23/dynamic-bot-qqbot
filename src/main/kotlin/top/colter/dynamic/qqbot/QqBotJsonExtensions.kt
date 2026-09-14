package top.colter.dynamic.qqbot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * QQ 官方接口返回的 JSON 结构并不总与文档一致，字段类型异常时强转
 * （`jsonObject` / `jsonArray` / `jsonPrimitive`）会直接抛 `IllegalArgumentException`，
 * 让一次字段类型意外升级成整条消息/整次请求的失败。
 *
 * 这里统一提供安全转换：类型不符时返回 null，由调用方决定是跳过该字段还是整体判定失败。
 *
 * 接收者声明为可空，因此 `element.asPrimitiveOrNull()` 与 `element?.asPrimitiveOrNull()`
 * 两种写法都可用。
 */
internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
