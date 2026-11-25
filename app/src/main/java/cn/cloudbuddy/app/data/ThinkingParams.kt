package cn.cloudbuddy.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 思考强度的档位。
 *
 * 各家 OpenAI 兼容端点的「思考模式」开关长得完全不一样，但档位语义是通用的：
 * 关 → 直接答、不烧推理 token；低/中/高 → 先想再答，越高想得越久。
 * 这里只保留「人话」档位，具体发什么字段由 [ThinkingStyle] 决定。
 */
enum class ThinkingEffort(val key: String, val label: String) {
    OFF("off", "关闭"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    /** 关闭时不该再带 reasoning_effort——带了等于告诉服务端「想一点」，自相矛盾。 */
    val enabled: Boolean get() = this != OFF

    companion object {
        fun of(key: String?): ThinkingEffort =
            entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/**
 * 思考参数用哪一家的写法。
 *
 * 这是本功能唯一的复杂点：**「思考强度」不是一个标准字段**。
 *
 * - OpenAI / xAI / 阶跃：只认 `reasoning_effort`
 * - DeepSeek / 智谱 / Kimi / MiniMax / 豆包：`thinking:{type:enabled}` 才是开关，
 *   强度另配 `reasoning_effort`
 * - 通义（DashScope）/ 硅基流动：开关叫 `enable_thinking`（布尔）
 *
 * 只发 `reasoning_effort` 的话，第二、三类端点会当作没看见——这正是「模型不思考」的
 * 直接原因。所以让用户选写法，或者交给 [AUTO] 按接口地址猜（猜错也能手改）。
 */
enum class ThinkingStyle(val key: String, val label: String) {
    AUTO("auto", "自动"),
    THINKING("thinking", "thinking"),
    ENABLE_THINKING("enable_thinking", "enable_thinking"),
    EFFORT("effort", "reasoning_effort");

    companion object {
        fun of(key: String?): ThinkingStyle =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * 把「思考强度」翻译成请求体里真正的字段。
 *
 * 全部是纯函数，方便单测：这块出错的表现是「模型不思考」或「接口 400」，
 * 两种都不带任何指向性，光靠打一次真接口很难定位。
 */
object ThinkingParams {

    /** 按 host 猜写法。猜错的代价只是参数没生效，用户可以在设置页改。 */
    fun autoStyle(baseUrl: String): ThinkingStyle {
        val host = baseUrl.lowercase()
        return when {
            // 阿里云 DashScope（通义）与硅基流动：布尔开关。
            listOf("dashscope", "aliyuncs", "siliconflow").any(host::contains) ->
                ThinkingStyle.ENABLE_THINKING
            // thinking:{type:enabled} 阵营。智谱 bigmodel.cn、Kimi moonshot、
            // 火山方舟 volces、MiniMax 都是这个写法。
            listOf(
                "deepseek", "moonshot", "kimi", "bigmodel", "zhipu",
                "minimax", "volces", "ark.cn", "doubao"
            ).any(host::contains) -> ThinkingStyle.THINKING
            // 其余（OpenAI 官方、xAI、阶跃、自建 vLLM…）走 reasoning_effort。
            else -> ThinkingStyle.EFFORT
        }
    }

    fun resolveStyle(baseUrl: String, chosen: String): ThinkingStyle =
        ThinkingStyle.of(chosen).let { if (it == ThinkingStyle.AUTO) autoStyle(baseUrl) else it }

    /**
     * 真正要合并进请求体的字段。
     *
     * 空 Map 表示「一个字都不发」——[ThinkingStyle.EFFORT] 下的「关闭」只能这样表达：
     * `reasoning_effort` 没有「关」这个值（OpenAI 的 none/minimal 各家支持度不一），
     * 硬发 `none` 反而容易在 o 系列以外的模型上换回一句 400。
     */
    fun fields(
        baseUrl: String,
        styleKey: String,
        effortKey: String,
        modelId: String = "",
    ): Map<String, JsonElement> {
        val style = resolveStyle(baseUrl, styleKey)
        val effort = ThinkingEffort.of(effortKey)
        // 老一代 OpenAI 模型（gpt-4o / 4.1 / turbo）收到 reasoning_effort 会直接 400，
        // 不是「忽略」——默认强度是「中」，不挡这一下的话，这类模型第一次用就报错。
        // 只对 EFFORT 这一套生效：明确选了 thinking / enable_thinking 说明用的是别家。
        if (style == ThinkingStyle.EFFORT && looksClassic(modelId)) return emptyMap()
        val out = LinkedHashMap<String, JsonElement>()
        when (style) {
            ThinkingStyle.THINKING -> {
                // 关闭也必须显式发 disabled：这家的思考默认是开的，不发是「继续想」。
                out["thinking"] = buildJsonObject {
                    put("type", JsonPrimitive(if (effort.enabled) "enabled" else "disabled"))
                }
            }

            ThinkingStyle.ENABLE_THINKING -> {
                out["enable_thinking"] = JsonPrimitive(effort.enabled)
            }

            ThinkingStyle.EFFORT, ThinkingStyle.AUTO -> Unit
        }
        // 前两家把强度单独放在 reasoning_effort 里；第三家本身就是强度字段。
        if (effort.enabled) out["reasoning_effort"] = JsonPrimitive(effort.key)
        return out
    }

    /**
     * 是否为「只支持普通对话」的老一代 OpenAI 模型。
     *
     * 只用于 EFFORT 写法下的兜底：OpenAI 对不认识的参数是**报错**而不是忽略，
     * 与大多数兼容端点不同。
     */
    private fun looksClassic(modelId: String): Boolean {
        val id = modelId.lowercase()
        return listOf("gpt-3", "gpt-4o", "gpt-4-turbo", "gpt-4.1", "chatgpt-4o")
            .any(id::startsWith)
    }

    /** 设置页里那句「实际会发出什么」，让用户能自己核对。 */
    fun describe(
        baseUrl: String,
        styleKey: String,
        effortKey: String,
        modelId: String = "",
    ): String {
        val style = resolveStyle(baseUrl, styleKey)
        val fields = fields(baseUrl, style.key, effortKey, modelId)
        if (fields.isEmpty()) {
            return when {
                style == ThinkingStyle.EFFORT && looksClassic(modelId) ->
                    "当前不发思考参数：${modelId} 不支持 reasoning_effort（发了会 400）。"
                else ->
                    "当前不发思考参数：${style.label} 这一套没有「关闭」的值，" +
                        "想彻底关掉思考请改用 thinking 或 enable_thinking 写法。"
            }
        }
        val body = JsonObject(fields).toString()
        return "本次会附加：$body"
    }

    /**
     * 把 [fields] 并进一个 Chat Completions 请求体。
     *
     * 用字符串拼接而不是反序列化成强类型：请求体是 Koog 组装的，字段会随版本变化，
     * 我们只加自己这两三个字段，其余原样透传。解析失败就原样返回——宁可这次不思考，
     * 也不能把整个请求搞坏。
     */
    fun apply(body: String, fields: Map<String, JsonElement>): String {
        if (fields.isEmpty()) return body
        val obj = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return body
        // 已经有的字段不覆盖：万一将来 Koog 自己开始发 reasoning_effort，
        // 让框架的值优先，我们的只做补充。
        val merged = LinkedHashMap<String, JsonElement>(obj)
        fields.forEach { (k, v) -> if (!merged.containsKey(k)) merged[k] = v }
        return JsonObject(merged).toString()
    }
}
