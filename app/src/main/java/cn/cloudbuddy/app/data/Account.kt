package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本机保存的一个账号。
 *
 * 只存展示信息与身份标识，凭据（一整套 cookie）由 [PersistentCookieJar] 按 [id]
 * 分槽保存。两者分开的原因是：cookie 会随续期不断变化，而这里是低频元数据，
 * 混在一起会让「切换账号」变成一次大量读写。
 */
data class Account(
    val id: String,
    /** 登录用的手机号，界面上做脱敏展示。 */
    val hint: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    val userId: String = "",
    val enterpriseId: String = AppSettings.ENTERPRISE_PERSONAL,
    val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
    val addedAt: Long = 0L
) {
    /** 列表里显示的名字：优先昵称，其次脱敏手机号。 */
    val title: String get() = nickname.ifBlank { maskHint(hint).ifBlank { "未命名账号" } }

    /** 副标题：昵称和手机号都在时两个都显示，便于区分同一人的两个号。 */
    val subtitle: String
        get() = when {
            nickname.isNotBlank() && hint.isNotBlank() -> maskHint(hint)
            else -> ""
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hint", hint)
        .put("nickname", nickname)
        .put("avatar", avatarUrl)
        .put("uid", userId)
        .put("enterprise", enterpriseId)
        .put("base", baseUrl)
        .put("added", addedAt)

    companion object {
        fun from(o: JSONObject?): Account? {
            if (o == null) return null
            val id = o.optString("id")
            if (id.isBlank()) return null
            return Account(
                id = id,
                hint = o.optString("hint"),
                nickname = o.optString("nickname"),
                avatarUrl = o.optString("avatar"),
                userId = o.optString("uid"),
                enterpriseId = o.optString("enterprise").ifBlank { AppSettings.ENTERPRISE_PERSONAL },
                baseUrl = o.optString("base").ifBlank { AppSettings.DEFAULT_BASE_URL },
                addedAt = o.optLong("added")
            )
        }

        fun listOf(raw: String?): List<Account> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { from(array.optJSONObject(it)) }
        }

        fun encode(list: List<Account>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        /**
         * 手机号脱敏：139****8888。
         *
         * 登录页让用户输的是完整号码，但账号列表是给旁边的人也能扫一眼看的，
         * 没必要把完整号码贴在设置页上。
         */
        fun maskHint(hint: String): String {
            var digits = hint.filter { it.isDigit() }
            // 带国家码的写法很常见（+86 / 0086），脱敏前先剥掉，否则前三位会变成 861
            if (digits.length > 11) digits = digits.removePrefix("0086").removePrefix("86")
            if (digits.length < 7) return hint
            return digits.take(3) + "****" + digits.takeLast(4)
        }
    }
}
