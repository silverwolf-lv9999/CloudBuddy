package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号的技能（Skill）。
 *
 * 两类来源混在一个列表里：
 *
 * - `market` / `skillhub`：从技能市场装的，云端有资产记录，可启停、可卸载；
 * - `custom`：用户自己上传的，走 `/v2/user-asset/custom-skills`。
 *
 * 官方 web 端把「列表」和「启停开关」分成两个接口：`get-list` 给资产清单，
 * `assets/configuration` 给每个资产的 enabled 状态。少查一个就会出现
 * 「明明关掉了还显示启用」。
 */
data class SkillInfo(
    val id: String,
    val name: String,
    val title: String,
    val description: String = "",
    val source: String = "",
    val version: String? = null,
    val enabled: Boolean = true,
    val status: String? = null,
    /** 已安装与市场搜索共用这个结构，[installed] 用来区分按钮语义。 */
    val installed: Boolean = true
)

/**
 * 技能接口。路径取自官方 web 端打包产物（`main-*.js`）里的常量：
 *
 * - 清单 `GET /v2/user-asset/skill/get-list?page_no=&page_size=`
 * - 启停 `GET|POST /v2/user-asset/assets/configuration?entity_type=skill`
 * - 安装 `POST /v2/user-asset/skill/install  {items:[{source,asset_id,version}]}`
 * - 市场 `POST /portal/enterprises/{id}/skill-market/list?source=builtin&...`（见下方说明）
 *
 * 这些接口没有公开文档，字段按 web 端的解析函数还原；解析一律取多个候选键，
 * 少一个字段只会少显示一行，不会整页报错。
 */
class SkillApi(private val http: CloudHttp, private val settings: AppSettings) {

    suspend fun installed(): List<SkillInfo> = withContext(Dispatchers.IO) {
        val list = runCatching {
            http.call("GET", "$PATH_LIST?page_no=1&page_size=$PAGE_SIZE")
        }.getOrElse { throw it }
        val items = list.optJSONArray("items")
            ?: list.optJSONObject("data")?.optJSONArray("items")
            ?: JSONArray()
        val parsed = (0 until items.length()).mapNotNull { parseSkill(items.optJSONObject(it)) }
        val toggles = runCatching { configurations() }.getOrDefault(emptyMap())
        parsed.map { skill ->
            skill.copy(enabled = toggles[skill.id] ?: skill.enabled)
        }
    }

    /** 每个资产的启用状态；拿不到就返回空表（列表里按「启用」显示）。 */
    suspend fun configurations(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val data = http.call("GET", "$PATH_CONFIG?entity_type=skill&page_no=1&page_size=$PAGE_SIZE")
        val items = data.optJSONArray("items")
            ?: data.optJSONObject("data")?.optJSONArray("items")
            ?: return@withContext emptyMap()
        val map = mutableMapOf<String, Boolean>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("asset_id").ifBlank { o.optString("assetId") }
            if (id.isBlank()) continue
            map[id] = o.optBoolean("enabled", true)
        }
        map
    }

    /**
     * 批量启停。返回失败原因（成功时为空）。
     *
     * 云端是「部分成功」语义：`succeeded` 和 `failed` 同时返回，不能只看 HTTP 200。
     */
    suspend fun setEnabled(skills: List<SkillInfo>, enabled: Boolean): List<String> =
        withContext(Dispatchers.IO) {
            val items = JSONArray()
            skills.forEach {
                items.put(
                    JSONObject()
                        .put("source", it.source.ifBlank { "market" })
                        .put("asset_id", it.id)
                        .put("version", it.version.orEmpty())
                )
            }
            val body = JSONObject()
                .put("entity_type", "skill")
                .put("action", if (enabled) "enable" else "disable")
                .put("items", items)
            val data = http.call("POST", PATH_CONFIG, body)
            val failed = data.optJSONArray("failed") ?: JSONArray()
            (0 until failed.length()).mapNotNull { i ->
                val o = failed.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("asset_id").ifBlank { o.optString("assetId") }
                val reason = o.optString("reason").ifBlank { "云端拒绝" }
                skills.firstOrNull { it.id == id }?.title?.let { "$it：$reason" } ?: reason
            }
        }

    /**
     * 技能市场搜索。
     *
     * 2026-11-24 实测：`internal_knot-skill` 这条对当前账号直接回
     * `code=17012 knot user identity unavailable`（HTTP 403），市面上的旧路径不能用。
     * 官方 web 默认走企业聚合接口 `POST /portal/enterprises/{id}/skill-market/list`，
     * 参数在 query 里，个人账号的 id 就是字面量 `personal`。这条路返回 7 万多条技能，
     * 搜索词用 `keyword`（不是 `search_keyword`）。
     */
    suspend fun market(keyword: String = "", page: Int = 1): List<SkillInfo> =
        withContext(Dispatchers.IO) {
            val enterprise = settings.enterpriseId.ifBlank { AppSettings.ENTERPRISE_PERSONAL }
            val query = buildString {
                append("/portal/enterprises/")
                append(java.net.URLEncoder.encode(enterprise, "UTF-8"))
                append("/skill-market/list?source=builtin&page=").append(page)
                append("&page_size=").append(MARKET_PAGE_SIZE)
                if (keyword.isNotBlank()) {
                    append("&keyword=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
                }
            }
            // 参数全在 query，不传 body：CloudHttp.bodyFor 会给 POST 补一个长度为 0
            // 的 RequestBody。OkHttp 不允许 POST 带 null body，本地就会抛异常。
            parseMarketItems(http.call("POST", query))
        }

    /** 从市场安装一个技能。 */
    suspend fun install(assetId: String, version: String?) = withContext(Dispatchers.IO) {
        val item = JSONObject()
            .put("source", "market")
            .put("asset_id", assetId)
            .put("version", version.orEmpty())
        http.call("POST", PATH_INSTALL, JSONObject().put("items", JSONArray().put(item)))
        Unit
    }

    private fun parseSkill(o: JSONObject?): SkillInfo? {
        if (o == null) return null
        val id = sequenceOf(
            o.optString("skill_id"),
            o.optString("id"),
            o.optString("asset_id")
        ).firstOrNull { it.isNotBlank() } ?: return null
        val name = sequenceOf(o.optString("name"), o.optString("slug"), id)
            .firstOrNull { it.isNotBlank() } ?: id
        val title = sequenceOf(
            o.optString("display_name_zh"),
            o.optString("display_name"),
            o.optString("display_name_en"),
            o.optString("title"),
            name
        ).firstOrNull { it.isNotBlank() } ?: name
        val description = sequenceOf(
            o.optString("description_zh"),
            o.optString("description"),
            o.optString("description_en"),
            o.optString("summary")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val enabled = when (val raw = o.opt("enabled")) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            else -> o.optString("status").uppercase() !in DISABLED_STATUS
        }
        return SkillInfo(
            id = id,
            name = name,
            title = title,
            description = description,
            source = o.optString("source").ifBlank { "custom" },
            version = o.optString("version").takeIf { it.isNotBlank() && it != "null" },
            enabled = enabled,
            status = o.optString("status").takeIf { it.isNotBlank() && it != "null" },
            installed = true
        )
    }

    private companion object {
        const val PATH_LIST = "/v2/user-asset/skill/get-list"
        const val PATH_CONFIG = "/v2/user-asset/assets/configuration"
        const val PATH_INSTALL = "/v2/user-asset/skill/install"
        const val PATH_MARKET_KNOT = "/console/agent-gateway/internal_knot-skill/apigw/api/v1/skills/list"
        const val PAGE_SIZE = 100
        const val MARKET_PAGE_SIZE = 20
        val DISABLED_STATUS = setOf("DISABLED", "OFFLINE", "DELETED", "REJECTED")
    }
}

/** 企业聚合市场的一条：`data.items[]`，字段名与 `get-list` 那套不同，别复用类里的解析。 */
internal fun parseMarketItems(root: JSONObject): List<SkillInfo> {
    val data = root.optJSONObject("data") ?: root
    val items = data.optJSONArray("items") ?: data.optJSONArray("skills") ?: return emptyList()
    return (0 until items.length()).mapNotNull { i ->
        val o = items.optJSONObject(i) ?: return@mapNotNull null
        val id = sequenceOf(o.optString("skill_id"), o.optString("item_ref"), o.optString("id"), o.optString("slug"))
            .firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
        SkillInfo(
            id = id,
            name = o.optString("name").ifBlank { id },
            title = sequenceOf(
                o.optString("display_name_zh"),
                o.optString("display_name"),
                o.optString("display_name_en"),
                o.optString("title")
            ).firstOrNull { it.isNotBlank() } ?: id,
            description = sequenceOf(
                o.optString("description_zh"),
                o.optString("description"),
                o.optString("description_en")
            ).firstOrNull { it.isNotBlank() }.orEmpty(),
            source = o.optString("source").ifBlank { "market" },
            version = o.optString("version").takeIf { it.isNotBlank() && it != "null" },
            status = o.optString("category_name").takeIf { it.isNotBlank() },
            installed = false
        )
    }
}
