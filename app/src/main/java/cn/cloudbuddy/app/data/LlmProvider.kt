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

    companion object {
        fun decodeList(raw: String?): List<LlmProvider> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            val out = ArrayList<LlmProvider>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                out.add(
                    LlmProvider(
                        id = id,
                        name = o.optString("name").trim(),
                        baseUrl = o.optString("baseUrl").trim(),
                        apiKey = o.optString("apiKey").trim(),
                        enabled = o.optBoolean("enabled", true),
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
    }
}
