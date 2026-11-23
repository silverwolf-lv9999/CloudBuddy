package cn.cloudbuddy.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本机偏好与登录态。
 *
 * 这个客户端不走「把 WorkBuddy 当成本地模型 API」的反代路线：它保留官方的
 * 云端会话（E2B 沙箱）语义，因此凭据必须是浏览器式的一整套 cookie
 * （网关 session + Keycloak SSO），由 [PersistentCookieJar] 统一保管。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cloudbuddy", Context.MODE_PRIVATE)

    val cookies = PersistentCookieJar(prefs)

    /** 云端站点（console 接口、登录页都在这个域下）。 */
    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL }
        set(value) = prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply()

    var loggedIn: Boolean
        get() = prefs.getBoolean("logged_in", false)
        set(value) = prefs.edit().putBoolean("logged_in", value).apply()

    var accountHint: String
        get() = prefs.getString("account_hint", "").orEmpty()
        set(value) = prefs.edit().putString("account_hint", value.trim()).apply()

    var nickname: String
        get() = prefs.getString("nickname", "").orEmpty()
        set(value) = prefs.edit().putString("nickname", value.trim()).apply()

    var avatarUrl: String
        get() = prefs.getString("avatar_url", "").orEmpty()
        set(value) = prefs.edit().putString("avatar_url", value.trim()).apply()

    var enterpriseId: String
        get() = prefs.getString("enterprise_id", ENTERPRISE_PERSONAL).orEmpty().ifBlank { ENTERPRISE_PERSONAL }
        set(value) = prefs.edit().putString("enterprise_id", value.trim()).apply()

    /** 账号 uid；官方 AccountService 会把它放进 X-User-Id。 */
    var userId: String
        get() = prefs.getString("user_id", "").orEmpty()
        set(value) = prefs.edit().putString("user_id", value.trim()).apply()

    /** 当前模型；空表示用云端默认模型。 */
    var selectedModel: String
        get() = prefs.getString("model", "").orEmpty()
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", THEME_SYSTEM).orEmpty()
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamic_color", true)
        set(value) = prefs.edit().putBoolean("dynamic_color", value).apply()

    /** 是否默认展开模型思考过程。 */
    var showThoughts: Boolean
        get() = prefs.getBoolean("show_thoughts", false)
        set(value) = prefs.edit().putBoolean("show_thoughts", value).apply()

    /**
     * 中间步骤是否折叠。
     *
     * 这是全局意图而不是「当前这一刻的样子」：打开后连**之后新产生的轮次**也折叠，
     * 用户不必每问一句再手动点一次。单轮的手动展开只在本次会话内有效，不写回这里。
     */
    var collapseSteps: Boolean
        get() = prefs.getBoolean("collapse_steps", true)
        set(value) = prefs.edit().putBoolean("collapse_steps", value).apply()

    /**
     * 对话一次渲染多少轮历史；0 表示全部。
     *
     * 长会话一次性铺开几千条消息，首次布局就要测量全部项，打开会话会明显卡顿。
     * 默认只渲染最近 20 轮，往上按需展开，视觉上等价于「不一次性加载所有消息」。
     */
    var historyTurns: Int
        get() = prefs.getInt("history_turns", 20)
        set(value) = prefs.edit().putInt("history_turns", value.coerceIn(0, 1000)).apply()

    /** 等 `session/load` 响应的上限（秒）。长会话回放慢，默认给到 180 秒。 */
    var historyTimeoutSec: Int
        get() = prefs.getInt("history_timeout_sec", 180)
        set(value) = prefs.edit().putInt("history_timeout_sec", value.coerceIn(30, 600)).apply()

    val historyTimeoutMs: Long get() = historyTimeoutSec.coerceIn(30, 600) * 1000L

    var lastConversation: String
        get() = prefs.getString("last_conversation", "").orEmpty()
        set(value) = prefs.edit().putString("last_conversation", value).apply()

    /** 工作区上次停留的路径（按会话隔离，避免串味）。 */
    fun workspacePath(conversationId: String): String =
        prefs.getString("workspace:$conversationId", "/workspace").orEmpty().ifBlank { "/workspace" }

    fun setWorkspacePath(conversationId: String, path: String) =
        prefs.edit().putString("workspace:$conversationId", path).apply()

    /** 网关签发的 session 会与登录时的 UA 绑定，登录与业务请求必须共用同一个值。 */
    val userAgent: String get() = DEFAULT_USER_AGENT

    fun saveSession(account: String, nickname: String = "") {
        prefs.edit().putBoolean("logged_in", true).putString("account_hint", account).apply()
        if (nickname.isNotBlank()) this.nickname = nickname
    }

    fun signOut() {
        cookies.clear()
        prefs.edit()
            .putBoolean("logged_in", false)
            .putString("account_hint", "")
            .putString("nickname", "")
            .putString("avatar_url", "")
            .apply()
    }

    /** 网关判定登录失效：只丢网关 session，保留 SSO，续期时不必再收一次短信。 */
    fun markSessionExpired() {
        cookies.dropGatewaySession()
        prefs.edit().putBoolean("logged_in", false).apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.workbuddy.cn"
        const val ENTERPRISE_PERSONAL = "personal"

        /** 账号未选择模型时官方端的默认模型。 */
        const val DEFAULT_MODEL = "hy4-preview"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.002) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }
}
