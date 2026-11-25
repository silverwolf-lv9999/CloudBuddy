package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 自定义 API 的连通性自检。
 *
 * 为什么要单独一条探测而不是"让用户发条消息试试"：
 * 配错的三种典型情形（地址多写了 `/v1`、Key 抄错、模型 ID 不存在）在 agent 里
 * 都表现为同一句含糊的失败，用户得反复试。这里发一个 `max_tokens=1` 的最小请求，
 * 一次把「网络通不通 / Key 认不认 / 模型存不存在」区分开。
 *
 * 不引 Koog 的 client：探测要的是原始 HTTP 状态码与错误体，
 * 而框架会把它们包成异常，反而拿不到 `401` 和 `model_not_found` 的区别。
 */
class CustomEndpointProbe(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    data class Result(val ok: Boolean, val detail: String)

    suspend fun probe(config: CustomModelConfig): Result = withContext(Dispatchers.IO) {
        if (!config.usable) {
            return@withContext Result(false, "配置不完整：接口地址、API Key、模型 ID 都要填")
        }
        val url = EndpointResolver.previewPath(config.baseUrl)
        val payload = JSONObject()
            .put("model", config.modelId)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", "ping")
            ))
            .put("max_tokens", 1)
            .put("stream", false)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + config.apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                if (response.isSuccessful) {
                    Result(true, "连接正常（HTTP ${response.code}，模型 ${config.modelId} 可用）")
                } else {
                    Result(false, describe(response.code, text))
                }
            }
        } catch (e: Exception) {
            Result(false, "连不上：${e.message ?: e::class.simpleName}")
        }
    }

    /** 把各家五花八门的错误体压成一句人能看懂的话。 */
    private fun describe(code: Int, body: String): String {
        val message = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")
                ?: o.optString("message")
                ?: o.optString("detail")
        }.getOrNull().orEmpty().ifBlank { body.take(200) }

        val hint = when {
            code == 401 || code == 403 -> "Key 不对或没有权限"
            code == 404 -> "地址或模型 ID 不对（当前请求打到 $message 之外的地方？）"
            code == 402 || code == 429 -> "额度不足或被限流"
            code in 500..599 -> "服务端自己出错，稍后再试"
            code == 400 && message.contains("model", ignoreCase = true) -> "模型 ID 可能不存在"
            else -> ""
        }
        return buildString {
            append("HTTP ").append(code)
            if (message.isNotBlank()) append("：").append(message.take(200))
            if (hint.isNotBlank()) append("\n").append(hint)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
