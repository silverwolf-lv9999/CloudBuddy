package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一个模型提供商（OpenAI 兼容端点）：名称、地址、Key、开关，以及它名下的模型清单。
 *
 * 之前只有一个全局端点（customBaseUrl + customApiKey），所有模型都挤在那一个
 * 地址底下——用户同时用 DeepSeek 官方和一个中转站就没法配。照 RikkaHub 的
 * ProviderSetting 结构拆开：提供商是独立实体，模型挂在提供商下面，
 * 会话选模型时按「提供商 + 模型」唯一定位。
 *
 * 协议目前只支持 OpenAI Chat Completions 一种（自研引擎 [OpenAiStreamClient]
 * 的范围）；Claude / Gemini 原生协议要接时再给这里加子类。
 */
data class LlmProvider(
    /** 稳定标识。模型与选型都引用它，改名字不影响已选的模型。 */
    val id: String,
    /** 界面显示名，如「DeepSeek 官方」「某某中转」。 */
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    /** 关掉的提供商不参与请求，模型清单保留（重新打开开关就回来）。 */
    val enabled: Boolean = true,
    /**
     * 自定义请求路径（相对地址的路径部分，如 `v1/chat/completions`，或完整 URL）。
     * 空 = 按 [EndpointResolver] 的统一规则拼；个别网关的路径不在 `/chat/completions`
     * 结尾时用它覆盖（照 RikkaHub 的 chatCompletionsPath 字段）。
     */
    val chatPath: String = "",
    /** 自定义请求头。中转站的鉴权头（如 `HTTP-Referer`、`X-Title`）从这里加。 */
    val headers: Map<String, String> = emptyMap(),
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    /** 选择键里的紧凑形态，模型卡与聊天选择器显示用。 */
    val label: String get() = name.trim().ifBlank { baseUrl.take(24) }

    fun encode(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("enabled", enabled)
        .put("chatPath", chatPath)
        .put("headers", JSONObject(headers))

    companion object {
        fun decodeList(raw: String?): List<LlmProvider> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            val out = ArrayList<LlmProvider>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                val headers = LinkedHashMap<String, String>()
                o.optJSONObject("headers")?.let { h ->
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = h.optString(k)
                        if (k.isNotBlank() && v.isNotBlank()) headers[k] = v
                    }
                }
                out.add(
                    LlmProvider(
                        id = id,
                        name = o.optString("name").trim(),
                        baseUrl = o.optString("baseUrl").trim(),
                        apiKey = o.optString("apiKey").trim(),
                        enabled = o.optBoolean("enabled", true),
                        chatPath = o.optString("chatPath").trim(),
                        headers = headers,
                    )
                )
            }
            return out
        }

        fun encodeList(providers: List<LlmProvider>): String {
            val array = JSONArray()
            providers.forEach { array.put(it.encode()) }
            return array.toString()
        }

        fun newId(): String = "p-" + UUID.randomUUID().toString().take(8)

        /** 多行文本（`Key: Value` 每行一条）解析成请求头；空行与没有冒号的行忽略。 */
        fun parseHeaders(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            text.lines().forEach { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@forEach
                val k = line.take(idx).trim()
                val v = line.substring(idx + 1).trim()
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v
            }
            return out
        }

        /** 请求头转回多行文本，编辑框显示用。 */
        fun headersToText(headers: Map<String, String>): String =
            headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
}
