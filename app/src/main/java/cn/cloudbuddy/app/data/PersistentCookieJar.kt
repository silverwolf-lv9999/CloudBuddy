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
 */
class PersistentCookieJar(private val prefs: SharedPreferences) : CookieJar {

    private val lock = Any()
    private val store = mutableListOf<Cookie>()

    init {
        runCatching { load() }
    }

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
            persist()
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
        persist()
    }

    fun clear() = synchronized(lock) {
        store.clear()
        prefs.edit().remove(KEY).apply()
    }

    private fun persist() {
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
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        val array = JSONArray(raw)
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

    private companion object {
        const val KEY = "cookies_v1"
    }
}
