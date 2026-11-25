package cn.cloudbuddy.app.data

import org.json.JSONObject

/**
 * 从 ACP 的 mcpServers 描述里读出「怎么连」。
 *
 * 单独拆出来是为了能单测：真连一次 MCP 服务器要外部依赖，而这段判断（url 还是 command、
 * SSE 还是 streamable HTTP、头怎么带）错了，表现是「连上了但调不了工具」这种很难查的现象。
 */
internal object McpEndpoint {

    data class Config(
        val name: String,
        val url: String,
        /** true 表示走老的 SSE 传输，false 表示 streamable HTTP。 */
        val sse: Boolean,
        val headers: Map<String, String>,
    )

    /**
     * 认不出来（command 型的 stdio、缺 url、JSON 坏了）返回 null。
     *
     * SSE 的判定给两个来源：显式 `type`，以及 url 以 `/sse` 结尾——后者是社区最常见的
     * 写法，很多人复制粘贴时不带 type。
     */
    fun parse(server: McpServer): Config? {
        val o: JSONObject = server.toJson() ?: return null
        val url = o.optString("url").trim()
        if (url.isBlank()) return null
        val type = o.optString("type").lowercase()
        val sse = type == "sse" || url.trimEnd('/').endsWith("/sse")
        val headers = LinkedHashMap<String, String>()
        val h = o.optJSONObject("headers")
        if (h != null) {
            for (key in h.keys()) {
                val value = h.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
            }
        }
        return Config(server.name.ifBlank { url }, url, sse, headers)
    }
}
