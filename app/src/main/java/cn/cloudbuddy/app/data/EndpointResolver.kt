package cn.cloudbuddy.app.data

/**
 * OpenAI 兼容端点的地址归一化。
 *
 * 这里处理的是一个**必然会被踩到**的问题：各家服务的"地址"填法并不统一。
 *
 * | 用户填写 | 期望请求 | 直接交给 Koog 的结果 |
 * | --- | --- | --- |
 * | `https://api.deepseek.com` | `/v1/chat/completions` | 对 |
 * | `https://api.deepseek.com/v1` | `/v1/chat/completions` | `/chat/completions` → **404** |
 * | `https://x.com/v1/chat/completions` | `/v1/chat/completions` | 路径重复 → **404** |
 *
 * 用户照 OpenAI 的习惯填 `/v1` 是最常见的做法（官方文档、各种教程都这么写），
 * 所以不能要求用户去理解路径拼接规则，必须自己吃掉这层差异。
 * 拼接的真实规则见 [resolve]：**base 只留 origin，路径全部放进 path**。
 *
 * 拆成纯函数是为了能单测：这条逻辑错了整个功能就用不了，而它完全不需要网络。
 */
object EndpointResolver {

    /** 归一化结果：给 Koog 用的 base 与相对路径。 */
    data class Resolved(
        /** 只含 `scheme://host:port`，不含任何路径段（见 [resolve] 里 ktor 的拼接规则）。 */
        val baseUrl: String,
        /** 相对 [baseUrl] 的路径，如 `v1/chat/completions`。 */
        val chatCompletionsPath: String,
    )

    const val DEFAULT_PATH = "v1/chat/completions"

    /**
     * 拆 `scheme://host[:port]` 与它后面的路径。
     *
     * 只认 http(s)：填成 `api.deepseek.com`（少 scheme）或本地 socket 路径时整体透传，
     * 让下游报「地址不对」，而不是在这里被改写成一个更难认的地址。
     */
    private val ORIGIN = Regex("^(https?://[^/]+)(/.*)?$", RegexOption.IGNORE_CASE)

    /**
     * 把用户填的地址拆成「根地址 + 路径」。
     *
     * **根地址只保留 scheme://host:port，路径前缀全部挪进 [Resolved.chatCompletionsPath]。**
     * 这不是审美选择，是 Koog 0.6.0 + ktor 的真实行为：请求 path 是**相对路径**，
     * ktor 解析时会替换掉 base 的最后一段。所以 `baseUrl=.../v1` 配
     * `path=chat/completions` 打出去的是 `/chat/completions`（`/v1` 被吃掉），
     * 端点回一句 404 "Not found"。也就是说「按 OpenAI 习惯填 /v1」这个最常见的
     * 填法，以前是**连不上**的——模型列表能拉（那条请求是我们自己拼的完整 URL），
     * 一发消息就 404，用户只会以为自己 key 填错了。
     *
     * 规则按优先级：
    1. 去掉首尾空白与尾部斜杠；
    2. 已经带有 `/chat/completions`（用户直接贴了完整端点）→ 剥掉；
    3. 拆 origin 与剩下的路径前缀；
    4. 前缀为空 → 按缺省 `v1/chat/completions`；否则前缀 + `/chat/completions`。
     */
    fun resolve(rawUrl: String): Resolved {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return Resolved("", DEFAULT_PATH)

        val completionsMarker = "/chat/completions"
        // 用户贴了完整端点：把这一段剥掉，剩下的都算前缀。
        val rest = if (trimmed.lowercase().endsWith(completionsMarker)) {
            trimmed.dropLast(completionsMarker.length).trimEnd('/')
        } else {
            trimmed
        }

        val match = ORIGIN.find(rest)
            // 不像 http(s) 地址（只填了主机名之类）：整体当 base，让下游去报错，
            // 硬拆只会把用户填错的地址改成一个更难认的地址。
            ?: return Resolved(rest, DEFAULT_PATH)
        val origin = match.groupValues[1]
        val prefix = match.groupValues[2].trim('/')
        val path = if (prefix.isEmpty()) DEFAULT_PATH else "$prefix/chat/completions"
        return Resolved(origin, path)
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
