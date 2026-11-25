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

    /** 提示词片段表（编码规范、项目约定这类可复用的补充，见 [PromptPreset]）。 */
    val prompts = PromptStore(prefs)

    /**
     * 本机技能库（`filesDir/skills`）。
     *
     * 和云端技能是两套：云端那批在账号里、由云端 agent 执行；这里只喂给本机会话。
     */
    val localSkills = LocalSkillStore(java.io.File(context.applicationContext.filesDir, "skills"), prefs)

    /**
     * 本机会话的完整消息流（思考、工具调用、回答、失败记录都在里面）。
     *
     * 放在 `filesDir/sessions` 下按会话一个 JSON：内容比偏好项大得多，
     * 塞 SharedPreferences 会让每次 `apply()` 都把整份 XML 重写一遍。
     */
    val sessionStore = SessionStore(java.io.File(context.applicationContext.filesDir, "sessions"))

    /**
     * 自定义模型模式的对话历史。
     *
     * 现在它只是 [sessionStore] 的一个视图（见 [TranscriptMemory]），
     * [AgentMemoryStore] 仅用于把老版本存在 SharedPreferences 里的历史搬过来。
     */
    val agentMemory: AgentMemory = TranscriptMemory(sessionStore, AgentMemoryStore(prefs))

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

    // ---- 自定义模型（OpenAI 兼容端点）----
    //
    // 这两套是并列的，不是替换：ENGINE_WB 走原有云端会话协议（扣积分），
    // ENGINE_CUSTOM 走本机 Koog agent + 用户自己的 API Key（不扣积分、不产生会话记录）。
    // 默认保持 ENGINE_WB，保证升级后行为与之前完全一致。

    /** 当前推理引擎，见 [ENGINE_WB] / [ENGINE_CUSTOM]。 */
    var engineMode: String
        get() = prefs.getString("engine_mode", ENGINE_WB).orEmpty().ifBlank { ENGINE_WB }
        set(value) = prefs.edit().putString("engine_mode", value).apply()

    /** 自定义模型的 baseUrl，如 https://api.deepseek.com 。 */
    var customBaseUrl: String
        get() = prefs.getString("custom_base_url", "").orEmpty()
        set(value) = prefs.edit().putString("custom_base_url", value.trim()).apply()

    /**
     * 自定义模型的 API Key。
     *
     * 只落本机 SharedPreferences，与账号 cookie 同级别；不上传、不写日志、
     * 不进 /workspace（那里和云端 agent 共享，见沙箱安全须知）。
     */
    var customApiKey: String
        get() = prefs.getString("custom_api_key", "").orEmpty()
        set(value) = prefs.edit().putString("custom_api_key", value.trim()).apply()

    /** 自定义模型的模型 ID，如 deepseek-chat 。 */
    var customModelId: String
        get() = prefs.getString("custom_model_id", "").orEmpty()
        set(value) = prefs.edit().putString("custom_model_id", value.trim()).apply()

    /** 采样温度。编码任务偏低更稳，默认 0.3。 */
    var customTemperature: Float
        get() = prefs.getFloat("custom_temperature", 0.3f)
        set(value) = prefs.edit().putFloat("custom_temperature", value.coerceIn(0f, 2f)).apply()

    /** 单轮最多迭代次数（推理 + 工具调用算一轮）。 */
    var customMaxIterations: Int
        get() = prefs.getInt("custom_max_iter", 40)
        set(value) = prefs.edit().putInt("custom_max_iter", value.coerceIn(1, 200)).apply()

    /**
     * 自定义系统提示词。空表示用内置默认（见 SessionAgent 里的 DEFAULT_SYSTEM_PROMPT）。
     *
     * 全局而不是按会话：这是「这个模型该怎么说话、按什么规矩干活」的设定，
     * 跟着账号走更符合直觉；按会话另设一套的话，用户根本记不住哪条是哪套。
     */
    var customSystemPrompt: String
        get() = prefs.getString("custom_system_prompt", "").orEmpty()
        set(value) = prefs.edit().putString("custom_system_prompt", value).apply()

    /**
     * 用户自己维护的模型清单（不是 `GET /models` 的原始回包）。
     *
     * 拉取只是**候选池**：端点返回几十个模型，全塞进选择器里没人找得到，而且
     * 重新拉一次就会把用户改过的显示名、思考参数冲掉。所以分成两层：
     * 候选池（[AppUiState] 里的 customCandidates，只在内存）和
     * 这份「我的模型」。开源客户端（RikkaHub）也是这么分的。
     *
     * 存成 JSON 数组而不是 StringSet：列表顺序有意义（各家都把默认模型放前面），
     * StringSet 会把它打乱。旧版本存纯 ID 字符串数组，见 [CustomModel.decode] 的兼容。
     */
    var customModels: List<CustomModel>
        get() = CustomModel.decode(prefs.getString("custom_models", ""))
        set(value) = prefs.edit().putString("custom_models", CustomModel.encode(value)).apply()

    /** 按 ID 找一条模型配置；没配置过返回 null。 */
    fun customModelEntry(id: String): CustomModel? =
        customModels.firstOrNull { it.id == id }

    /**
     * 按选择键找一条模型配置。
     *
     * 选择键是 `providerId/modelId`（或旧数据的裸 modelId），全局唯一；
     * 裸 ID 先按选择键找，找不到再按纯模型 ID 兜底（旧会话存的就是裸 ID）。
     */
    fun customModelEntryByKey(key: String): CustomModel? =
        customModels.firstOrNull { it.selKey == key }
            ?: customModels.firstOrNull { it.id == key }

    // ---------- 提供商（多端点） ----------
    //
    // 之前只有一个全局 baseUrl+Key，想同时用 DeepSeek 官方和一个中转站就没法配。
    // 提供商成为独立实体后，模型挂在提供商下面，会话按「提供商 + 模型」选。

    var llmProviders: List<LlmProvider>
        get() = LlmProvider.decodeList(prefs.getString("llm_providers", ""))
        set(value) = prefs.edit().putString("llm_providers", LlmProvider.encodeList(value)).apply()

    fun providerOf(id: String): LlmProvider? = llmProviders.firstOrNull { it.id == id }

    fun upsertProvider(provider: LlmProvider) {
        val current = llmProviders.toMutableList()
        val index = current.indexOfFirst { it.id == provider.id }
        if (index >= 0) current[index] = provider else current.add(provider)
        llmProviders = current
    }

    /**
     * 删提供商。它名下的模型一起删（留着也是指着一个不存在的端点），
     * 当前选型指向被删模型时回落到剩下的第一个。
     */
    fun removeProvider(id: String) {
        llmProviders = llmProviders.filterNot { it.id == id }
        val next = customModels.filterNot { it.providerId == id }
        customModels = next
        if (next.none { it.selKey == customModelId }) {
            customModelId = next.firstOrNull()?.selKey.orEmpty()
        }
    }

    /**
     * 旧数据迁移：全局端点（customBaseUrl/customApiKey）搬成第一个提供商，
     * 旗下模型全部挂过去。只在还没有任何提供商时执行一次，幂等。
     *
     * 旧的全局字段不清空：万一迁移有遗漏（比如旧会话存的是裸模型 ID），
     * [customModel] 的解析仍能回落到它们，不至于一下全断。
     */
    fun ensureProvidersMigrated() {
        if (llmProviders.isNotEmpty()) return
        if (customBaseUrl.isBlank() && customApiKey.isBlank()) return
        val provider = LlmProvider(
            id = LlmProvider.newId(),
            name = "默认提供商",
            baseUrl = customBaseUrl,
            apiKey = customApiKey,
        )
        llmProviders = listOf(provider)
        val migrated = customModels.map { m -> if (m.providerId.isBlank()) m.copy(providerId = provider.id) else m }
        customModels = migrated
    }

    /**
     * 加入模型（按选择键去重）。
     *
     * 返回真正新增的条数：调用方据此判断要不要顺手把当前模型也设过去。
     */
    fun addCustomModels(added: List<CustomModel>): Int {
        val current = customModels.toMutableList()
        val known = current.map { it.selKey }.toMutableSet()
        var count = 0
        added.forEach { m ->
            if (m.id.isNotBlank() && known.add(m.selKey)) {
                current.add(m)
                count++
            }
        }
        if (count > 0) customModels = current
        return count
    }

    fun removeCustomModel(key: String) {
        val next = customModels.filterNot { it.selKey == key }
        customModels = next
        // 删掉的正好是当前模型：回落到剩下的第一个，别留一个指向不存在模型的键。
        if (customModelId == key) customModelId = next.firstOrNull()?.selKey.orEmpty()
    }

    /** 覆盖某条模型（显示名、能力、参数）。选择键变了等于换模型，按旧键匹配以免改丢。 */
    fun updateCustomModel(originalKey: String, model: CustomModel) {
        val next = customModels.map { if (it.selKey == originalKey) model else it }
        customModels = next
        if (customModelId == originalKey) customModelId = model.selKey
    }

    /**
     * 思考参数的写法（见 [ThinkingStyle]）。
     *
     * 默认 `auto`：按接口地址猜。猜错只会「没生效」，不会报错，用户可以在设置页改；
     * 而默认死写一种的话，一半的端点会直接不思考。
     */
    var customThinkingStyle: String
        get() = prefs.getString("custom_thinking_style", ThinkingStyle.AUTO.key).orEmpty()
            .ifBlank { ThinkingStyle.AUTO.key }
        set(value) = prefs.edit().putString("custom_thinking_style", value).apply()

    /**
     * 思考强度（见 [ThinkingEffort]）。默认「中」。
     *
     * 默认不是「关闭」：Koog 组装请求时不会带任何思考字段，很多端点（通义、部分自建
     * vLLM）默认就是关的，用户看到的是「模型不思考、直接瞎答」。这条默认值就是为了
     * 让新装的客户端开箱就会先想一步。
     */
    var customThinkingEffort: String
        get() = prefs.getString("custom_thinking_effort", ThinkingEffort.MEDIUM.key).orEmpty()
            .ifBlank { ThinkingEffort.MEDIUM.key }
        set(value) = prefs.edit().putString("custom_thinking_effort", value).apply()

    /**
     * 终端字号（sp）。
     *
     * 手机上 12sp 的等宽字已经偏小，但命令输出本来就密——默认别太大，
     * 让需要的人自己调。存偏好而不是每次重置：调字号的人下次还会想调。
     */
    var terminalFontSp: Int
        get() = prefs.getInt("terminal_font_sp", 14)
        set(value) = prefs.edit().putInt("terminal_font_sp", value.coerceIn(10, 20)).apply()

    /** 回放给模型的历史轮数上限。 */
    var customContextTurns: Int
        get() = prefs.getInt("custom_context_turns", 10)
        set(value) = prefs.edit().putInt("custom_context_turns", value.coerceIn(1, 50)).apply()

    /**
     * 某条本机会话实际该用的模型 ID。
     *
     * 与会话级的云端模型是**两套命名空间**：本机会话的模型 ID 是用户自己端点上的
     * （`deepseek-chat`），云端那套（`hy4-preview`）在这条会话里根本不存在。
     * 所以这里绝不回落到 [selectedModel]——那是云端默认模型，拿它去请求第三方接口
     * 只会得到一句 404 或模型不存在。
     */
    fun customModelFor(conversationId: String?): String {
        if (conversationId.isNullOrBlank()) return customModelId
        return accountValue(MODEL_KEY_PREFIX + conversationId).ifBlank { customModelId }
    }

    /**
     * 组装成 [CustomModelConfig]；未配置完整时 [CustomModelConfig.usable] 为 false。
     *
     * 顺序是「全局默认 + 该模型的覆盖」：某个模型单独设了思考强度或系统提示词，
     * 只在这一条上生效，换回别的模型立刻回到全局那套。
     *
     * 端点归属：模型键是 `providerId/modelId` 时按提供商取地址与 Key；
     * 旧数据的裸 ID / 空提供商回落到迁移前的全局字段——迁移只搬数据不清字段，
     * 就是为了让这些旧引用不至于一下全断。
     */
    fun customModel(conversationId: String? = null): CustomModelConfig {
        val key = customModelFor(conversationId)
        val entry = customModelEntryByKey(key)
        val modelId = entry?.id ?: key.substringAfterLast('/')
        val (baseUrl, apiKey) = endpointFor(entry)
        return CustomModelConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            temperature = customTemperature.toDouble(),
            maxIterations = customMaxIterations,
            contextTurns = entry?.contextTurns?.takeIf { it > 0 } ?: customContextTurns,
            systemPrompt = entry?.systemPrompt?.takeIf { it.isNotBlank() } ?: customSystemPrompt,
            thinkingStyle = customThinkingStyle,
            thinkingEffort = entry?.thinkingEffort?.takeIf { it.isNotBlank() } ?: customThinkingEffort,
            // 片段是全局的（不跟模型走）：一条「提交前跑测试」对哪个模型都成立，
            // 按模型各存一份只会让用户在换模型后发现规矩莫名其妙没了。
            presets = prompts.enabled(),
        )
    }

    /** 一条模型的端点与 Key：挂在提供商下取提供商的，旧数据回落全局字段。 */
    private fun endpointFor(entry: CustomModel?): Pair<String, String> {
        val provider = entry?.providerId?.takeIf { it.isNotBlank() }?.let { providerOf(it) }
        if (provider != null) return provider.baseUrl to provider.apiKey
        return customBaseUrl to customApiKey
    }

    /**
     * 标记某个会话「用过自定义模型」。
     *
     * 这类会话的对话记录只在本机（推理跑在手机上，云端看不到这些消息），
     * 所以重新打开时不能靠 ACP 回放——那条会话在云端是空的，
     * 必须换成读 [agentMemory]。
     */
    fun markCustomConversation(id: String) {
        if (id.isBlank()) return
        // getStringSet 返回的集合不可直接改，必须复制一份再写回。
        val cur = prefs.getStringSet(KEY_CUSTOM_CONVS, emptySet()).orEmpty().toMutableSet()
        if (cur.add(id)) prefs.edit().putStringSet(KEY_CUSTOM_CONVS, cur).apply()
    }

    fun isCustomConversation(id: String): Boolean =
        prefs.getStringSet(KEY_CUSTOM_CONVS, emptySet()).orEmpty().contains(id)

    fun unmarkCustomConversation(id: String) {
        val cur = prefs.getStringSet(KEY_CUSTOM_CONVS, emptySet()).orEmpty().toMutableSet()
        if (cur.remove(id)) prefs.edit().putStringSet(KEY_CUSTOM_CONVS, cur).apply()
    }

    // ---------- 会话级模型 / 引擎 ----------
    //
    // 官方是「每个会话各自选模型」，客户端原来只有一个全局 selectedModel，
    // 切会话就把模型带过去了：A 会话选 deepseek、切到 B 会话仍然显示 deepseek，
    // 但 B 在云端跑的还是它自己的模型，界面与现实脱节。
    //
    // 这里的规则：
    // - 会话有过显式选择 → 用会话自己的；
    // - 没选过 → 回落到全局（全局此时是「新会话的默认值」）。
    // 键带账号前缀：会话 id 属于某个账号，换账号不该互相看见。

    fun modelFor(conversationId: String?): String {
        if (conversationId.isNullOrBlank()) return selectedModel
        return accountValue(MODEL_KEY_PREFIX + conversationId).ifBlank { selectedModel }
    }

    fun setModelFor(conversationId: String, model: String) {
        if (conversationId.isBlank()) return
        setAccountValue(MODEL_KEY_PREFIX + conversationId, model.trim())
    }

    /** 这条会话有没有显式选过模型（没有的话第一次打开时会被固化，之后就固定下来）。 */
    fun hasModelFor(conversationId: String): Boolean =
        accountValue(MODEL_KEY_PREFIX + conversationId).isNotBlank()

    /**
     * 会话当前用哪个引擎（[ENGINE_WB] / [ENGINE_CUSTOM]）。
     *
     * 与 [engineMode] 的关系同模型：会话显式选过就听会话的，否则跟随全局默认。
     */
    fun engineFor(conversationId: String?): String {
        if (conversationId.isNullOrBlank()) return engineMode
        val saved = accountValue(ENGINE_KEY_PREFIX + conversationId)
        return if (saved == ENGINE_WB || saved == ENGINE_CUSTOM) saved else engineMode
    }

    fun setEngineFor(conversationId: String, engine: String) {
        if (conversationId.isBlank()) return
        setAccountValue(ENGINE_KEY_PREFIX + conversationId, engine)
    }

    /**
     * 这条会话归谁（[ENGINE_WB] / [ENGINE_CUSTOM]）。
     *
     * **没存过的一律算云端**：升级上来的老会话根本没有归属记录，
     * 若回落到全局 `engineMode`，用户把默认引擎改成自定义之后，
     * 几十条老会话会被集体误判成本地——它们的对话其实都在云端。
     */
    fun kindOf(conversationId: String?): String {
        if (conversationId.isNullOrBlank()) return engineMode
        if (!hasEngineFor(conversationId)) return ENGINE_WB
        return engineFor(conversationId)
    }

    /** 是不是本地（自定义 API）会话。 */
    fun isLocal(conversationId: String?): Boolean = kindOf(conversationId) == ENGINE_CUSTOM

    fun hasEngineFor(conversationId: String): Boolean {
        val saved = accountValue(ENGINE_KEY_PREFIX + conversationId)
        return saved == ENGINE_WB || saved == ENGINE_CUSTOM
    }

    /** 会话被删除时把它的模型/引擎选择一起清掉，别留垃圾。 */
    fun clearConversationPrefs(conversationId: String) {
        if (conversationId.isBlank()) return
        prefs.edit()
            .remove(accountKey(MODEL_KEY_PREFIX + conversationId))
            .remove(accountKey(ENGINE_KEY_PREFIX + conversationId))
            .apply()
        unmarkCustomConversation(conversationId)
    }

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
        val raw = prefs.getString(msgCacheKey(conversationId), null) ?: return emptyList()
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
                put("text", msg.text.take(MSG_FIELD_MAX))
                put("toolState", msg.toolState.name)
                put("turn", msg.turn)
                msg.toolName?.let { put("toolName", it) }
                msg.toolInput?.let { put("toolInput", it.take(MSG_FIELD_MAX)) }
                msg.toolOutput?.let { put("toolOutput", it.take(MSG_FIELD_MAX)) }
            }
            array.put(obj)
        }
        prefs.edit().putString(msgCacheKey(conversationId), array.toString()).apply()
    }

    /** 清除某个会话的消息缓存。 */
    fun clearMessageCache(conversationId: String) {
        prefs.edit().remove(msgCacheKey(conversationId)).apply()
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

    /** 消息缓存按账号 + 会话隔离：同一个会话 id 在不同账号下不是同一份内容。 */
    private fun msgCacheKey(conversationId: String): String =
        KEY_MSG_CACHE_PREFIX + activeAccountId + ":" + conversationId

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

        /** 推理引擎：走云端会话协议（默认，扣积分）。 */
        const val ENGINE_WB = "wb"

        /** 推理引擎：本机 agent + 用户自带的 OpenAI 兼容 API（不扣积分）。 */
        const val ENGINE_CUSTOM = "custom"

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.002) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        private const val KEY_ACCOUNTS = "accounts_v2"
        private const val KEY_ACTIVE = "active_account"

        /** 用过自定义模型的会话 id 集合。 */
        private const val KEY_CUSTOM_CONVS = "custom_conversations"

        private const val MODEL_KEY_PREFIX = "conv_model:"
        private const val ENGINE_KEY_PREFIX = "conv_engine:"

        /** 还没建账号时（登录过程中）临时写进这个槽，登录成功后随账号一起被接管。 */
        private const val GUEST_SLOT = "guest"

        /** 消息缓存 key 前缀，按会话 ID 隔离。 */
        private const val KEY_MSG_CACHE_PREFIX = "msg_cache_v1:"

        /** 每个会话最多缓存的消息条数，防止无限膨胀。 */
        private const val MSG_CACHE_LIMIT = 200

        /**
         * 单条消息单个字段的缓存上限。
         *
         * 工具输出可能有几 MB（`cat` 大文件），原样序列化进 SharedPreferences
         * 会让每次落盘都变成一次大 JSON 写入，重新打开时解析也慢。
         */
        private const val MSG_FIELD_MAX = 16_000
    }
}
