package cn.cloudbuddy.app.data

/**
 * OpenAI 兼容端点的地址归一化。
 *
 * 这里处理的是一个**必然会被踩到**的问题：Koog 的 `OpenAIClientSettings`
 * 默认把请求打到 `{baseUrl}/v1/chat/completions`。而各家服务的"地址"填法并不统一：
 *
 * | 用户填写 | 拼出来的实际请求 | 结果 |
 * | --- | --- | --- |
 * | `https://api.deepseek.com` | `/v1/chat/completions` | 对 |
 * | `https://api.deepseek.com/v1` | `/v1/v1/chat/completions` | **404** |
 * | `https://x.com/v1/chat/completions` | `/v1/chat/completions/v1/chat/completions` | **404** |
 *
 * 用户照 OpenAI 的习惯填 `/v1` 是最常见的做法（官方文档、各种教程都这么写），
 * 所以不能要求用户去理解路径拼接规则，必须自己吃掉这层差异。
 *
 * 拆成纯函数是为了能单测：这条逻辑错了整个功能就用不了，而它完全不需要网络。
 */
object EndpointResolver {

    /** 归一化结果：给 Koog 用的 base 与相对路径。 */
    data class Resolved(
        /** 不含尾部斜杠、不含 chat/completions 的根地址。 */
        val baseUrl: String,
        /** 相对 [baseUrl] 的路径，如 `v1/chat/completions`。 */
        val chatCompletionsPath: String,
    )

    const val DEFAULT_PATH = "v1/chat/completions"

    /**
     * 把用户填的地址拆成「根地址 + 路径」。
     *
     * 规则按优先级：
    1. 去掉首尾空白与尾部斜杠；
    2. 已经带有 `/chat/completions`（用户直接贴了完整端点）→ 剥掉，剩下的都算 base；
    3. 以 `/v1` 结尾 → base 保留原样（含 /v1），路径只用 `chat/completions`；
    4. 其余情况 → 按缺省 `v1/chat/completions` 补全。
     */
    fun resolve(rawUrl: String): Resolved {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return Resolved("", DEFAULT_PATH)

        val lower = trimmed.lowercase()
        val completionsMarker = "/chat/completions"
        if (lower.endsWith(completionsMarker)) {
            // 用户贴了完整端点：把路径整段剥掉，剩下的当 base。
            val base = trimmed.dropLast(completionsMarker.length).trimEnd('/')
            // 剥完可能剩下 .../v1，此时 base 已含 v1，路径只需 chat/completions。
            return if (base.lowercase().endsWith("/v1")) {
                Resolved(base, "chat/completions")
            } else {
                Resolved(base, DEFAULT_PATH)
            }
        }

        if (lower.endsWith("/v1")) {
            // base 已含版本号，路径不能再带一次 v1。
            return Resolved(trimmed, "chat/completions")
        }

        return Resolved(trimmed, DEFAULT_PATH)
    }

    /**
     * 模型列表（`GET /models`）的完整地址。
     *
     * 必须跟 chat/completions 走同一套前缀规则：裸域名按缺省补 `v1` 的端点
     * （OpenAI、Moonshot 都是这样）在根上并没有 `/models`，直接拼
     * `https://api.openai.com/models` 会拿到 404，而用户看到的提示却是
     * 「地址不对」——他会去反复改那个明明是对的地址。
     */
    fun modelsUrl(rawUrl: String): String {
        val r = resolve(rawUrl)
        if (r.baseUrl.isBlank()) return ""
        // chatCompletionsPath 是 "v1/chat/completions" 或 "chat/completions"：
        // 剥掉 "chat/completions" 这一段，剩下的前缀（含 v1）原样保留。
        // 注意不能用 substringBeforeLast('/')——那只会去掉 "completions"，
        // 拼出来是 /v1/chat/models。
        val prefix = r.chatCompletionsPath.removeSuffix("chat/completions").trimEnd('/')
        val path = if (prefix.isEmpty()) "models" else "$prefix/models"
        return r.baseUrl.trimEnd('/') + "/" + path
    }

    /**
     * 给用户看的「请求会打到哪」提示。
     *
     * 设置页把它显示出来，用户能立刻发现自己填错了，不用等发消息才看到 404。
     */
    fun previewPath(rawUrl: String): String {
        val r = resolve(rawUrl)
        if (r.baseUrl.isBlank()) return "（未填写地址）"
        return r.baseUrl + "/" + r.chatCompletionsPath
    }
}
