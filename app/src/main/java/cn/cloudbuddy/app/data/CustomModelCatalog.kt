package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 拉取 OpenAI 兼容端点的模型列表（`GET {base}/models`）。
 *
 * 为什么要有：模型 ID 是个必须逐字符正确的字符串，`deepseek-chat` 写成
 * `deepseek_chat` 就只会在第一次发消息时炸，而且报错长得像"Key 不对"。
 * 拉一次列表让用户点选，比让他去文档里抄靠谱得多。
 *
 * 不引 Koog 的 client：这里要的是原始 HTTP 状态码，框架会把它包成异常。
 */
class CustomModelCatalog(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    data class Result(val models: List<String>, val error: String?) {
        val ok: Boolean get() = error == null
    }

    suspend fun fetch(baseUrl: String, apiKey: String): Result = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result(emptyList(), "先填接口地址")
        if (apiKey.isBlank()) return@withContext Result(emptyList(), "先填 API Key")

        // 用归一化后的地址，别在用户已经写了 /v1 的地址后面再拼一层；
        // 也别在裸域名上直接拼 /models（OpenAI 这类端点只在 /v1 下有）。
        val url = EndpointResolver.modelsUrl(baseUrl)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + apiKey)
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result(emptyList(), describe(response.code, text, url))
                }
                val models = parse(text)
                if (models.isEmpty()) {
                    // 少数端点把列表放在别处或直接不给，这时只能说清楚"拿到了但没解析出模型"。
                    Result(emptyList(), "接口返回了内容，但没找到模型列表（也许该端点不提供 /models）")
                } else {
                    Result(models, null)
                }
            }
        } catch (e: Exception) {
            Result(emptyList(), "连不上：${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * 解析模型列表。
     *
     * 兼容三种形状：`{"data":[{"id":"x"}]}`（OpenAI 标准）、`{"data":["x"]}`、
     * 以及顶层就是数组的野路子。字段名也可能是 `id` / `name` / `model`。
     */
    fun parse(body: String): List<String> {
        val trim = body.trim()
        if (trim.isEmpty()) return emptyList()
        val array = runCatching {
            when {
                trim.startsWith("[") -> JSONArray(trim)
                else -> JSONObject(trim).optJSONArray("data")
                    ?: JSONObject(trim).optJSONArray("models")
            }
        }.getOrNull() ?: return emptyList()

        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is JSONObject -> {
                    val id = item.optString("id").ifBlank { item.optString("name") }
                        .ifBlank { item.optString("model") }
                    if (id.isNotBlank()) out.add(id.trim())
                }
                is String -> if (item.isNotBlank()) out.add(item.trim())
            }
        }
        return out.toList()
    }

    private fun describe(code: Int, body: String, url: String): String {
        val message = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")
                ?: o.optString("message")
                ?: o.optString("detail")
        }.getOrNull().orEmpty().ifBlank { body.take(160) }

        val hint = when (code) {
            401, 403 -> "Key 不对或没有权限"
            404 -> "地址不对：当前请求打到 $url"
            402, 429 -> "额度不足或被限流"
            in 500..599 -> "服务端自己出错，稍后再试"
            else -> ""
        }
        return buildString {
            append("HTTP ").append(code)
            if (message.isNotBlank()) append("：").append(message.take(160))
            if (hint.isNotBlank()) append("\n").append(hint)
        }
    }
}
