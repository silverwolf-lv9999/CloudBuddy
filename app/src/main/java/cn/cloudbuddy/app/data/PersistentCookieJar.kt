package cn.cloudbuddy.app.data

import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * 浏览器式 cookie 罐：登录、重定向、业务接口共用同一份存储。
 *
 * 用 SharedPreferences 存 JSON，而不是 CookieManager，原因是我们要在原生 HTTP
 * 请求里精确控制每一跳的 Cookie 头（登录链路会跨 console / auth-v2 / SSO 多个路径）。
 *
 * 多账号下这里按「槽」隔离：每个账号一份 `cookies_v1:<账号 id>`。切账号只换
 * [switchTo] 的目标槽，OkHttp 持有的这个实例不变——重建 jar 会连带重建
 * OkHttpClient，正在跑的请求全部作废。
 */
class PersistentCookieJar(private val prefs: SharedPreferences) : CookieJar {

    private val lock = Any()
    private val store = mutableListOf<Cookie>()

    @Volatile
    private var slot: String = LEGACY_SLOT

    /**
     * 切到另一个账号的 cookie 槽。
     *
     * [carryOver] 为 true 时把当前内存里的 cookie 先写进目标槽再切——用于
     * 「登录过程中先写进 guest 槽，登录成功后接管给新账号」这一条路径。
     */
    fun switchTo(target: String, carryOver: Boolean = false) = synchronized(lock) {
        if (target == slot && !carryOver) return@synchronized
        if (carryOver) persistLocked(target) else persistLocked(slot)
        slot = target
        store.clear()
        loadLocked(target)
    }

    /** 当前槽名，测试与诊断用。 */
    fun currentSlot(): String = slot

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt < now }
            for (cookie in cookies) {
                store.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                if (cookie.expiresAt > now) store.add(cookie)
            }
            persistLocked(slot)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val matched = store.filter { it.expiresAt > now && it.matches(url) }
            return if (matched.isEmpty()) emptyList() else matched
        }
    }

    fun hasAny(): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        store.any { it.expiresAt > now }
    }

    /** 只丢弃网关会话（session / session_2），保留 Keycloak SSO。 */
    fun dropGatewaySession() = synchronized(lock) {
        store.removeAll { it.name == "session" || it.name == "session_2" }
        persistLocked(slot)
    }

    /** 清空当前账号的 cookie。 */
    fun clear() = synchronized(lock) {
        store.clear()
        prefs.edit().remove(KEY_PREFIX + slot).apply()
    }

    /** 删除某个槽（账号被移除时调用，当前槽顺带清空内存）。 */
    fun clearSlot(target: String) = synchronized(lock) {
        prefs.edit().remove(KEY_PREFIX + target).apply()
        if (target == slot) store.clear()
    }

    private fun persistLocked(target: String) {
        if (store.isEmpty() && target != slot) return
        val array = JSONArray()
        store.forEach { c ->
            array.put(
                JSONObject()
                    .put("name", c.name)
                    .put("value", c.value)
                    .put("domain", c.domain)
                    .put("path", c.path)
                    .put("expires", c.expiresAt)
                    .put("secure", c.secure)
                    .put("httpOnly", c.httpOnly)
                    .put("hostOnly", c.hostOnly)
            )
        }
        prefs.edit().putString(KEY_PREFIX + target, array.toString()).apply()
    }

    private fun loadLocked(target: String) {
        val raw = prefs.getString(KEY_PREFIX + target, null) ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val builder = runCatching {
                Cookie.Builder()
                    .name(o.optString("name"))
                    .value(o.optString("value"))
                    .path(o.optString("path", "/"))
                    .expiresAt(o.optLong("expires"))
                    .apply {
                        val domain = o.optString("domain")
                        if (o.optBoolean("hostOnly")) hostOnlyDomain(domain) else domain(domain)
                        if (o.optBoolean("secure")) secure()
                        if (o.optBoolean("httpOnly")) httpOnly()
                    }
            }.getOrNull() ?: continue
            store.add(builder.build())
        }
    }

    companion object {
        /** 单账号版本的 key，迁移时读一次。 */
        const val KEY_LEGACY = "cookies_v1"
        const val KEY_PREFIX = "cookies_v1:"

        /** 迁移与「还没建账号」时用的槽名。 */
        private const val LEGACY_SLOT = "legacy"
    }
}
