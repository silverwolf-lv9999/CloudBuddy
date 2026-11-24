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
 * - 市场 `GET /console/agent-gateway/internal_knot-skill/apigw/api/v1/skills/list`
 *
 * 这些接口没有公开文档，字段按 web 端的解析函数还原；解析一律取多个候选键，
 * 少一个字段只会少显示一行，不会整页报错。
 */
class SkillApi(private val http: CloudHttp) {

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

    /** 技能市场搜索。空关键词时返回下载量最高的若干条。 */
    suspend fun market(keyword: String = "", page: Int = 1): List<SkillInfo> =
        withContext(Dispatchers.IO) {
            val query = buildString {
                append(PATH_MARKET)
                append("?page=").append(page)
                append("&page_size=").append(PAGE_SIZE)
                append("&order_by=download_count")
                if (keyword.isNotBlank()) append("&search_keyword=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
            }
            val data = http.call("GET", query)
            val skills = data.optJSONArray("skills")
                ?: data.optJSONObject("data")?.optJSONArray("skills")
                ?: JSONArray()
            (0 until skills.length()).mapNotNull { i ->
                val o = skills.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("slug").ifBlank { o.optString("id") }
                if (id.isBlank()) return@mapNotNull null
                SkillInfo(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    title = o.optString("display_name")
                        .ifBlank { o.optString("displayName") }
                        .ifBlank { o.optString("name") }
                        .ifBlank { id },
                    description = o.optString("description").ifBlank { o.optString("summary") },
                    source = "market",
                    version = o.optString("version").takeIf { it.isNotBlank() },
                    status = o.optString("category").takeIf { it.isNotBlank() },
                    installed = false
                )
            }
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
        const val PATH_MARKET = "/console/agent-gateway/internal_knot-skill/apigw/api/v1/skills/list"
        const val PAGE_SIZE = 100
        val DISABLED_STATUS = setOf("DISABLED", "OFFLINE", "DELETED", "REJECTED")
    }
}
