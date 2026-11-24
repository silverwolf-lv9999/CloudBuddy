package cn.cloudbuddy.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** 待发送的本地附件。 */
data class PendingAttachment(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is PendingAttachment && other.name == name && other.bytes.size == bytes.size
    override fun hashCode(): Int = name.hashCode() * 31 + bytes.size
}

data class AppUiState(
    val booting: Boolean = true,
    val loggedIn: Boolean = false,
    val account: AccountInfo = AccountInfo(),
    val conversations: List<Conversation> = emptyList(),
    val activeId: String? = null,
    val activeTitle: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val state: TurnState = TurnState.IDLE,
    val ask: AskQuestion? = null,
    val error: String? = null,
    val loadingConversation: Boolean = false,
    /**
     * 回放已收到的消息条数，只用于「正在载入历史 · 已收到 N 条」。
     *
     * 回放期间不推消息内容：历史一条条冒出来会像在重放当时的步骤，
     * 加载完一次性给终态才是用户期望的观感。
     */
    val historyProgress: Int = 0,
    /** 历史没放完（回放超时）时的温和提示，不是错误，可重新载入。 */
    val historyNotice: String? = null,
    /** 草稿会话：还没创建云端会话，第一次发送时才建。 */
    val draft: Boolean = false,
    val sending: Boolean = false,
    val models: List<ModelChoice> = emptyList(),
    val selectedModel: String = "",
    val modelsLoading: Boolean = false,
    val modelError: String? = null,
    val usage: List<UsagePack> = emptyList(),
    val checkin: CheckinInfo? = null,
    val checkinLoading: Boolean = false,
    val checkinError: String? = null,
    val growth: GrowthInfo? = null,
    /**
     * 折叠条的展开状态（key = 折叠条的渲染 key，value = 是否展开）。
     *
     * 只记录用户显式操作过的条目；没记录的跟随 [stepsCollapsed]。
     * key 用渲染项自带的字符串而不是轮次编号：只折过程时一轮会有多个折叠条，
     * 按轮次存会让它们联动。
     */
    val stepsExpanded: Map<String, Boolean> = emptyMap(),
    /**
     * 中间步骤默认折叠。这是全局意图：打开后新产生的轮次也折叠，不必每轮手动点一次。
     */
    val stepsCollapsed: Boolean = true,
    /** 折叠方式（整轮 / 只折思考与工具）。 */
    val foldMode: FoldMode = FoldMode.WHOLE,
    /**
     * 启动时连不上服务器，但本地凭据还在：直接进主页，不弹错误、不踢回登录页。
     */
    val offline: Boolean = false,
    val sessionExpired: Boolean = false,
    /** 已保存的账号（切换入口用）。 */
    val accounts: List<Account> = emptyList(),
    /** 当前活跃账号 id，界面据此高亮。 */
    val activeAccountId: String = "",
    /** 账号的技能清单（云端 `user-asset`）。 */
    val skills: List<SkillInfo> = emptyList(),
    val skillsLoading: Boolean = false,
    val skillsError: String? = null,
    /** 技能市场搜索结果。 */
    val marketSkills: List<SkillInfo> = emptyList(),
    val marketLoading: Boolean = false,
    /** 本机 MCP 服务器（随会话下发）。 */
    val mcpServers: List<McpServer> = emptyList(),
    /** 账号级 MCP 连接器：云端保存，和官方 App / 网页端共用同一份。 */
    val connectors: List<Connector> = emptyList(),
    val connectorsLoading: Boolean = false,
    val connectorsError: String? = null,
    /** 连接器市场清单（mcp_only）。 */
    val connectorCatalog: List<Connector> = emptyList(),
    val connectorCatalogLoading: Boolean = false,
    /** 正在走的授权流程：界面据此弹「打开授权页 / 填 token」。 */
    val connectorAuth: PendingConnectorAuth? = null
)

/** 一次未完成的连接器授权：记下名字，界面才能知道结果该刷新哪一条。 */
data class PendingConnectorAuth(
    val name: String,
    val title: String,
    val flow: ConnectorAuth
)

data class WorkspaceState(
    val conversationId: String? = null,
    val path: String = "/workspace",
    val entries: List<SandboxEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 会话接入点 / 文件网关诊断信息，方便排查不同网关版本的差异。 */
    val diagnostics: String = ""
)

/**
 * 客户端的编排层。
 *
 * 关键取舍：不把 WorkBuddy 当成本地模型 API 来「反代」，所有执行都发生在云端
 * 沙箱里（ACP 会话 + `/workspace`）。因此这里维护的是「会话连接 + 沙箱工作区」
 * 两套状态，而不是一个无状态的 chat completions 调用。
 */
class BuddyRepository(val settings: AppSettings) {

    val http = CloudHttp(settings)
    val login = LoginClient(settings, http)
    val api = WorkBuddyApi(settings, http)
    val sandbox = SandboxFs(http, settings)
    val skillsApi = SkillApi(http, settings)
    val connectorApi = ConnectorApi(http)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, AcpConnection>()
    private val sessions = ConcurrentHashMap<String, CloudSession>()

    /**
     * 状态对账用的少量簿记。
     *
     * 对账的目的是把本地「猜」的状态和云端记录对齐（详见 [StateReconciler]），
     * 但这对账本身不能变成负担：后台不跑、空闲慢跑、有任务才快跑，
     * 补历史还要按 `updatedAt` 去重并退避。这几个字段就是那套节流。
     */
    @Volatile private var foreground = true
    @Volatile private var resyncing = false
    @Volatile private var lastResyncAt = 0L
    @Volatile private var lastTurnEndAt = 0L
    private var resyncGap = RESYNC_MIN_GAP
    private val syncedAt = ConcurrentHashMap<String, Long>()

    /**
     * 正在跑 prompt 的会话 id。
     *
     * 不能用全局 `ui.sending` 代替：切到别的会话时它会被清掉，切回来时又没人置回 true。
     * 对账若据此判断「本地没有流」，就会在任务正跑时重建连接、把活的 SSE 掐断。
     */
    private val inFlightTurns = ConcurrentHashMap.newKeySet<String>()

    /**
     * 「本地这一轮真的还在动」：有在途登记，并且那条连接的 SSE 最近还在推事件。
     *
     * 只登记不校验是不够的。协程可能卡在读一个早就死掉的流上（网络切换、中间层吞连接），
     * `inFlightTurns` 就一直有记录，对账被「本地在途优先」永久挡住——表现就是任务早就
     * 在云端结束了，客户端还一直转圈、点停止后一发送又提示「云端仍在运行」。
     * 这里用事件时间兜底：超过 [STREAM_STALE_MS] 没有新事件，就不再认这条在途登记。
     */
    private fun hasLiveTurn(id: String): Boolean {
        if (!inFlightTurns.contains(id)) return false
        val conn = connections[id] ?: return false
        return conn.isStreamActive(STREAM_STALE_MS)
    }

    @Volatile private var lastFullListAt = 0L

    /**
     * UI 状态的写锁。
     *
     * 对账线程和 SSE 推送线程都会改 [_ui]：前者只该改状态角标，后者改消息。
     * 两边各自「读—改—写」时没有临界区，对账就可能把刚推上来的消息回退成旧快照。
     * 锁内只比几个标量字段，不做整份 state 的深度比较（消息列表很长，比一次比重建还贵）。
     */
    private val uiLock = Any()

    /** ACP session/load 下发的可用模型，按会话隔离；这是最可靠的模型来源。 */
    private val acpModels = ConcurrentHashMap<String, List<ModelChoice>>()
    @Volatile private var acpCurrentModel: String? = null

    private val _ui = MutableStateFlow(
        AppUiState(
            loggedIn = settings.loggedIn && settings.cookies.hasAny(),
            stepsCollapsed = settings.collapseSteps,
            foldMode = settings.fold,
            accounts = settings.accounts(),
            activeAccountId = settings.activeAccountId,
            mcpServers = settings.mcp.list()
        )
    )
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    private val _workspace = MutableStateFlow(WorkspaceState())
    val workspace: StateFlow<WorkspaceState> = _workspace.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    init {
        // 自动续期只关心「续上没有」：网络异常交给调用方按失败处理，
        // 否则一次超时会被当成凭据失效，把用户踢回登录页。
        http.renewer = { runCatching { runBlocking { login.renew() } }.getOrDefault(false) }
        http.onSessionExpired = {
            settings.markSessionExpired()
            _ui.value = _ui.value.copy(loggedIn = false, sessionExpired = true)
        }
        scope.launch { stateSyncLoop() }
    }

    // ---------- 云端状态对账 ----------

    /**
     * 前台/后台切换。后台直接停掉对账：既省电省流量，也避免用户没看的时候还在重建连接。
     * 回到前台立刻补一次，别让用户盯着过期的「进行中」。
     */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) scope.launch { reconcile(fullList = true) }
    }

    /**
     * 对账主循环。
     *
     * 只做一件轻量的网络请求（`batch-get` 当前会话），其余判断都在内存里；
     * 空闲 45 秒一次、有任务 10 秒一次、后台 0 次。
     */
    private suspend fun stateSyncLoop() {
        while (true) {
            val current = _ui.value
            val ready = current.loggedIn && !current.booting && current.activeId != null
            var anyActive = StateReconciler.isActive(current.state)
            if (ready && StateReconciler.intervalMs(foreground, anyActive) > 0) {
                anyActive = runCatching { reconcile() }.getOrDefault(anyActive)
            }
            val next = StateReconciler.intervalMs(foreground && ready, anyActive, current.sending)
            delay(if (next > 0L) next else 30_000L)
        }
    }

    /**
     * 把当前会话的状态和云端记录对一次账。
     *
     * 顺带按 [FULL_LIST_GAP] 刷一次会话列表（其它会话的角标）；列表刷新比状态对账稀得多，
     * 因为它要拉 30 条记录，而对账只需要一个 id。
     *
     * @return 对账后是否仍有任务在跑（决定下一次的间隔）
     */
    private suspend fun reconcile(fullList: Boolean = false): Boolean {
        val current = _ui.value
        val activeId = current.activeId ?: return StateReconciler.isActive(current.state)
        if (!current.loggedIn || current.booting) return false

        val now = System.currentTimeMillis()
        val shouldList = fullList || now - lastFullListAt >= FULL_LIST_GAP

        var activeRemote: Conversation? = null
        var list: List<Conversation>? = null
        if (shouldList) {
            lastFullListAt = now
            list = runCatching { api.listConversations() }.getOrNull()
            activeRemote = list?.firstOrNull { it.id == activeId }
        }
        if (activeRemote == null) {
            activeRemote = runCatching { api.batchGet(listOf(activeId)) }.getOrNull()?.firstOrNull()
        }

        val latest = _ui.value
        // 本地刚收尾的那几秒，服务端记录往往还停在 running；此刻采纳会把状态弹回
        // 「进行中」、暂停按钮重新出现，看着像任务又活了。给服务端一个收敛窗口。
        val settling = System.currentTimeMillis() - lastTurnEndAt < SETTLE_MS
        val action = if (activeRemote != null &&
            !(settling && StateReconciler.isActive(activeRemote.state) && !StateReconciler.isActive(latest.state))
        ) {
            StateReconciler.decide(latest.state, activeRemote.state, hasLiveTurn(activeId))
        } else {
            StateReconciler.Action.NONE
        }
        val adopted = if (action != StateReconciler.Action.NONE) activeRemote else null
        val targetState = adopted?.state ?: latest.state

        // 列表：非当前会话直接用云端角标；当前会话用对账后的状态，并保留本地的
        // 待确认卡片与产物（REST 里没有这两项，覆盖掉会让卡片闪一下又回来）。
        val merged = list?.map { remote ->
            if (remote.id == activeId) {
                remote.copy(state = targetState, artifacts = latest.artifacts.ifEmpty { remote.artifacts }, ask = latest.ask)
            } else remote
        } ?: if (adopted != null) {
            // 只查了 batch-get（没到刷新列表的周期）：也得把角标改过来，
            // 否则会话页显示「已停止」，列表里还挂着「进行中」。
            latest.conversations.map { if (it.id == activeId) it.copy(state = targetState) else it }
        } else {
            null
        }

        // 只写「角标 + 状态」，且必须在锁内基于最新值重算：网络期间 SSE 线程可能刚
        // publish 过更新的消息，拿旧快照整份覆盖会把新消息回退掉。
        var committedState = latest.state
        synchronized(uiLock) {
            val cur = _ui.value
            var next = cur
            if (merged != null && !sameConversations(cur.conversations, merged)) {
                next = next.copy(conversations = merged)
            }
            if (adopted != null) {
                next = next.copy(
                    state = adopted.state,
                    error = if (adopted.state == TurnState.FAILED) adopted.error else null
                )
            }
            if (next !== cur) _ui.value = next
            committedState = next.state
        }

        if (adopted != null && action == StateReconciler.Action.ADOPT_REMOTE_AND_RELOAD) {
            // 状态边沿（从没任务变成有任务）说明本地漏了事件，这次补历史不该等退避。
            resyncGap = RESYNC_MIN_GAP
            scheduleResync(activeId, adopted)
        }
        return StateReconciler.isActive(committedState) || StateReconciler.isActive(activeRemote?.state)
    }

    /**
     * 列表结构比较：只比影响界面的字段。
     *
     * `Conversation` 是 data class，`equals` 会连 `artifacts` 一起比；列表项多、产物多时
     * 这种深度比较反而比重建对象更贵，而界面只关心这几个字段。
     */
    private fun sameConversations(a: List<Conversation>, b: List<Conversation>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.id != y.id || x.title != y.title || x.state != y.state ||
                x.updatedAt != y.updatedAt || x.preview != y.preview
            ) return false
        }
        return true
    }

    /**
     * 补一次历史（云端在别处跑出来的新内容）。
     *
     * 这里是唯一的重操作：重建连接会重放整段历史。所以三道闸：正在本地发送时不补、
     * `updatedAt` 没前进不补、两次之间按倍数退避到 60 秒。
     */
    private fun scheduleResync(id: String, remote: Conversation) {
        if (resyncing) return
        if (_ui.value.activeId != id || hasLiveTurn(id)) return
        if (!StateReconciler.shouldResync(remote.updatedAt, syncedAt[id] ?: 0L, remote.state)) return
        val now = System.currentTimeMillis()
        if (now - lastResyncAt < resyncGap) return
        resyncing = true
        scope.launch {
            val ok = runCatching { silentResync(id) }.getOrDefault(false)
            lastResyncAt = System.currentTimeMillis()
            resyncGap = if (ok) {
                (resyncGap * 3 / 2).coerceAtMost(RESYNC_MAX_GAP)
            } else {
                (resyncGap * 2).coerceAtMost(RESYNC_MAX_GAP)
            }
            resyncing = false
        }
    }

    /**
     * 静默重建连接并替换消息，不显示「正在载入」、不清空当前内容。
     *
     * 用于「任务在云端继续跑」的场景：用户不该看到进度条，只该看到内容变多。
     */
    private suspend fun silentResync(id: String): Boolean {
        if (_ui.value.activeId != id || hasLiveTurn(id)) return false
        val conn = withContext(Dispatchers.IO) {
            connections.remove(id)?.close()
            connection(id)
        }
        if (_ui.value.activeId != id) return false
        val snap = conn.snapshot()
        val current = _ui.value
        // 重放收尾会把「执行中」收敛成 IDLE；云端明明还在跑时不能跟着降级，
        // 否则暂停按钮消失、用户又能发消息，正是要修的那个错位。
        val state = if (snap.state == TurnState.IDLE && StateReconciler.isActive(current.state)) {
            current.state
        } else {
            snap.state
        }
        synchronized(uiLock) {
            val cur = _ui.value
            if (cur.activeId != id) return false
            _ui.value = cur.copy(
                messages = snap.messages,
                artifacts = snap.artifacts,
                state = state,
                ask = snap.ask,
                error = snap.error,
                loadingConversation = false,
                historyProgress = 0
            )
        }
        syncedAt[id] = runCatching { api.batchGet(listOf(id)).firstOrNull()?.updatedAt }
            .getOrNull() ?: System.currentTimeMillis()
        return true
    }

    /**
     * 打开会话后立刻对一次账：`session/load` 的重放收尾会把「执行中」压成 IDLE，
     * 而云端可能正被另一台设备驱动。这一步决定输入栏是发送还是暂停。
     */
    private suspend fun reconcileAfterOpen(id: String) {
        val remote = runCatching { api.batchGet(listOf(id)) }.getOrNull()?.firstOrNull() ?: return
        syncedAt[id] = remote.updatedAt
        val current = _ui.value
        if (current.activeId != id) return
        val action = StateReconciler.decide(current.state, remote.state, hasLiveTurn(id))
        // 刚载入完，消息是最新的：只采纳状态，不再触发一次重建。
        if (action != StateReconciler.Action.NONE) {
            synchronized(uiLock) {
                val cur = _ui.value
                if (cur.activeId == id) {
                    _ui.value = cur.copy(
                        state = remote.state,
                        error = if (remote.state == TurnState.FAILED) remote.error else null
                    )
                }
            }
        }
    }

    /**
     * 发送前的最后一道闸：服务端记录里若仍是执行中/等待，就拒绝发送。
     *
     * 查不到就放行——网络抖动不该把正常发送也挡住；能查到但状态不对才是真问题。
     */
    private suspend fun confirmIdle(id: String): Boolean {
        val remote = runCatching { api.batchGet(listOf(id)) }.getOrNull()?.firstOrNull() ?: return true
        if (!StateReconciler.isActive(remote.state)) return true
        applyIfActive(id) { copy(state = remote.state, sending = false) }
        return false
    }

    // ---------- 启动 / 登录 ----------

    /**
     * 启动流程。
     *
     * 判定顺序是「凭据在不在」→「服务在不在」。以前两者混成一个 Boolean，
     * 网络差时 probe 失败 → renew 也失败（其实是超时）→ 直接登出，
     * 已登录用户被扔回登录页，还得重新等短信验证码。
     *
     * 现在只有凭据**明确**不可用才登出；连不上就先以离线状态进主页。
     */
    fun start() {
        scope.launch {
            if (!_ui.value.loggedIn) {
                _ui.value = _ui.value.copy(booting = false)
                return@launch
            }
            when (api.probeState()) {
                SessionProbe.VALID -> {
                    _ui.value = _ui.value.copy(booting = false, loggedIn = true, offline = false)
                    refreshAll()
                }

                SessionProbe.INVALID -> {
                    // getOrNull() == null 表示续期时网络出错，不是凭据问题。
                    val renewed = runCatching { login.renew() }.getOrNull()
                    if (renewed == false) {
                        settings.markSessionExpired()
                        _ui.value = _ui.value.copy(booting = false, loggedIn = false, offline = false)
                        return@launch
                    }
                    _ui.value = _ui.value.copy(
                        booting = false,
                        loggedIn = true,
                        offline = renewed == null
                    )
                    if (renewed == true) refreshAll()
                }

                SessionProbe.UNREACHABLE -> {
                    // 连不上服务器，但本地凭据还在：直接进主页，不刷新、不弹错误。
                    _ui.value = _ui.value.copy(booting = false, loggedIn = true, offline = true)
                }
            }
        }
    }

    private suspend fun refreshAll() {
        refreshAccount()
        refreshConversations()
        refreshModels()
        refreshCheckin()
        refreshUsage()
    }

    suspend fun sendCode(phone: String): Int = login.sendCode(phone)

    suspend fun submitCode(phone: String, code: String): Result<Unit> = runCatching {
        login.submit(phone, code)
        _ui.value = _ui.value.copy(loggedIn = true, booting = false, sessionExpired = false)
        publishAccounts()
        refreshAccount()
        refreshConversations()
        refreshModels()
    }

    fun signOut() {
        connections.values.forEach { it.close() }
        connections.clear()
        sessions.clear()
        acpModels.clear()
        acpCurrentModel = null
        settings.signOut()
        // 折叠开关是界面偏好，不随登录态清空——重置成全量默认值会和偏好对不上。
        _ui.value = AppUiState(
            booting = false,
            loggedIn = false,
            stepsCollapsed = settings.collapseSteps,
            foldMode = settings.fold,
            accounts = settings.accounts(),
            activeAccountId = settings.activeAccountId,
            mcpServers = settings.mcp.list()
        )
        _workspace.value = WorkspaceState()
    }

    // ---------- 多账号 ----------

    /**
     * 切换账号。
     *
     * 必须先把 ACP 连接、会话缓存和界面状态全部清掉：会话 id 是账号内的，
     * 带着上一个账号的 `activeId` 去拉列表会命中别人的会话（服务端会 404 或者
     * 更糟——返回一个同 id 的别人的会话）。
     */
    fun switchAccount(id: String) {
        if (!settings.switchAccount(id)) return
        resetForAccountSwitch()
        start()
    }

    /**
     * 添加账号：先开一个空槽，让登录链路有地方写 cookie。
     *
     * 返回新账号 id；界面拿到后跳到登录页。登录成功 [saveSession] 会把信息补进
     * 这条记录，失败则由使用者调 [removeAccount] 清理空壳。
     */
    fun addAccount(): String {
        val id = settings.beginAccount()
        resetForAccountSwitch()
        return id
    }

    /** 删除账号。删的是当前账号时自动切到剩下的第一个；一个不剩就回登录页。 */
    fun removeAccount(id: String) {
        val wasActive = id == settings.activeAccountId
        settings.removeAccount(id)
        if (wasActive) {
            resetForAccountSwitch()
            if (settings.loggedIn) start()
        } else {
            publishAccounts()
        }
    }

    // ---------- 技能 ----------

    /** 拉账号的技能清单（含启用状态）。失败要把原因显出来，不能安静地显示空列表。 */
    fun refreshSkills() {
        if (_ui.value.skillsLoading) return
        _ui.value = _ui.value.copy(skillsLoading = true, skillsError = null)
        scope.launch {
            val result = runCatching { skillsApi.installed() }
            _ui.value = _ui.value.copy(
                skillsLoading = false,
                skills = result.getOrElse { _ui.value.skills },
                skillsError = result.exceptionOrNull()?.let { describe(it) }
            )
        }
    }

    /** 启停技能。云端可能「部分成功」，失败原因直接回给界面。 */
    fun setSkillEnabled(skill: SkillInfo, enabled: Boolean) {
        scope.launch {
            val errors = runCatching { skillsApi.setEnabled(listOf(skill), enabled) }
                .getOrElse { listOf(describe(it)) }
            if (errors.isEmpty()) {
                _ui.value = _ui.value.copy(
                    skills = _ui.value.skills.map {
                        if (it.id == skill.id) it.copy(enabled = enabled) else it
                    }
                )
            } else {
                _events.tryEmit(errors.first())
            }
        }
    }

    /** 搜索技能市场。 */
    fun searchMarket(keyword: String) {
        _ui.value = _ui.value.copy(marketLoading = true)
        scope.launch {
            val result = runCatching { skillsApi.market(keyword) }
            _ui.value = _ui.value.copy(
                marketLoading = false,
                marketSkills = result.getOrElse { emptyList() }
            )
            result.exceptionOrNull()?.let { _events.tryEmit("技能市场：" + describe(it)) }
        }
    }

    /** 从市场安装，成功后刷新清单。 */
    fun installSkill(skill: SkillInfo) {
        scope.launch {
            runCatching { skillsApi.install(skill.id, skill.version) }
                .onSuccess {
                    _events.tryEmit("已安装 ${skill.title}")
                    _ui.value = _ui.value.copy(marketSkills = emptyList())
                    refreshSkills()
                }
                .onFailure { _events.tryEmit("安装失败：" + describe(it)) }
        }
    }

    // ---------- MCP ----------

    /** MCP 表变了就同步到界面；`payload()` 在下一轮会话建立/加载时生效。 */
    fun reloadMcp() {
        _ui.value = _ui.value.copy(mcpServers = settings.mcp.list())
    }

    /** 保存一条 MCP 配置，返回错误提示（成功为 null）。 */
    fun saveMcp(raw: String, id: String? = null): String? {
        val error = settings.mcp.upsert(raw, id)
        if (error == null) reloadMcp()
        return error
    }

    fun setMcpEnabled(id: String, enabled: Boolean) {
        settings.mcp.setEnabled(id, enabled)
        reloadMcp()
    }

    fun removeMcp(id: String) {
        settings.mcp.remove(id)
        reloadMcp()
    }

    // ---------- 账号 MCP 连接器（云端） ----------

    /** 拉账号的连接器清单。授权状态要另查一次，`connect_status` 只说明「绑过」。 */
    fun refreshConnectors() {
        if (_ui.value.connectorsLoading) return
        _ui.value = _ui.value.copy(connectorsLoading = true, connectorsError = null)
        scope.launch {
            val result = runCatching { connectorApi.mine() }
            _ui.value = _ui.value.copy(
                connectorsLoading = false,
                connectors = result.getOrElse { _ui.value.connectors },
                connectorsError = result.exceptionOrNull()?.let { describe(it) }
            )
        }
    }

    /** 启停云端连接器。先改界面再打请求，失败回滚——开关点了没反应最难受。 */
    fun setConnectorActive(connector: Connector, enabled: Boolean) {
        _ui.value = _ui.value.copy(
            connectors = _ui.value.connectors.map {
                if (it.name == connector.name) it.copy(active = enabled) else it
            }
        )
        scope.launch {
            runCatching { connectorApi.setActive(connector.name, enabled) }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        connectors = _ui.value.connectors.map {
                            if (it.name == connector.name) it.copy(active = connector.active) else it
                        }
                    )
                    _events.tryEmit("连接器启停失败：" + describe(it))
                }
        }
    }

    /** 解绑：连 `oauth` 授权一起失效。界面会先确认。 */
    fun removeConnector(connector: Connector) {
        scope.launch {
            runCatching { connectorApi.remove(connector.name) }
                .onSuccess {
                    _events.tryEmit("已解绑 ${connector.title}")
                    refreshConnectors()
                }
                .onFailure { _events.tryEmit("解绑失败：" + describe(it)) }
        }
    }

    fun loadConnectorCatalog() {
        if (_ui.value.connectorCatalogLoading) return
        _ui.value = _ui.value.copy(connectorCatalogLoading = true)
        scope.launch {
            val result = runCatching { connectorApi.catalog() }
            _ui.value = _ui.value.copy(
                connectorCatalogLoading = false,
                connectorCatalog = result.getOrElse { _ui.value.connectorCatalog }
            )
            result.exceptionOrNull()?.let { _events.tryEmit("连接器市场：" + describe(it)) }
        }
    }

    /** 发起授权。已连接时云端直接回 `connected`，这时刷新列表就行。 */
    fun connectConnector(connector: Connector) {
        scope.launch {
            runCatching { connectorApi.startAuth(connector.name) }
                .onSuccess { flow ->
                    if (flow.action == "connected") {
                        refreshConnectors()
                    } else {
                        _ui.value = _ui.value.copy(
                            connectorAuth = PendingConnectorAuth(connector.name, connector.title, flow)
                        )
                    }
                }
                .onFailure { _events.tryEmit("发起授权失败：" + describe(it)) }
        }
    }

    /** 表单型授权：官方对单字段表单发的就是 `authorization_code`。 */
    fun submitConnectorToken(token: String) {
        val pending = _ui.value.connectorAuth ?: return
        scope.launch {
            runCatching { connectorApi.submitToken(pending.name, token) }
                .onSuccess {
                    _ui.value = _ui.value.copy(connectorAuth = null)
                    refreshConnectors()
                }
                .onFailure { _events.tryEmit("提交授权失败：" + describe(it)) }
        }
    }

    /** 浏览器授权完成后点这个按钮轮询一次状态。 */
    fun checkConnectorAuth() {
        val pending = _ui.value.connectorAuth ?: return
        scope.launch {
            runCatching { connectorApi.status(pending.name) }
                .onSuccess { ok ->
                    if (ok) {
                        _ui.value = _ui.value.copy(connectorAuth = null)
                        refreshConnectors()
                    } else {
                        _events.tryEmit("还没完成授权，授权完再点一次")
                    }
                }
                .onFailure { _events.tryEmit("查询授权失败：" + describe(it)) }
        }
    }

    fun dismissConnectorAuth() {
        _ui.value = _ui.value.copy(connectorAuth = null)
    }

    /** 把异常翻译成一句人话：HTTP 404 说明这个账号/站点没有该接口，比「空列表」有用得多。 */
    private fun describe(error: Throwable): String = when (error) {
        is CloudException -> error.message ?: "请求失败"
        is SessionExpiredException -> "登录已过期"
        else -> error.message ?: error.javaClass.simpleName
    }

    /** 把账号表同步到界面。登录成功、资料刷新后调用。 */
    fun publishAccounts() {
        _ui.value = _ui.value.copy(
            accounts = settings.accounts(),
            activeAccountId = settings.activeAccountId,
            mcpServers = settings.mcp.list()
        )
    }

    private fun resetForAccountSwitch() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        sessions.clear()
        acpModels.clear()
        acpCurrentModel = null
        _ui.value = AppUiState(
            booting = false,
            loggedIn = settings.loggedIn && settings.cookies.hasAny(),
            stepsCollapsed = settings.collapseSteps,
            foldMode = settings.fold,
            accounts = settings.accounts(),
            activeAccountId = settings.activeAccountId,
            mcpServers = settings.mcp.list(),
            // 连接器是账号级的：切号必须清空，否则会把上一个账号的 GitHub 显示在新的账号下。
            connectors = emptyList(),
            connectorCatalog = emptyList(),
            connectorAuth = null
        )
        _workspace.value = WorkspaceState()
    }

    suspend fun refreshAccount() {
        val account = runCatching { api.account() }.getOrNull() ?: return
        if (account.nickname.isNotBlank()) settings.nickname = account.nickname
        if (account.avatarUrl.isNotBlank()) settings.avatarUrl = account.avatarUrl
        publishAccounts()
        _ui.value = _ui.value.copy(account = account.copy(phone = settings.accountHint))
    }

    suspend fun refreshConversations() {
        val list = runCatching { api.listConversations() }.getOrElse {
            _events.tryEmit(it.message ?: "会话列表加载失败")
            return
        }
        _ui.value = _ui.value.copy(
            // 能拉到列表说明网络恢复了，把启动时的离线标记清掉。
            offline = false,
            conversations = list.map { remote ->
                val active = _ui.value.activeId
                if (remote.id == active) {
                    remote.copy(
                        state = _ui.value.state,
                        artifacts = _ui.value.artifacts.ifEmpty { remote.artifacts },
                        ask = _ui.value.ask
                    )
                } else remote
            }
        )
    }

    /**
     * 模型三路合并：ACP `session/load` 下发的 availableModels（主）→
     * console `/models`（补充）→ 官方默认模型（兜底）。REST 失败不再静默吞掉。
     */
    suspend fun refreshModels() {
        _ui.value = _ui.value.copy(modelsLoading = true, modelError = null)
        val rest = runCatching { api.listModels() }
        val merged = mergeModels(rest.getOrDefault(emptyList()) + _ui.value.models)
        val selected = settings.selectedModel.takeIf { it.isNotBlank() }
            ?: acpCurrentModel?.takeIf { it.isNotBlank() }
            ?: merged.firstOrNull { it.isDefault }?.id
            ?: merged.firstOrNull()?.id
            ?: AppSettings.DEFAULT_MODEL
        _ui.value = _ui.value.copy(
            models = merged,
            selectedModel = selected,
            modelsLoading = false,
            modelError = rest.exceptionOrNull()?.message
        )
    }

    private fun mergeModels(remote: List<ModelChoice>): List<ModelChoice> {
        val all = LinkedHashMap<String, ModelChoice>()
        acpModels.values.flatten().forEach { choice ->
            all[choice.id] = all[choice.id]?.merge(choice) ?: choice
        }
        remote.forEach { choice ->
            all[choice.id] = all[choice.id]?.merge(choice) ?: choice
        }
        if (all.isEmpty()) {
            all[AppSettings.DEFAULT_MODEL] =
                ModelChoice(AppSettings.DEFAULT_MODEL, "官方默认模型", isDefault = true)
        }
        return all.values.toList()
    }

    private fun ModelChoice.merge(other: ModelChoice): ModelChoice = copy(
        name = if (name == id && other.name != other.id) other.name else name,
        description = description ?: other.description,
        credits = credits ?: other.credits,
        isDefault = isDefault || other.isDefault
    )

    private fun publishModels(conversationId: String, models: List<ModelChoice>, current: String?) {
        if (models.isNotEmpty()) acpModels[conversationId] = models
        if (!current.isNullOrBlank()) acpCurrentModel = current
        val merged = mergeModels(_ui.value.models)
        val selected = settings.selectedModel.takeIf { it.isNotBlank() }
            ?: current?.takeIf { it.isNotBlank() }
            ?: _ui.value.selectedModel
        _ui.value = _ui.value.copy(models = merged, selectedModel = selected, modelError = null)
    }

    suspend fun refreshUsage() {
        val packs = runCatching { api.usage() }.getOrDefault(emptyList())
        val growth = runCatching { api.growth() }.getOrNull()
        _ui.value = _ui.value.copy(
            usage = packs,
            growth = growth ?: _ui.value.growth
        )
    }

    /** 签到状态；失败保留错误文案，由界面提供重试。 */
    suspend fun refreshCheckin() {
        if (_ui.value.checkinLoading) return
        _ui.value = _ui.value.copy(checkinLoading = true, checkinError = null)
        runCatching { api.checkinStatus() }
            .onSuccess { info ->
                _ui.value = _ui.value.copy(checkin = info, checkinLoading = false)
            }
            .onFailure { failure ->
                _ui.value = _ui.value.copy(checkinLoading = false, checkinError = readable(failure))
            }
    }

    /** 领取今日签到积分，成功后顺带刷新余额。 */
    fun claimCheckin() {
        if (_ui.value.checkinLoading) return
        _ui.value = _ui.value.copy(checkinLoading = true, checkinError = null)
        scope.launch {
            runCatching { api.claimCheckin() }
                .onSuccess { info ->
                    _ui.value = _ui.value.copy(checkin = info, checkinLoading = false)
                    val credit = if (info.todayCredit > 0) "，+${info.todayCredit} 积分" else ""
                    _events.tryEmit("签到成功$credit")
                    refreshUsage()
                }
                .onFailure { failure ->
                    val message = readable(failure)
                    _ui.value = _ui.value.copy(checkinLoading = false, checkinError = message)
                    _events.tryEmit("签到失败：$message")
                }
        }
    }

    // ---------- 中间步骤折叠 ----------

    /**
     * 折叠 / 展开某一轮的中间步骤。
     *
     * 存的是「显式值」：没记录过的轮次按默认规则渲染（正在执行的那一轮展开，
     * 其余折叠）。这样用户手动收起的组不会被下一个流式分片重新展开。
     */
    fun toggleSteps(key: String, expanded: Boolean) {
        val current = _ui.value
        _ui.value = current.copy(stepsExpanded = current.stepsExpanded + (key to expanded))
    }

    /** 切换折叠方式。已记录的展开状态清空：两种模式的 key 不是一套，留着会串。 */
    fun setFoldMode(mode: FoldMode) {
        settings.foldMode = mode.key
        _ui.value = _ui.value.copy(foldMode = mode, stepsExpanded = emptyMap())
    }

    /** 三点菜单的一键操作：把当前所有轮次写成显式值。 */
    /**
     * 切换「折叠中间步骤」。
     *
     * 这是全局开关，不是「把当前这些轮次设成折叠」：写进偏好后，之后新产生的轮次
     * 默认也折叠，用户不必每问一句再手动点一次。
     * 同时清空逐轮的手动记录——不清的话旧轮次还开着，看着像开关没生效。
     */
    fun setCollapseSteps(collapsed: Boolean) {
        settings.collapseSteps = collapsed
        _ui.value = _ui.value.copy(stepsCollapsed = collapsed, stepsExpanded = emptyMap())
    }

    fun selectModel(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        settings.selectedModel = clean
        _ui.value = _ui.value.copy(
            selectedModel = clean,
            modelError = null,
            models = if (_ui.value.models.any { it.id == clean }) _ui.value.models
            else _ui.value.models + ModelChoice(clean, clean)
        )
        val active = _ui.value.activeId
        if (active == null) {
            _events.tryEmit("已选择「$clean」，下一次发送时生效")
            return
        }
        scope.launch {
            runCatching { connection(active).setModel(clean) }
                .onSuccess { _events.tryEmit("已切换到「$clean」") }
                .onFailure {
                    _events.tryEmit("切换失败：" + readable(it) + "（下轮 prompt 仍会带上该模型）")
                }
        }
    }

    // ---------- 会话 ----------

    /** 新建草稿会话：真正创建发生在第一次发送时，避免留下空会话。 */
    fun startNew() {
        _ui.value = _ui.value.copy(
            activeId = null,
            draft = true,
            activeTitle = "新会话",
            messages = emptyList(),
            artifacts = emptyList(),
            state = TurnState.IDLE,
            ask = null,
            error = null,
            loadingConversation = false,
            historyProgress = 0,
            historyNotice = null,
            sending = false,
            stepsExpanded = emptyMap()
        )
    }

    suspend fun openConversation(id: String) {
        _ui.value = _ui.value.copy(
            activeId = id,
            draft = false,
            loadingConversation = true,
            historyProgress = 0,
            historyNotice = null,
            messages = emptyList(),
            artifacts = emptyList(),
            error = null,
            ask = null,
            // 清掉上一条会话遗留的 sending：否则 A 在跑时切到 B，输入栏仍是暂停按钮。
            // B 自己若在跑，state 会在下面从它的快照恢复成 RUNNING，不受影响。
            sending = false,
            state = TurnState.IDLE,
            activeTitle = _ui.value.conversations.firstOrNull { it.id == id }?.title ?: "云端会话",
            stepsExpanded = emptyMap()
        )
        settings.lastConversation = id
        val result = runCatching {
            withContext(Dispatchers.IO) { connection(id) }
        }
        result.onSuccess { conn ->
            // 期间用户可能已经切走，回放慢的时候尤其容易撞上。
            if (_ui.value.activeId != id) return@onSuccess
            val snap = conn.snapshot()
            _ui.value = _ui.value.copy(
                loadingConversation = false,
                historyProgress = 0,
                historyNotice = if (conn.loadTimedOut) HISTORY_TIMEOUT_NOTICE else null,
                messages = snap.messages,
                artifacts = snap.artifacts,
                state = snap.state,
                ask = snap.ask,
                error = snap.error,
                activeTitle = _ui.value.conversations.firstOrNull { it.id == id }?.title ?: _ui.value.activeTitle
            )
            // 重放收尾一律把「执行中」压成 IDLE，但云端可能正被别的设备驱动着。
            // 这一次对账决定输入栏是发送还是暂停，也决定要不要补后续输出。
            reconcileAfterOpen(id)
        }.onFailure { failure ->
            if (_ui.value.activeId != id) return@onFailure
            _ui.value = _ui.value.copy(
                loadingConversation = false,
                historyProgress = 0,
                error = readable(failure),
                state = TurnState.FAILED
            )
        }
    }

    /**
     * 重建连接并重新回放历史。
     *
     * [openConversation] 在连接还活着时会直接复用快照，拿不到更新的历史，
     * 所以「重新载入」「历史不完整时重试」必须先把旧连接关掉。
     */
    suspend fun reloadConversation(id: String) {
        connections.remove(id)?.close()
        openConversation(id)
    }

    fun closeConversation() {
        _ui.value = _ui.value.copy(
            activeId = null,
            draft = false,
            messages = emptyList(),
            artifacts = emptyList(),
            state = TurnState.IDLE,
            ask = null,
            error = null,
            historyProgress = 0,
            historyNotice = null,
            sending = false,
            stepsExpanded = emptyMap()
        )
    }

    fun send(prompt: String, attachments: List<PendingAttachment> = emptyList()) {
        if (prompt.isBlank() && attachments.isEmpty()) return
        // 本地确实还有一轮在跑：直接拦下。以前只看有没有文字，云端跑着也能发，
        // 新消息插进正在执行的轮次里，上下文就乱了。
        // 但「本地显示在跑」必须配上「流还活着」才算数：否则一条僵死残留就会把
        // 这条会话永久锁死（提示云端仍在运行，实际云端早结束了）。
        val currentId = _ui.value.activeId
        if (StateReconciler.isActive(_ui.value.state) &&
            (currentId == null || hasLiveTurn(currentId))
        ) {
            _events.tryEmit("上一轮还没收尾，消息未发送：可稍候，或点停止后再发")
            return
        }
        _ui.value = _ui.value.copy(sending = true, error = null, ask = null)
        scope.launch {
            // 发送目标在协程启动时锁定：用户中途切到别的会话，本轮仍归原会话，
            // 且错误/状态只写回原会话，不能污染当前显示的另一条会话。
            val targetId = _ui.value.activeId
            // 新会话的 id 在 try 里才产生，所以用可变变量记住真正在跑的会话，
            // finally 才能准确注销——按 activeId 注销的话，用户切走后这个 id 会永远留在集合里，
            // 那条会话就再也不会被对账、也不会补新输出。
            var turnId: String? = targetId
            try {
                val id = targetId ?: createConversation(prompt)
                turnId = id
                // 本地状态可能过期（重放收尾会把执行中压成 IDLE，云端其实还在跑）：
                // 发送前用服务端记录再确认一次，别把消息塞进别人的轮次。
                if (targetId != null && !confirmIdle(targetId)) {
                    applyIfActive(targetId) { copy(sending = false) }
                    _events.tryEmit("上一轮还没收尾，消息未发送：可稍候，或点停止后再发")
                    return@launch
                }
                inFlightTurns.add(id)
                val conn = withContext(Dispatchers.IO) { connection(id) }
                applyModel(conn)
                val (blocks, sourceBlocks) = buildPrompt(conn.session, prompt, attachments)
                conn.prompt(prompt, currentModel(), blocks, sourceBlocks, PROMPT_TIMEOUT)
                _events.tryEmit("本轮已结束")
            } catch (e: PromptWaitTimeout) {
                // 本地等待窗口到了，但云端任务可能还在跑（SSE 仍在推进度）。
                // 不标失败、不弹红色错误；界面状态由 reducer 依事件收敛。
                _events.tryEmit("云端任务仍在运行，可继续等待或点停止")
            } catch (_: TurnCancelled) {
                // 用户点了停止，或连接被对账接管：这一轮已经安静收尾，不当失败。
            } catch (e: Exception) {
                lastTurnEndAt = System.currentTimeMillis()
                applyIfActive(targetId) { copy(error = readable(e), state = TurnState.FAILED) }
            } finally {
                turnId?.let { inFlightTurns.remove(it) }
                if (_ui.value.activeId == targetId) _ui.value = _ui.value.copy(sending = false)
                runCatching { refreshConversations() }
            }
        }
    }

    /**
     * 只在 [id] 仍是当前会话时改 UI。
     *
     * 用户切走后，旧会话的后台 coroutine 仍会结束/报错；不判断就会把另一条
     * 会话的输入栏改成暂停、或把错误贴到人家页面上（1.3.3 实测问题 3）。
     */
    private inline fun applyIfActive(id: String?, block: AppUiState.() -> AppUiState) {
        val current = _ui.value
        if (id != null && current.activeId == id) _ui.value = block(current)
    }

    fun stop() {
        val id = _ui.value.activeId ?: return
        scope.launch {
            val live = connections[id]?.takeIf { it.alive }
            runCatching {
                if (live != null) {
                    live.cancelTurn()
                } else {
                    // 任务是在别处（网页端 / 另一台设备）发起的：本地没有活连接，
                    // 临时建一条只为下发 session/cancel，用完立刻关掉，不进缓存
                    // （没跑过 load 的连接被复用会得到空历史）。
                    withContext(Dispatchers.IO) {
                        val temp = AcpConnection(http, settings, sessionOf(id))
                        try {
                            temp.open()
                            temp.cancelTurn()
                            delay(600)
                        } finally {
                            temp.close()
                        }
                    }
                }
            }
            // 用户主动停止：给一个收敛窗口，别让服务端滞后的 running 立刻把按钮弹回来。
            lastTurnEndAt = System.currentTimeMillis()
            inFlightTurns.remove(id)
            applyIfActive(id) { copy(state = TurnState.STOPPED, sending = false) }
            // 云端收敛到停止需要一点时间；立刻对一次账，别让角标停在「进行中」。
            reconcile()
        }
    }

    fun answerQuestion(answer: AskAnswer) {
        val id = _ui.value.activeId ?: return
        val ask = _ui.value.ask ?: return
        val text = ask.answerText(answer).ifBlank { "（跳过）" }
        _ui.value = _ui.value.copy(ask = null, sending = true)
        // 走 resolve 的回答不会进 prompt 流程，云端也不会回显；不主动记一笔，
        // 界面上用户刚做的选择会凭空消失。
        val viaResolve = ask.requestId != null
        if (viaResolve) connections[id]?.recordAnswer(text)
        scope.launch {
            // 回答之后本轮仍在继续，和对账一样要算「在途」，否则会被服务端滞后的
            // waiting 状态盖回去，问题卡片可能重新弹出来。
            inFlightTurns.add(id)
            try {
                val conn = withContext(Dispatchers.IO) { connection(id) }
                if (viaResolve) conn.resolve(ask, answer)
                else {
                    val (blocks, source) = buildPrompt(conn.session, text, emptyList())
                    conn.prompt(text, currentModel(), blocks, source, PROMPT_TIMEOUT)
                }
            } catch (e: PromptWaitTimeout) {
                _events.tryEmit("云端任务仍在运行，可继续等待或点停止")
            } catch (_: TurnCancelled) {
                // 同上：停止不是错误。
            } catch (e: Exception) {
                applyIfActive(id) { copy(error = readable(e)) }
            } finally {
                inFlightTurns.remove(id)
                if (_ui.value.activeId == id) _ui.value = _ui.value.copy(sending = false)
            }
        }
    }

    fun deleteConversation(id: String) {
        scope.launch {
            runCatching { api.delete(id) }
                .onSuccess {
                    connections.remove(id)?.close()
                    sessions.remove(id)
                    if (_ui.value.activeId == id) closeConversation()
                    refreshConversations()
                    _events.tryEmit("会话已删除")
                }
                .onFailure { _events.tryEmit("删除失败：" + readable(it)) }
        }
    }

    fun renameConversation(id: String, title: String) {
        scope.launch {
            runCatching { api.rename(id, title) }
                .onSuccess {
                    if (_ui.value.activeId == id) _ui.value = _ui.value.copy(activeTitle = title)
                    refreshConversations()
                }
                .onFailure { _events.tryEmit("重命名失败：" + readable(it)) }
        }
    }

    suspend fun downloadArtifact(artifact: Artifact): Result<ByteArray> = runCatching {
        api.download(artifact, _ui.value.activeId)
    }

    // ---------- 工作区（云端沙箱文件） ----------

    suspend fun loadWorkspace(conversationId: String, path: String? = null) {
        val target = path ?: settings.workspacePath(conversationId)
        _workspace.value = _workspace.value.copy(
            conversationId = conversationId,
            path = target,
            loading = true,
            error = null
        )
        val result = runCatching {
            withContext(Dispatchers.IO) {
                withSandbox(conversationId) { session, endpoint ->
                    Triple(endpoint, session.token, sandbox.list(endpoint, session.token, target))
                }
            }
        }
        result.onSuccess { (endpoint, _, entries) ->
            settings.setWorkspacePath(conversationId, target)
            _workspace.value = _workspace.value.copy(
                entries = entries,
                loading = false,
                error = null,
                diagnostics = "文件网关：$endpoint\n路径：$target\n" +
                    "列目录：filesystem.Filesystem/ListDir\n条目：${entries.size}"
            )
        }.onFailure { failure ->
            _workspace.value = _workspace.value.copy(
                loading = false,
                error = readable(failure),
                diagnostics = sessionDiagnostics(conversationId) + "\n" + (failure.message ?: "")
            )
        }
    }

    /** 丢弃缓存的会话、重新取接入点后重试（沙箱过期 / 网关 404 时用）。 */
    fun reloadSessionAndWorkspace(conversationId: String, path: String? = null) {
        scope.launch {
            sessions.remove(conversationId)
            loadWorkspace(conversationId, path)
        }
    }

    /** 供界面直接调用的「不等待」入口。 */
    fun scopeLoad(conversationId: String, path: String) {
        scope.launch { loadWorkspace(conversationId, path) }
    }

    suspend fun readWorkspaceFile(conversationId: String, path: String): Result<ByteArray> = runCatching {
        withContext(Dispatchers.IO) {
            withSandbox(conversationId) { session, endpoint ->
                sandbox.read(endpoint, session.token, path)
            }
        }
    }

    suspend fun uploadToWorkspace(conversationId: String, dir: String, name: String, bytes: ByteArray): Result<String> =
        runCatching {
            withContext(Dispatchers.IO) {
                withSandbox(conversationId) { session, endpoint ->
                    sandbox.write(endpoint, session.token, SandboxPath.join(dir, name), bytes)
                }
            }
        }

    suspend fun deleteWorkspaceEntry(conversationId: String, path: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            withSandbox(conversationId) { session, endpoint ->
                sandbox.remove(endpoint, session.token, path)
            }
        }
    }

    suspend fun createWorkspaceFolder(conversationId: String, dir: String, name: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            withSandbox(conversationId) { session, endpoint ->
                sandbox.mkdir(endpoint, session.token, SandboxPath.join(dir, name))
            }
        }
    }

    /**
     * 401/403/404 时重新拉取会话接入点再试一次：官方客户端在沙箱重建/续期后
     * 会刷新 session，旧的 49983 端点随之失效，直接重试同一地址只会继续 404。
     */
    private suspend fun <T> withSandbox(
        conversationId: String,
        block: suspend (CloudSession, String) -> T
    ): T {
        var session = sessionOf(conversationId)
        var endpoint = session.dataEndpoint ?: throw CloudException("该会话没有可用的沙箱文件服务地址")
        return try {
            block(session, endpoint)
        } catch (e: CloudException) {
            if (e.code !in setOf(401, 403, 404)) throw e
            sessions.remove(conversationId)
            session = api.fetchSession(conversationId).also { sessions[conversationId] = it }
            endpoint = session.dataEndpoint ?: throw CloudException("重新获取会话后仍无文件网关")
            block(session, endpoint)
        }
    }

    private suspend fun sessionDiagnostics(conversationId: String): String {
        val session = try {
            sessions[conversationId] ?: sessionOf(conversationId)
        } catch (e: Exception) {
            return "会话接入点读取失败：" + readable(e)
        }
        return "ACP 接入点：${session.link}\n文件网关：${session.dataEndpoint ?: "未解析到"}\n" +
            "沙箱 ID：${session.sandboxId ?: "未下发"}\ncwd：${session.cwd ?: "/workspace"}"
    }

    fun shutdown() {
        connections.values.forEach { it.close() }
        connections.clear()
        scope.cancel()
    }

    // ---------- 内部 ----------

    private fun currentModel(): String =
        settings.selectedModel.ifBlank { _ui.value.selectedModel }.ifBlank { AppSettings.DEFAULT_MODEL }

    private suspend fun createConversation(prompt: String): String {
        val (id, session) = api.createConversation(prompt, currentModel())
        session?.let { sessions[id] = it }
        _ui.value = _ui.value.copy(
            activeId = id,
            draft = false,
            activeTitle = prompt.take(24).ifBlank { "新会话" }
        )
        settings.lastConversation = id
        return id
    }

    private suspend fun sessionOf(id: String): CloudSession {
        sessions[id]?.let { return it }
        return api.fetchSession(id).also { sessions[id] = it }
    }

    private suspend fun connection(id: String): AcpConnection {
        connections[id]?.takeIf { it.alive }?.let { return it }
        val session = sessionOf(id)
        val conn = AcpConnection(http, settings, session)
        conn.onUpdate = { snapshot -> publish(id, snapshot) }
        conn.onModels = { models, current -> publishModels(id, models, current) }
        conn.onReplayProgress = { count -> publishReplayProgress(id, count) }
        // 先登记再 load：并发调用不会重复建连，也能被「重新载入」正确关掉。
        connections[id] = conn
        try {
            withContext(Dispatchers.IO) {
                conn.open()
                conn.initializeAndLoad(settings.historyTimeoutMs)
            }
        } catch (e: Exception) {
            // 只有「连不上」才算失败；历史没放完由 loadTimedOut 单独表达。
            connections.remove(id, conn)
            conn.close()
            throw e
        }
        return conn
    }

    /**
     * 回放进度：只更新一行数字，不碰 messages。
     *
     * 把历史逐条塞进列表会让界面看起来在「重放当时的步骤」（消息往上冒、
     * 工具转圈），用户要的是加载完直接呈现最新状态，所以这里只报进度。
     */
    private fun publishReplayProgress(id: String, count: Int) {
        val current = _ui.value
        if (current.activeId != id) return
        if (current.historyProgress == count && current.loadingConversation) return
        _ui.value = current.copy(historyProgress = count, loadingConversation = true)
    }

    private fun publish(id: String, snapshot: AcpSnapshot) = synchronized(uiLock) {
        val current = _ui.value
        if (current.activeId != id) return

        // 记下「刚刚收尾」的时刻：对账据此给服务端一个收敛窗口（见 SETTLE_MS）。
        if (StateReconciler.isActive(current.state) && !StateReconciler.isActive(snapshot.state)) {
            lastTurnEndAt = System.currentTimeMillis()
        }

        // 会话列表只关心状态角标 / 产物 / 待确认，纯文本分片不该重建它，
        // 否则会话页会跟着流式输出一起抖动。
        val listDirty = current.state != snapshot.state ||
            current.ask != snapshot.ask ||
            current.artifacts !== snapshot.artifacts

        var next = current.copy(
            messages = snapshot.messages,
            artifacts = snapshot.artifacts,
            state = snapshot.state,
            error = snapshot.error,
            ask = snapshot.ask,
            loadingConversation = false,
            historyProgress = 0
        )

        if (listDirty) {
            next = next.copy(
                conversations = next.conversations.map {
                    if (it.id == id) {
                        it.copy(state = snapshot.state, artifacts = snapshot.artifacts, ask = snapshot.ask)
                    } else it
                }
            )
        }

        // 原实现连续两次 `_ui.value = copy(...)`，一次推送会触发两轮重组。
        // 这里合成一次，并在内容确实没变时直接跳过。
        val unchanged = current.messages.size == snapshot.messages.size &&
            current.messages.lastOrNull() == snapshot.messages.lastOrNull() &&
            current.state == snapshot.state &&
            current.ask == snapshot.ask &&
            current.error == snapshot.error &&
            !current.loadingConversation &&
            current.historyProgress == 0
        if (unchanged) return

        _ui.value = next
    }

    private fun applyModel(conn: AcpConnection) {
        val model = currentModel()
        runCatching { conn.setModel(model) }
            .onFailure { _events.tryEmit("模型「$model」设置失败：" + readable(it)) }
    }

    /** 把本地附件送进沙箱，让云端 agent 直接用路径读写；失败时退回内联内容块。 */
    private suspend fun buildPrompt(
        session: CloudSession,
        text: String,
        attachments: List<PendingAttachment>
    ): Pair<JSONArray, JSONArray> {
        val blocks = JSONArray()
        val sourceBlocks = JSONArray()
        val sandboxPaths = mutableListOf<Pair<String, String>>()
        val model = currentModel()

        for (attachment in attachments) {
            val uploaded = runCatching {
                val endpoint = session.dataEndpoint ?: throw CloudException("无文件网关")
                val dir = (session.cwd ?: "/workspace").trimEnd('/') + "/uploads/cb-" + System.currentTimeMillis()
                sandbox.write(endpoint, session.token, "$dir/${attachment.name}", attachment.bytes)
            }.getOrNull()

            val block = if (uploaded != null) {
                sandboxPaths += attachment.name to uploaded
                JSONObject()
                    .put("type", "resource_link")
                    .put("name", attachment.name)
                    .put("title", attachment.name)
                    .put("uri", uploaded)
                    .put("mimeType", attachment.mimeType)
                    .put(
                        "_meta",
                        JSONObject()
                            .put("filename", attachment.name)
                            .put("size", attachment.bytes.size)
                            .put("displayText", attachment.name)
                            .put("attachmentTransport", "cloud")
                            .put("codebuddy.ai", JSONObject().put("sandboxPath", uploaded).put("model", model))
                    )
            } else if (attachment.mimeType.startsWith("image/") && attachment.bytes.size <= MAX_INLINE) {
                JSONObject()
                    .put("type", "image")
                    .put("mimeType", attachment.mimeType)
                    .put("data", Base64.getEncoder().encodeToString(attachment.bytes))
            } else if (attachment.bytes.size <= MAX_INLINE) {
                JSONObject()
                    .put("type", "resource")
                    .put(
                        "resource",
                        JSONObject()
                            .put("uri", attachment.name)
                            .put("mimeType", attachment.mimeType)
                            .put("blob", Base64.getEncoder().encodeToString(attachment.bytes))
                    )
            } else {
                throw CloudException("附件「${attachment.name}」上传失败，且体积超出内联上限")
            }
            blocks.put(block)
            sourceBlocks.put(block)
        }

        if (text.isNotBlank()) {
            val textBlock = JSONObject()
                .put("type", "text")
                .put("text", text)
                .put("_meta", JSONObject().put("codebuddy.ai", JSONObject().put("model", model)))
            blocks.put(textBlock)
            sourceBlocks.put(JSONObject().put("type", "text").put("text", text))
        }
        if (sandboxPaths.isNotEmpty()) {
            val lines = sandboxPaths.joinToString("\n") { (name, path) -> "- $name: $path" }
            blocks.put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        "<attachment_sandbox_paths>\n以下附件已存放在沙箱本地路径，请直接读写这些路径。\n$lines\n</attachment_sandbox_paths>"
                    )
                    .put("_meta", JSONObject().put("codebuddy.ai/hiddenPromptContext", "attachment-sandbox-paths")
                        .put("codebuddy.ai", JSONObject().put("model", model)))
            )
        }
        return blocks to sourceBlocks
    }

    private fun readable(error: Throwable): String {
        val message = error.message.orEmpty().take(160)
        return when (error) {
            is SessionExpiredException -> "登录已过期，请重新登录"
            is java.net.UnknownHostException -> "网络不可用，请检查网络"
            is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
            is javax.net.ssl.SSLException -> "网络连接不安全，请检查网络"
            is CloudException -> message.ifBlank { "云端返回异常" }
            else -> message.ifBlank { error.javaClass.simpleName }
        }
    }

    private companion object {
        const val PROMPT_TIMEOUT = 10 * 60_000L
        const val MAX_INLINE = 8 * 1024 * 1024

        /** 回放超时后的提示：说清「已显示的部分仍可用」，并给出重试入口。 */
        const val HISTORY_TIMEOUT_NOTICE =
            "历史记录未全部载入（云端回放超时），当前显示的是已经收到的部分"

        /** 补历史的最小间隔；持续在跑时按倍数退避，最长 [RESYNC_MAX_GAP]。 */
        const val RESYNC_MIN_GAP = 8_000L
        const val RESYNC_MAX_GAP = 60_000L

        /** 全量列表（其它会话的状态角标）刷新间隔：比状态对账稀得多。 */
        const val FULL_LIST_GAP = 60_000L

        /** 本地收尾后的静默窗口：等云端记录从 running 收敛，避免状态回弹。 */
        const val SETTLE_MS = 8_000L

        /**
         * 「本地这一轮还活着」的事件新鲜度上限。
         *
         * 长任务里两次工具调用之间几十秒不出事件是正常的，取 90 秒足够宽松；
         * 超过它还没有任何事件，就认为本地这条流已经僵死，交给云端记录接管。
         */
        const val STREAM_STALE_MS = 90_000L
    }
}
