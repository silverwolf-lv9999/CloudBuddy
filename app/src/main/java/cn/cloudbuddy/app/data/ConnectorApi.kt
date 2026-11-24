package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 账号里的 MCP 连接器（Connector）。
 *
 * 这是**账号级、云端存的** MCP 配置，和本机那份 [McpStore] 是两回事：
 * 官方安卓端 / 桌面端 / web 端打开同一个连接器，另外两端也跟着变，
 * 因为它落在 `/console/as/connector/user/` 这条记录里，不落在任何一个客户端。
 *
 * 字段对应官方 web 的 `connect_status` / `active_status` 两个标记：
 *
 * - [bound]（`connect_status=1`）授权已经绑到这个账号上；
 * - [active]（`active_status=1`）在会话里启用。绑定但没启用，沙箱里的 Agent 看不到；
 * - [authOk] 授权本身还有效。`connect_status` 只代表「绑过」，token 过期后仍然是 1，
 *   要另外查一次 `oauth/auth-status` 才知道。三者都满足才算真的可用。
 */
data class Connector(
    val name: String,
    val title: String,
    val description: String = "",
    val bound: Boolean = false,
    val active: Boolean = false,
    val authOk: Boolean = false,
    val authType: String? = null,
    val repos: List<String> = emptyList()
) {
    /** 已绑定、已启用、授权有效——沙箱里的 Agent 才会真的拿到这些工具。 */
    val ready: Boolean get() = bound && active && authOk

    /** 绑过但授权过期：界面提示「重新授权」，而不是让用户以为开关坏了。 */
    val needsAuth: Boolean get() = bound && !authOk

    val subtitle: String
        get() = when {
            !bound -> "未连接"
            !authOk -> "授权已失效"
            !active -> "已连接 · 未启用"
            else -> "已连接 · 已启用"
        }
}

/** 一次授权流程的下一步动作，对应官方 `next_action`。 */
data class ConnectorAuth(
    /** `redirect` 开浏览器 / `show_form` 填 token / `poll` 输设备码 / `connected` 已经好了。 */
    val action: String,
    val url: String? = null,
    val verificationUri: String? = null,
    val userCode: String? = null
)

/**
 * 连接器接口。全部路径取自官方 web 端打包产物里的常量拼接，没有公开文档：
 *
 * - 我的 `GET    /console/as/connector/user/` → `data.connectors[]`
 * - 市场 `GET    /console/as/connector/registry2c/list?scope=all&mcp_only=true`
 * - 启用 `PATCH  /console/as/connector/user/{name}/active_status  {name, active_status}`
 * - 解绑 `DELETE /console/as/connector/user/{name}/`
 * - 授权 `POST   /console/as/connector/oauth/{name}/start` → `next_action`
 * - 换码 `POST   /console/as/connector/oauth/{name}/connect     {authorization_code}`
 * - 状态 `GET    /console/as/connector/oauth/{name}/status`
 * - 批量 `POST   /console/as/connector/oauth/auth-status       {names:[]}`
 * - 撤销 `POST   /console/as/connector/oauth/{name}/revoke`
 *
 * 名字统一走 [enc]：连接器 name 是 slug，但仍按 web 的做法编码一次，
 * 免得某个带斜杠的名字把路径切坏。
 */
class ConnectorApi(private val http: CloudHttp) {

    suspend fun mine(): List<Connector> = withContext(Dispatchers.IO) {
        val items = parseConnectors(http.call("GET", PATH_USER))
        val statuses = runCatching { authStatuses(items.map { it.name }) }.getOrDefault(emptyMap())
        items.map { it.copy(authOk = if (it.bound) statuses[it.name] ?: true else false) }
    }

    /** 可安装的连接器清单（市场）。拿不到就返回空表，界面显示「空」而不是报错。 */
    suspend fun catalog(): List<Connector> = withContext(Dispatchers.IO) {
        parseConnectors(http.call("GET", PATH_REGISTRY), catalog = true)
    }

    suspend fun setActive(name: String, active: Boolean) = withContext(Dispatchers.IO) {
        http.call(
            "PATCH",
            "$PATH_USER${enc(name)}/active_status",
            JSONObject().put("name", name).put("active_status", if (active) 1 else 0)
        )
        Unit
    }

    suspend fun remove(name: String) = withContext(Dispatchers.IO) {
        http.call("DELETE", "$PATH_USER${enc(name)}/")
        Unit
    }

    suspend fun startAuth(name: String): ConnectorAuth = withContext(Dispatchers.IO) {
        parseAuth(http.call("POST", "$PATH_OAUTH${enc(name)}/start", JSONObject()))
    }

    /** 单 token 的授权表单：官方对 `{token}` 这种单字段表单发的就是 `authorization_code`。 */
    suspend fun submitToken(name: String, token: String) = withContext(Dispatchers.IO) {
        http.call(
            "POST",
            "$PATH_OAUTH${enc(name)}/connect",
            JSONObject().put("authorization_code", token.trim())
        )
        Unit
    }

    suspend fun status(name: String): Boolean = withContext(Dispatchers.IO) {
        val data = http.data(http.call("GET", "$PATH_OAUTH${enc(name)}/status"))
        data.optString("status") == "connected"
    }

    suspend fun authStatuses(names: List<String>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            if (names.isEmpty()) return@withContext emptyMap()
            parseAuthStatuses(
                http.call("POST", "$PATH_AUTH_STATUS", JSONObject().put("names", JSONArray(names)))
            )
        }

    private fun enc(name: String): String = URLEncoder.encode(name, "UTF-8")

    private companion object {
        const val PATH_USER = "/console/as/connector/user/"
        const val PATH_OAUTH = "/console/as/connector/oauth/"
        const val PATH_REGISTRY = "/console/as/connector/registry2c/list?scope=all&mcp_only=true"
        const val PATH_AUTH_STATUS = "/console/as/connector/oauth/auth-status"
    }
}

// ---------- 解析（纯函数，单测覆盖） ----------

/** `user/` 给 `data.connectors[]`，`registry2c` 给 `data.list[]`，两种节点形状略有差异。 */
internal fun parseConnectors(root: JSONObject, catalog: Boolean = false): List<Connector> {
    val data = root.optJSONObject("data") ?: root
    val array = data.optJSONArray(if (catalog) "list" else "connectors")
        ?: data.optJSONObject("data")?.optJSONArray(if (catalog) "list" else "connectors")
        ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        parseConnector(array.optJSONObject(index), catalog)
    }
}

internal fun parseConnector(o: JSONObject?, catalog: Boolean): Connector? {
    if (o == null) return null
    val name = o.optString("name").ifBlank { o.optString("id") }
    if (name.isBlank()) return null
    val extra = o.optJSONObject("data") ?: JSONObject()
    val title = sequenceOf(
        extra.optString("display_name"),
        extra.optString("display_name_zh"),
        o.optString("display_name"),
        o.optString("title"),
        name
    ).firstOrNull { it.isNotBlank() } ?: name
    val description = sequenceOf(
        extra.optString("description"),
        o.optString("description")
    ).firstOrNull { it.isNotBlank() }.orEmpty()
    // 市场清单里没有 connect_status，只有 status；这两种标记不能混着取，
    // 否则「市场上可安装」会被误判成「已经绑过」。
    val bound = if (catalog) false else o.optInt("connect_status") == 1
    val active = !catalog && o.optInt("active_status") == 1
    val status = o.optString("status")
    if (catalog && status == "disabled") return null
    return Connector(
        name = name,
        title = title,
        description = description,
        bound = bound,
        active = active,
        authOk = bound,
        authType = o.optString("auth_type").takeIf { it.isNotBlank() && it != "null" },
        repos = parseRepos(o.opt("repos"))
    )
}

private fun parseRepos(raw: Any?): List<String> = when (raw) {
    is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf(String::isNotBlank) }
    is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

internal fun parseAuth(root: JSONObject): ConnectorAuth {
    val data = root.optJSONObject("data") ?: root
    val inner = data.optJSONObject("data") ?: data
    val url = sequenceOf(inner.optString("authorize_url"), inner.optString("authorizeUrl"))
        .firstOrNull { it.isNotBlank() }
    val action = sequenceOf(
        inner.optString("next_action"),
        inner.optString("nextAction"),
        data.optString("next_action")
    ).firstOrNull { it.isNotBlank() } ?: if (url != null) "redirect" else "connected"
    return ConnectorAuth(
        action = action,
        url = url,
        verificationUri = sequenceOf(
            inner.optString("verification_uri"),
            inner.optString("verificationUri")
        ).firstOrNull { it.isNotBlank() },
        userCode = sequenceOf(inner.optString("user_code"), inner.optString("userCode"))
            .firstOrNull { it.isNotBlank() }
    )
}

/** `data.statuses[name].status == "connected"`，其余一律按未授权。 */
internal fun parseAuthStatuses(root: JSONObject): Map<String, Boolean> {
    val data = root.optJSONObject("data") ?: root
    val statuses = data.optJSONObject("statuses") ?: return emptyMap()
    val map = mutableMapOf<String, Boolean>()
    statuses.keys().forEach { name ->
        map[name] = statuses.optJSONObject(name)?.optString("status") == "connected"
    }
    return map
}
