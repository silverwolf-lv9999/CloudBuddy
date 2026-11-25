package cn.cloudbuddy.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本机偏好、账号表与登录态。
 *
 * 这个客户端不走「把 WorkBuddy 当成本地模型 API」的反代路线：它保留官方的
 * 云端会话（E2B 沙箱）语义，因此凭据必须是浏览器式的一整套 cookie
 * （网关 session + Keycloak SSO），由 [PersistentCookieJar] 统一保管。
 *
 * 多账号的实现方式：
 *
 * - 账号元数据（昵称、手机号、uid、企业 ID）存在 `accounts_v2` 这条 JSON 里；
 * - **每样跟账号绑定的值都带 `a:<账号 id>:` 前缀**，切账号只是换活跃 id，
 *   不需要搬数据；
 * - cookie 按账号分槽（`cookies_v1:<账号 id>`），所以切回来不必重新收短信。
 *
 * 而主题、折叠方式这类是「这台手机上的偏好」，与账号无关，保持原来的全局 key。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cloudbuddy", Context.MODE_PRIVATE)

    val cookies = PersistentCookieJar(prefs)

    /** 本机 MCP 服务器表（随会话下发，见 [McpStore] 的说明）。 */
    val mcp = McpStore(prefs)

    init {
        migrateSingleAccountIfNeeded()
        cookies.switchTo(cookieSlotFor(activeAccountId))
    }

    // ---------- 账号表 ----------

    /** 已保存的账号，按添加时间升序。 */
    fun accounts(): List<Account> = Account.listOf(prefs.getString(KEY_ACCOUNTS, null))

    /** 当前活跃账号 id；未登录或刚启用多账号时为空白。 */
    var activeAccountId: String
        get() = prefs.getString(KEY_ACTIVE, "").orEmpty()
        private set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    val activeAccount: Account? get() = accounts().firstOrNull { it.id == activeAccountId }

    /** 账号数 ≥ 1 时界面才显示切换入口。 */
    val hasMultipleAccounts: Boolean get() = accounts().size > 1

    /**
     * 切换账号：换活跃 id、换 cookie 槽、清掉与上个账号相关的界面状态。
     *
     * 返回是否真的切了（id 不存在或与当前相同都返回 false），调用方据此决定
     * 要不要重新拉会话列表——没切的话刷新一遍纯属浪费。
     */
    fun switchAccount(id: String): Boolean {
        if (id.isBlank() || id == activeAccountId) return false
        if (accounts().none { it.id == id }) return false
        activeAccountId = id
        cookies.switchTo(cookieSlotFor(id))
        return true
    }

    /**
     * 开一个新的空账号槽，供「添加账号」时先登录再落库。
     *
     * 先建槽而不是登录后再建，是因为登录链路本身就要往 cookie 罐里写东西
     * （Keycloak SSO + 网关 session），必须有一个确定的槽位可写。
     */
    fun beginAccount(baseUrl: String = DEFAULT_BASE_URL): String {
        val id = "a" + System.currentTimeMillis().toString(36)
        val list = accounts() + Account(id = id, baseUrl = baseUrl, addedAt = System.currentTimeMillis())
        prefs.edit().putString(KEY_ACCOUNTS, Account.encode(list)).apply()
        // 首个账号：登录链路已经把 cookie 写进 guest 槽了，接管过来而不是清空。
        val adopting = activeAccountId.isBlank()
        activeAccountId = id
        cookies.switchTo(cookieSlotFor(id), carryOver = adopting)
        return id
    }

    /** 更新账号的展示信息（登录成功、资料刷新都走这里）。 */
    fun updateAccount(
        id: String = activeAccountId,
        hint: String? = null,
        nickname: String? = null,
        avatarUrl: String? = null,
        userId: String? = null,
        enterpriseId: String? = null,
        baseUrl: String? = null
    ) {
        if (id.isBlank()) return
        val list = accounts()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = list[index].copy(
            hint = hint ?: list[index].hint,
            nickname = nickname ?: list[index].nickname,
            avatarUrl = avatarUrl ?: list[index].avatarUrl,
            userId = userId ?: list[index].userId,
            enterpriseId = enterpriseId ?: list[index].enterpriseId,
            baseUrl = baseUrl ?: list[index].baseUrl
        )
        val next = list.toMutableList().also { it[index] = updated }
        prefs.edit().putString(KEY_ACCOUNTS, Account.encode(next)).apply()
    }

    /**
     * 删除一个账号（含它的 cookie 槽）。
     *
     * 删的是当前账号时自动切到剩下的第一个；一个不剩就回到未登录。
     */
    fun removeAccount(id: String) {
        val list = accounts()
        if (list.none { it.id == id }) return
        val remained = list.filterNot { it.id == id }
        prefs.edit().putString(KEY_ACCOUNTS, Account.encode(remained)).apply()
        // 该账号的会话级偏好（上次打开的会话）没有别的账号会用到
        prefs.edit().remove("last_conversation").apply()
        if (id == activeAccountId) {
            val next = remained.firstOrNull()
            activeAccountId = next?.id.orEmpty()
            // 先切走再删槽：反过来的话，切走时会把内存里的空 store 又写回刚删的槽
            cookies.switchTo(cookieSlotFor(next?.id.orEmpty()))
        }
        cookies.clearSlot(cookieSlotFor(id))
    }

    // ---------- 与账号绑定的值 ----------

    fun accountValue(name: String, def: String = ""): String =
        prefs.getString(accountKey(name), def).orEmpty().ifBlank { def }

    fun setAccountValue(name: String, value: String) {
        prefs.edit().putString(accountKey(name), value).apply()
    }

    var loggedIn: Boolean
        get() = prefs.getBoolean(accountKey("logged_in"), false)
        set(value) = prefs.edit().putBoolean(accountKey("logged_in"), value).apply()

    /** 云端站点（console 接口、登录页都在这个域下）。按账号存：企业站点可能不同。 */
    var baseUrl: String
        get() = accountValue("base_url", DEFAULT_BASE_URL).trimEnd('/')
        set(value) = setAccountValue("base_url", value.trim().trimEnd('/'))

    var accountHint: String
        get() = accountValue("account_hint")
        set(value) {
            setAccountValue("account_hint", value.trim())
            updateAccount(hint = value.trim())
        }

    var nickname: String
        get() = accountValue("nickname")
        set(value) {
            setAccountValue("nickname", value.trim())
            updateAccount(nickname = value.trim())
        }

    var avatarUrl: String
        get() = accountValue("avatar_url")
        set(value) {
            setAccountValue("avatar_url", value.trim())
            updateAccount(avatarUrl = value.trim())
        }

    var enterpriseId: String
        get() = accountValue("enterprise_id", ENTERPRISE_PERSONAL)
        set(value) = setAccountValue("enterprise_id", value.trim())

    /** 账号 uid；官方 AccountService 会把它放进 X-User-Id。 */
    var userId: String
        get() = accountValue("user_id")
        set(value) {
            setAccountValue("user_id", value.trim())
            updateAccount(userId = value.trim())
        }

    // ---------- 全局偏好（与账号无关） ----------

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
     * 回车是否直接发送消息。
     *
     * 打开（默认）：软键盘回车触发发送；关闭：回车就是换行，
     * 发送只能点右下角的发送键。写长消息的人会想关掉它。
     */
    var enterSend: Boolean
        get() = prefs.getBoolean("enter_send", true)
        set(value) = prefs.edit().putBoolean("enter_send", value).apply()

    /**
     * 折叠方式：整轮折叠 / 只折思考与工具。
     *
     * 与 [collapseSteps] 是两件事：那个决定「折不折」，这个决定「折什么」。
     * 分成两个设置项，是因为用户常常只想换呈现方式、不想每次都重新决定折不折。
     */
    var foldMode: String
        get() = prefs.getString("fold_mode", FoldMode.DEFAULT).orEmpty()
        set(value) = prefs.edit().putString("fold_mode", value).apply()

    /** 已解析的折叠方式，界面直接用这个，免得每处都 `FoldMode.of(...)`。 */
    val fold: FoldMode get() = FoldMode.of(foldMode)

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

    // ---------- 消息本地缓存 ----------

    /**
     * 读取缓存的消息列表。
     *
     * 打开会话时先显示缓存（立即有内容），后台再从云端拉最新。
     */
    fun loadMessageCache(conversationId: String): List<ChatMessage> {
        val raw = prefs.getString(KEY_MSG_CACHE_PREFIX + conversationId, null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                ChatMessage(
                    id = obj.optString("id"),
                    role = ChatRole.valueOf(obj.optString("role")),
                    text = obj.optString("text"),
                    toolState = ToolState.valueOf(obj.optString("toolState", "NONE")),
                    streaming = false,
                    turn = obj.optInt("turn"),
                    toolName = obj.optString("toolName").takeIf { it.isNotBlank() },
                    toolInput = obj.optString("toolInput").takeIf { it.isNotBlank() },
                    toolOutput = obj.optString("toolOutput").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 保存消息到缓存。
     *
     * 只保留最近 [MSG_CACHE_LIMIT] 条，防止 SharedPreferences 无限膨胀。
     */
    fun saveMessageCache(conversationId: String, messages: List<ChatMessage>) {
        val toSave = messages.takeLast(MSG_CACHE_LIMIT)
        val array = org.json.JSONArray()
        toSave.forEach { msg ->
            val obj = org.json.JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role.name)
                put("text", msg.text)
                put("toolState", msg.toolState.name)
                put("turn", msg.turn)
                msg.toolName?.let { put("toolName", it) }
                msg.toolInput?.let { put("toolInput", it) }
                msg.toolOutput?.let { put("toolOutput", it) }
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_MSG_CACHE_PREFIX + conversationId, array.toString()).apply()
    }

    /** 清除某个会话的消息缓存。 */
    fun clearMessageCache(conversationId: String) {
        prefs.edit().remove(KEY_MSG_CACHE_PREFIX + conversationId).apply()
    }

    /** 网关签发的 session 会与登录时的 UA 绑定，登录与业务请求必须共用同一个值。 */
    val userAgent: String get() = DEFAULT_USER_AGENT

    /**
     * 登录成功：落到活跃账号上；还没有账号就先建一个。
     *
     * 多账号下这个函数不再是「唯一的登录出口」——[beginAccount] 会先建槽，
     * 这里只是把结果补进那条记录。
     */
    fun saveSession(account: String, nickname: String = "", avatarUrl: String = "") {
        if (activeAccountId.isBlank()) beginAccount()
        prefs.edit().putBoolean(accountKey("logged_in"), true).apply()
        this.accountHint = account
        if (nickname.isNotBlank()) this.nickname = nickname
        if (avatarUrl.isNotBlank()) this.avatarUrl = avatarUrl
    }

    /** 退出当前账号：连账号记录一起删掉，避免列表里留一条空壳。 */
    fun signOut() {
        val id = activeAccountId
        if (id.isBlank()) {
            cookies.clear()
            prefs.edit().putBoolean(accountKey("logged_in"), false).apply()
            return
        }
        removeAccount(id)
    }

    /** 网关判定登录失效：只丢网关 session，保留 SSO，续期时不必再收一次短信。 */
    fun markSessionExpired() {
        cookies.dropGatewaySession()
        prefs.edit().putBoolean(accountKey("logged_in"), false).apply()
    }

    // ---------- 内部 ----------

    private fun accountKey(name: String): String = "a:${activeAccountId}:$name"

    private fun cookieSlotFor(accountId: String): String = accountId.ifBlank { GUEST_SLOT }

    /**
     * 从单账号版本迁移。
     *
     * 老版本把登录态直接放在 `logged_in` / `account_hint` / `cookies_v1` 上。
     * 这里一次性搬到 `a1` 这个账号下，并且**只在第一次运行时执行**（用
     * `accounts_v2` 是否存在标记），否则用户手动删光账号后会被旧数据复活一个。
     */
    private fun migrateSingleAccountIfNeeded() {
        if (prefs.contains(KEY_ACCOUNTS)) return
        val legacyLoggedIn = prefs.getBoolean("logged_in", false)
        val legacyCookies = prefs.getString(PersistentCookieJar.KEY_LEGACY, null)
        if (!legacyLoggedIn && legacyCookies.isNullOrBlank()) {
            prefs.edit().putString(KEY_ACCOUNTS, Account.encode(emptyList())).apply()
            return
        }
        val id = "a1"
        val account = Account(
            id = id,
            hint = prefs.getString("account_hint", "").orEmpty(),
            nickname = prefs.getString("nickname", "").orEmpty(),
            avatarUrl = prefs.getString("avatar_url", "").orEmpty(),
            userId = prefs.getString("user_id", "").orEmpty(),
            enterpriseId = prefs.getString("enterprise_id", ENTERPRISE_PERSONAL).orEmpty(),
            baseUrl = prefs.getString("base_url", DEFAULT_BASE_URL).orEmpty(),
            addedAt = System.currentTimeMillis()
        )
        val editor = prefs.edit()
            .putString(KEY_ACCOUNTS, Account.encode(listOf(account)))
            .putString(KEY_ACTIVE, id)
            .putBoolean("a:$id:logged_in", legacyLoggedIn)
            .putString("a:$id:account_hint", account.hint)
            .putString("a:$id:nickname", account.nickname)
            .putString("a:$id:avatar_url", account.avatarUrl)
            .putString("a:$id:user_id", account.userId)
            .putString("a:$id:enterprise_id", account.enterpriseId)
            .putString("a:$id:base_url", account.baseUrl)
        if (!legacyCookies.isNullOrBlank()) {
            editor.putString(PersistentCookieJar.KEY_PREFIX + id, legacyCookies)
            editor.remove(PersistentCookieJar.KEY_LEGACY)
        }
        editor.apply()
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

        private const val KEY_ACCOUNTS = "accounts_v2"
        private const val KEY_ACTIVE = "active_account"

        /** 还没建账号时（登录过程中）临时写进这个槽，登录成功后随账号一起被接管。 */
        private const val GUEST_SLOT = "guest"

        /** 消息缓存 key 前缀，按会话 ID 隔离。 */
        private const val KEY_MSG_CACHE_PREFIX = "msg_cache_v1:"

        /** 每个会话最多缓存的消息条数，防止无限膨胀。 */
        private const val MSG_CACHE_LIMIT = 200
    }
}
