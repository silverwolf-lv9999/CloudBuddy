package cn.cloudbuddy.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条 MCP 服务器配置。
 *
 * [json] 存的是**原样的 ACP 描述**（`{"name":..,"command":..,"args":[..],"env":{..}}`
 * 或 `{"name":..,"url":..}`），不做字段改写：ACP 的 `mcpServers` 就是这个形状，
 * 拆成字段再拼回去只会把官方后来加的键吃掉。
 */
data class McpServer(
    val id: String,
    val name: String,
    val raw: String,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    /** 一行摘要：命令行式显示 `npx -y xxx`，HTTP 式显示 url。 */
    val summary: String
        get() {
            val o = toJson() ?: return raw
            val command = o.optString("command")
            if (command.isNotBlank()) {
                val args = o.optJSONArray("args")
                val list = buildList {
                    add(command)
                    if (args != null) for (i in 0 until args.length()) add(args.optString(i))
                }
                return list.joinToString(" ")
            }
            return o.optString("url").ifBlank { raw }
        }
}

/**
 * 本机的 MCP 服务器表。
 *
 * 这里必须说清楚一件事：**官方云端没有 MCP 管理接口**。web 端的
 * `getMcpServers` / `toggleMcpServer` 走的是本地 agent 适配器（桌面端读
 * `~/.codebuddy` 下的配置），云端沙箱里既没有可写的 REST 端点，也没有可浏览的
 * 配置路径对外暴露。客户端能做的只有 ACP 协议本身提供的那条通道：
 * 在 `session/new` / `session/load` 的 `mcpServers` 字段里下发。
 *
 * 所以这里的开关是「这一条要不要下发给会话」，不是「云端账号里的 MCP 列表」。
 * 服务端如果自己又推了 `_codebuddy.ai/mcp_servers_changed`，那条消息会以
 * 只读形式出现在同一张卡片里，一眼能看出两边不一致。
 */
class McpStore(private val prefs: SharedPreferences) {

    fun list(): List<McpServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            McpServer(
                id = id,
                name = o.optString("name"),
                raw = o.optString("raw"),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

    /**
     * 新增或更新。返回解析失败的提示，成功为 null。
     *
     * [id] 为空表示新增；粘贴的 JSON 至少要有 `name`，否则服务端会忽略这一条，
     * 而用户只会看到「加了但没生效」。
     */
    fun upsert(raw: String, id: String? = null): String? {
        val parsed = runCatching { JSONObject(raw.trim()) }.getOrNull()
            ?: return "不是合法的 JSON"
        val name = parsed.optString("name").ifBlank { parsed.optString("id") }
        if (name.isBlank()) return "缺少 name 字段"
        if (!parsed.has("command") && !parsed.has("url")) {
            return "需要 command（本地进程）或 url（远程服务）之一"
        }
        val target = id?.takeIf { it.isNotBlank() } ?: ("m" + System.currentTimeMillis().toString(36))
        val next = list().toMutableList()
        val index = next.indexOfFirst { it.id == target }
        val item = McpServer(target, name, parsed.toString(), next.getOrNull(index)?.enabled ?: true)
        if (index >= 0) next[index] = item else next += item
        save(next)
        return null
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    /** 下发给 ACP 的 `mcpServers`：只带启用中且能解析的那些。 */
    fun payload(): JSONArray {
        val array = JSONArray()
        list().filter { it.enabled }.forEach { server ->
            val json = server.toJson() ?: return@forEach
            // 客户端自己的 id 不往协议里塞，避免污染服务端配置。
            json.remove("id")
            array.put(json)
        }
        return array
    }

    private fun save(list: List<McpServer>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("raw", it.raw)
                    .put("enabled", it.enabled)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "mcp_servers_v1"
    }
}
