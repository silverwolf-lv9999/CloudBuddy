package cn.cloudbuddy.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /**
     * 当前**会话**选中的模型 ID。
     *
     * 注意是会话级而不是全局：官方每个会话各自认模型，切会话要跟着变。
     * 草稿会话（还没建）时它等于「新会话的默认模型」。
     */
    val selectedModel: String = "",
    /** 当前会话的推理引擎，见 [AppSettings.ENGINE_WB] / [AppSettings.ENGINE_CUSTOM]。 */
    val engine: String = AppSettings.ENGINE_WB,
    /**
     * 用户自己维护的本机模型清单（含显示名与按模型覆盖的参数）。
     *
     * 放进 UI 状态而不是只留在设置里，是因为模型弹窗、模型管理都要按它渲染：
     * SharedPreferences 的改动不会触发重组。
     */
    val customModels: List<CustomModel> = emptyList(),
    /**
     * 最近一次从端点拉到的候选池（`GET /models` 的原始结果，只在内存）。
     *
     * 与 [customModels] 分开：候选池是「端点上有哪些」，我的模型是「我要用哪些」。
     * 混成一个列表的话，重新拉取会把用户删掉的模型又灌回来。
     */
    val customCandidates: List<String> = emptyList(),
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
    /** 提示词片段（编码规范、项目约定这类可复用的补充，见 [PromptPreset]）。 */
    val promptPresets: List<PromptPreset> = emptyList(),
    /** 本机技能（只喂本机会话，与云端技能是两套）。 */
    val localSkills: List<LocalSkill> = emptyList(),
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
 * 终端里的一个标签页。
 *
 * 一个标签 = 一份独立的命令记录 + 一个独立的执行槽。切标签不打断在跑的命令，
 * 跟本地终端开多个窗口是一个意思：`npm install` 挂在那儿，你去另一个标签看日志。
 *
 * 沙箱是共享的（同一个会话同一个容器），标签之间不隔离进程——`cd` 也不会串，
 * 因为每条命令都是独立 `sh -c`，本来就没有持久的 shell 状态。
 */
data class TerminalTab(
    /** 会话内唯一；也是命令日志的分区键。 */
    val id: Long,
    val title: String,
    /** 命令记录，按时间正序。 */
    val entries: List<CmdEntry> = emptyList(),
    /** 这个标签里是否有一条命令正在跑。 */
    val running: Boolean = false,
    /** 正在跑的那条命令（压成单行），用于输入框上方提示。 */
    val runningCommand: String? = null,
    /** 正在跑那条命令的实时输出。 */
    val liveOutput: String = "",
)

/**
 * 命令行窗口的状态。
 *
 * 只装**当前会话**的东西：切会话时整块换掉，不给用户看到别的沙箱的输出——
 * 那比看不到更误导。
 *
 * 结构上按标签分组，但保留了 `entries` / `running` 这类"当前标签"的便捷属性：
 * 绝大多数 UI 只关心眼前这个标签，让它们到处写 `state.active?.entries` 只会更啰嗦。
 */
data class TerminalState(
    val conversationId: String? = null,
    val tabs: List<TerminalTab> = emptyList(),
    val activeTabId: Long = 0L,
    /** 上翻历史（跨标签共享，跟 shell 的 history 一个意思）。 */
    val history: List<String> = emptyList(),
    /** 一次性提示，比如「沙箱还没准备好」。 */
    val notice: String? = null,
) {
    val active: TerminalTab? get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
    val entries: List<CmdEntry> get() = active?.entries ?: emptyList()
    val running: Boolean get() = active?.running == true
    val runningCommand: String? get() = active?.runningCommand
    val liveOutput: String get() = active?.liveOutput ?: ""
}

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

    /** 自定义模型（本机 agent）的执行通道，与 ACP 完全独立。 */
    val sandboxExec = SandboxExec(http, settings)
    private val customAgents: AgentRuntime = AgentSupport.create(sandboxExec, settings)

    /** 自定义端点的模型清单（`GET /models`），只在设置页与模型弹窗用。 */
    private val catalog = CustomModelCatalog()

    /**
     * 设置页的「测试连接」。
     *
     * 复用 [http] 所在的 OkHttp 连接池，避免为一次探测新建线程池。
     */
    suspend fun testCustomEndpoint(): CustomEndpointProbe.Result =
        CustomEndpointProbe(http.api).probe(settings.customModel())

    /**
     * 正在跑的自定义模型轮次，按会话记 job。
     *
     * 必须留着 job 引用：[stop] 要能真正取消它。只改 UI 状态的话，
     * 协程还在跑、模型还在推理、工具还在沙箱里执行，用户点了停止却照样烧钱。
     */
    private val customJobs = ConcurrentHashMap<String, Job>()

    /** 会话还没建出来时的临时 job 句柄，建完迁进 [customJobs]。 */
    @Volatile
    private var pendingCustomJob: Job? = null

    /** 每个会话当前那条 TOOL 占位消息的 id，用来把「运行中」改成「已完成」。 */
    private val toolMsgIds = ConcurrentHashMap<String, String>()

    /**
     * 本地消息 id 的序号。
     *
     * 只用 `System.nanoTime()` 会撞：工具调用密集时两条消息可能落在同一纳秒，
     * 撞了之后 [finishToolPlaceholder] 会把另一条也改成「已完成」。
     */
    private val localMsgSeq = java.util.concurrent.atomic.AtomicLong()

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
     * 自愈簿记：会话 id → 首次观察到「运行中」的时刻 / 上次动手指令时刻 / 已尝试次数。
     *
     * 见 [scheduleHeal]：服务端的状态位只有重新 load 一次才会收敛，这里记的就是
     * 「观察了多久」「试过几次」，保证不会反复去戳另一台设备真正在跑的会话。
     */
    private val stuckSince = ConcurrentHashMap<String, Long>()
    private val healAt = ConcurrentHashMap<String, Long>()
    private val healTries = ConcurrentHashMap<String, Int>()
    @Volatile private var healing = false

    /** 已经清过一次的本机会话（见 [scheduleHeal] 里对本机会话的特殊处理）。 */
    private val cleanedLocal = ConcurrentHashMap.newKeySet<String>()

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
        return streamAlive(id)
    }

    /** 这条会话的本地连接最近还有没有事件进来（不管是谁发起的那一轮）。 */
    private fun streamAlive(id: String): Boolean =
        connections[id]?.isStreamActive(STREAM_STALE_MS) == true

    /**
     * 重进会话时这一轮还挂着未完成的工具调用。
     *
     * 用户实测：工具调用进行中刷新 / 重进，角标直接跳「已完成」，后续消息也不再出现。
     * 那一刻服务端并没有 prompt 在途，`completed` 只是默认上报。所以这里既不能采纳
     * 远端终态，也不能因为「状态没跨越」就不去补历史。
     */
    private fun openTurn(id: String): Boolean = connections[id]?.hasOpenTurn() == true

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

    /**
     * 云端记录的「该会话当前模型」，按会话隔离。
     *
     * **模型是跟着会话存在云端的**（`session/set_model` 写的就是这个），
     * 所以官方 App 里改一次，网页端和我们这边打开都能看到同一份。
     * 之前只存一个全局值、还让全局 `selectedModel` 优先，等于无视云端设置：
     * 网页端换了模型，客户端仍显示旧模型，且每次发消息都会把云端模型改回去。
     */
    private val acpCurrentModels = ConcurrentHashMap<String, String>()

    private val _ui = MutableStateFlow(
        AppUiState(
            loggedIn = settings.loggedIn && settings.cookies.hasAny(),
            stepsCollapsed = settings.collapseSteps,
            foldMode = settings.fold,
            accounts = settings.accounts(),
            activeAccountId = settings.activeAccountId,
            mcpServers = settings.mcp.list(),
            promptPresets = settings.prompts.ordered(),
            localSkills = settings.localSkills.list(),
            customModels = settings.customModels
        )
    )
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    private val _workspace = MutableStateFlow(WorkspaceState())
    val workspace: StateFlow<WorkspaceState> = _workspace.asStateFlow()

    /** 命令行窗口：按会话的命令记录 + 手动执行状态。 */
    private val _terminal = MutableStateFlow(TerminalState())
    val terminal: StateFlow<TerminalState> = _terminal.asStateFlow()

    /** 命令日志的存储本体，agent 工具调用和手动执行都往这里写。 */
    private val commandLog = CommandLog()

    /** 终端里敲过的命令，新的在前；跨会话共享，跟 shell 的 history 一个意思。 */
    private val cmdHistory = ArrayDeque<String>()

    /**
     * 手动命令的执行槽，一个标签一个。
     *
     * 标签之间互不打断：`npm install` 在标签 1 跑着，标签 2 照样能敲命令。
     * 同一标签内仍然一次只跑一条——`sh -c` 没有作业控制，并发跑只会让输出串台。
     */
    private class ShellRun(
        val convId: String,
        val tabId: Long,
        val command: String,
        val startedAt: Long,
    ) {
        /** 已收到的输出。停止时要靠它落盘，不能只留在 UI 状态里。 */
        val live = StringBuilder()

        @Volatile
        var job: Job? = null

        /** 用户点过停止。协程被取消后不一定立刻死，靠它压住重复记录。 */
        @Volatile
        var cancelled = false
    }

    /** 按标签 id 索引的执行槽。 */
    private val shellRuns = java.util.concurrent.ConcurrentHashMap<Long, ShellRun>()

    /** 每个会话开过哪些标签（id + 标题）。切回来时靠它恢复，屏幕内容从命令日志取。 */
    private val tabMeta = HashMap<String, List<Pair<Long, String>>>()

    /** 每个会话最后停在哪个标签。 */
    private val activeTabOf = HashMap<String, Long>()

    /**
     * 标签 id 全局自增。
     *
     * 不用「每会话从 1 开始」：tabId 是 [shellRuns] 的键，跨会话重号会让
     * 两个标签抢同一个执行槽。
     */
    private var nextTabSeq = 0L

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
        // 精简版没有自定义引擎：把可能残留在偏好里的自定义模式压回云端。
        // 否则设置页不显示引擎选择、会话却还在按本机模式发问，用户只会看到
        // 一条「自定义模型未配置完整」，找不到能改的地方。
        if (!Edition.full) settings.engineMode = AppSettings.ENGINE_WB
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
        // 本机会话在云端只有一条空壳记录，没有「真相」可对：推理在本机，状态也只能算在本机。
        // 拿它的云端角标去对账，就会把一条早就没在跑的会话永久钉在「执行中」
        // （而且每次对账还会去建一条临时 ACP 连接重放空历史）。
        val activeLocal = isLocalConv(activeId)

        var activeRemote: Conversation? = null
        var list: List<Conversation>? = null
        if (shouldList) {
            lastFullListAt = now
            list = runCatching { api.listConversations() }.getOrNull()
            activeRemote = list?.firstOrNull { it.id == activeId }
            // 列表里其它会话的滞留「运行中」也在这里修——用户可能一直停在会话页，
            // 看不到列表，自然不会「点进去」把它点活。
            list?.let { scheduleHeal(it) }
        }
        if (activeRemote == null && !activeLocal) {
            activeRemote = runCatching { api.batchGet(listOf(activeId)) }.getOrNull()?.firstOrNull()
        }

        val latest = _ui.value
        // 本地刚收尾的那几秒，服务端记录往往还停在 running；此刻采纳会把状态弹回
        // 「进行中」、暂停按钮重新出现，看着像任务又活了。给服务端一个收敛窗口。
        val settling = System.currentTimeMillis() - lastTurnEndAt < SETTLE_MS
        // 重进时历史停在未完成工具上：这一刻的终态多半是「没有 prompt 在途」的默认上报，
        // 采纳它会让角标跳「已完成」、之后也不再补消息。等补历史收到底再认。
        val suspectEnd = openTurn(activeId) && activeRemote != null &&
            !StateReconciler.isActive(activeRemote.state)
        val action = if (!activeLocal && activeRemote != null && !suspectEnd &&
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
            when {
                remote.id == activeId -> remote.copy(
                    state = targetState,
                    artifacts = latest.artifacts.ifEmpty { remote.artifacts },
                    ask = latest.ask
                )
                isLocalConv(remote.id) -> remote.asLocal()
                else -> remote
            }
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
        } else if (activeRemote != null && !streamAlive(activeId) && !hasLiveTurn(activeId) &&
            (StateReconciler.isActive(latest.state) || openTurn(activeId))
        ) {
            // 本地看着还在跑、可这条连接早就不推事件了（重进会话后的常态）：
            // 定期重放历史，把工具跑完之后的消息捞回来。没有这一步，界面会永远
            // 停在重进那一刻的内容上。
            scheduleResync(activeId, activeRemote)
        }
        if (activeLocal) return customJobs[activeId]?.isActive == true
        return StateReconciler.isActive(committedState) || StateReconciler.isActive(activeRemote?.state)
    }

    /**
     * 一条本机会话在列表里的真实样子。
     *
     * 状态只在本机：进程里有没有它的 job 在跑。云端那条记录从没收到过 prompt，
     * 它的状态位要么恒为 null，要么是这条会话还是云会话时留下的残值——采信了
     * 就会看到「本机」标签旁边挂着「云端执行中」，而且永远不消失。
     */
    private fun Conversation.asLocal(): Conversation = copy(
        state = if (customJobs[id]?.isActive == true) TurnState.RUNNING else TurnState.IDLE,
        ask = null,
        // 云端记的模型对本机会话没有意义，列表里显示本机那套才有信息量。
        model = settings.customModelFor(id).takeIf { it.isNotBlank() } ?: model
    )

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
     * 这里是唯一的重操作：重建连接会重放整段历史。所以几道闸：正在本地发送时不补、
     * 本地连接还在收事件时不补（重建会掐断实时流，历史还会覆盖正在流式的内容）、
     * `updatedAt` 没前进不补、两次之间按倍数退避到 60 秒。
     *
     * 例外是 **「本地静默但云端在跑」**：服务端只在有 prompt 在途时才往连接上推事件，
     * 重进会话后这条流已经不推了，光等 `updatedAt` 前进永远等不到——用户看到的就是
     * 「工具调用进行中重进，之后的消息一条都刷不出来」。这种情况按退避定期重放历史。
     */
    private fun scheduleResync(id: String, remote: Conversation) {
        if (resyncing) return
        if (_ui.value.activeId != id || hasLiveTurn(id)) return
        val alive = streamAlive(id)
        val newContent = StateReconciler.shouldResync(remote.updatedAt, syncedAt[id] ?: 0L, remote.state)
        val catchUp = !alive && (StateReconciler.isActive(remote.state) || openTurn(id))
        if (!newContent && !catchUp) return
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
        // 本机会话没有云端历史可补：这里替换 messages 的语义对它就是「清空」。
        if (_ui.value.activeId != id || hasLiveTurn(id)) return false
        if (isLocalConv(id)) return false
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
        val fetched = runCatching { api.batchGet(listOf(id)) }.getOrNull()?.firstOrNull() ?: return
        syncedAt[id] = fetched.updatedAt
        if (_ui.value.activeId != id) return
        // 服务端记录若是滞留的「运行中」，打开时就清掉：否则输入栏直接变成「停止」，
        // 用户面对的是一个早就不存在的任务（1.4.0 线上反馈）。
        val remote = settleIfStale(id, fetched)
        val current = _ui.value
        if (current.activeId != id) return
        val action = StateReconciler.decide(current.state, remote.state, hasLiveTurn(id))
        // 回放结束时这轮还挂着未完成工具、而服务端已经报终态：多半是「没有 prompt 在途」
        // 的默认上报，不是真跑完。此刻采纳会把角标写成「已完成」，还挡住后面的补历史。
        val suspectEnd = openTurn(id) && !StateReconciler.isActive(remote.state)
        // 刚载入完，消息是最新的：只采纳状态，不再触发一次重建。
        if (action != StateReconciler.Action.NONE && !suspectEnd) {
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
        // 复用的连接可能早就不推事件了（在列表页停留过、或在会话间来回切）：
        // 打开时立刻补一次历史，别让用户看到几分钟前的旧内容。
        if (!streamAlive(id) && !hasLiveTurn(id) &&
            (StateReconciler.isActive(remote.state) || openTurn(id))
        ) {
            scheduleResync(id, remote)
        }
    }

    /**
     * 发送前的最后一道闸：服务端记录里若仍是执行中/等待，就拒绝发送。
     *
     * 查不到就放行——网络抖动不该把正常发送也挡住；能查到但状态不对才是真问题。
     *
     * 1.4.1：拒绝之前先做一次自愈（load + cancel）。用户实测「旧客户端加载完历史就正常」，
     * 那是旧版本地把 running 擦掉了，服务端状态位并没有跟着变；新版采纳远端，于是重进
     * 多少次都还是「运行中」。所以清不掉时不再永久拦截——见下面的降级放行。
     */
    private suspend fun confirmIdle(id: String): Boolean {
        val fetched = runCatching { api.batchGet(listOf(id)) }.getOrNull()?.firstOrNull() ?: return true
        val remote = settleIfStale(id, fetched)
        if (!StateReconciler.isActive(remote.state)) {
            // 从假「运行中」里救回来了：说一句，免得用户以后还在等这个状态。
            if (StateReconciler.isActive(fetched.state)) _events.tryEmit("已清理该会话滞留的运行状态")
            return true
        }
        // 还是 running：只有「本地还在收事件」或「内容最近还在动」才算真在跑。
        // 两样都没有就不能再拦——状态位不可信时把用户永久锁在门外，比塞错一轮更糟：
        // 真在跑的话服务端会自己拒绝这条 prompt，错误会照实显示出来。
        val moving = System.currentTimeMillis() - remote.updatedAt < StateReconciler.HEAL_IDLE_MS
        if (streamAlive(id) || moving) {
            applyIfActive(id) { copy(state = remote.state, sending = false) }
            return false
        }
        _events.tryEmit("云端状态位仍显示运行中，已强制发送")
        return true
    }

    /**
     * 「滞留的运行中」就地清一次，返回收敛后的服务端记录。
     *
     * 判定条件全在 [StateReconciler.shouldHeal]：本机没有在途的一轮、内容已经很久没动。
     * 观察窗口这里不设——调用它的时候用户正在等（发送/打开），再让他等两分钟没道理。
     * 清不掉就原样返回，让上层继续按「云端在跑」处理。
     */
    private suspend fun settleIfStale(id: String, remote: Conversation): Conversation {
        if (!StateReconciler.isActive(remote.state)) return remote
        // 本机在途的一轮、或本地连接上还有事件在流进来（别的设备驱动时也会流）：
        // 都说明它真的在跑，不碰。
        if (hasLiveTurn(id) || streamAlive(id)) return remote
        // 重进时历史停在未完成工具上：任务确实还挂在工具里，这时候「5 分钟没动」
        // 是长工具调用的常态，自愈会 load + cancel，等于把用户正在跑的任务砍掉。
        if (openTurn(id)) return remote
        val staleMs = System.currentTimeMillis() - remote.updatedAt
        if (!StateReconciler.shouldHeal(
                remoteActive = true,
                localTurnInFlight = false,
                staleMs = staleMs,
                observedMs = StateReconciler.HEAL_OBSERVE_MS,
                tries = 0
            )
        ) return remote
        healStaleRun(id)
        return runCatching { api.batchGet(listOf(id)).firstOrNull() }.getOrNull() ?: remote
    }

    /**
     * 自愈：把服务端滞留的「运行中」清掉。
     *
     * 线上证据（1.4.0）：某条会话在列表里长期挂「运行中」，本机没在跑、也没收到事件；
     * 用户在**旧版客户端**里点进这条会话，它恢复正常，切回新客户端也跟着正常了。
     * 也就是说这个状态位是服务端写的「还有 agent 挂着这条会话」，`session/load`
     * 一挂上就会收敛——不需要用户真去看它。
     *
     * 所以这里对「看起来僵住」的会话建一条临时连接 load 一次就关，再回读真实角标。
     * 一次只处理一条，同一条有冷却和次数上限，且要求内容五分钟没动过，
     * 避免打断另一台设备真正在跑的任务。
     */
    private fun scheduleHeal(list: List<Conversation>) {
        if (!foreground || healing || list.isEmpty()) return
        val now = System.currentTimeMillis()
        val activeId = _ui.value.activeId
        // 当前打开的会话不在这里处理（它有活连接，发送前由 confirmIdle 负责）。
        val running = list.filter { StateReconciler.isActive(it.state) && it.id != activeId }
        val ids = running.map { it.id }.toSet()
        // 只给「在跑」的会话保留观察起点与次数：收敛后清零，下次再僵住重新计时。
        stuckSince.keys.retainAll(ids)
        healTries.keys.retainAll(ids)
        running.forEach { stuckSince.putIfAbsent(it.id, now) }

        val target = running.firstOrNull { conv ->
            // 本机会话只清理**一次**：它的云端状态我们根本不采信（见 [asLocal]），
            // 但 1.5.4 及更早建出来的会话可能真在服务端挂着一个没模型的空轮次，
            // 清一次值得；清不掉就算了，反复建临时 ACP 连接纯属白发请求。
            (!isLocalConv(conv.id) || conv.id !in cleanedLocal) &&
                now - (healAt[conv.id] ?: 0L) >= HEAL_GAP_MS &&
                StateReconciler.shouldHeal(
                    remoteActive = true,
                    localTurnInFlight = hasLiveTurn(conv.id) || streamAlive(conv.id),
                    staleMs = now - conv.updatedAt,
                    observedMs = now - (stuckSince[conv.id] ?: now),
                    tries = healTries[conv.id] ?: 0
                )
        } ?: return

        healing = true
        healAt[target.id] = now
        healTries[target.id] = (healTries[target.id] ?: 0) + 1
        if (isLocalConv(target.id)) cleanedLocal.add(target.id)
        scope.launch {
            val ok = healStaleRun(target.id)
            healing = false
            // 收敛后再取一次真实角标：不能让列表停在假的「运行中」。
            if (ok) runCatching { refreshConversations() }
        }
    }

    /**
     * 临时连接：先 load 一次，再补一次 cancel。
     *
     * 只有 load 不够——用户实测「旧客户端加载完历史就正常了」，那是旧版**本地**把 running
     * 擦掉了（它没有打开后的对账），服务端状态位其实还挂着；新版老老实实采纳远端，
     * 于是重进多少次都还是「运行中」。所以这里在 load 之后补一次 `session/cancel`：
     * 这一轮在服务端早就没人等了，cancel 是安全回收。
     *
     * 失败就当没发生过，下一次刷新还有机会。
     */
    private suspend fun healStaleRun(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = AcpConnection(http, settings, sessionOf(id))
            try {
                conn.open()
                conn.initializeAndLoad(HEAL_LOAD_TIMEOUT)
                conn.resetRemoteTurn()
            } finally {
                conn.close()
            }
        }.isSuccess
    }

    /**
     * 手动「解除卡住」：用户点一下就把这条会话的运行状态强制收敛。
     *
     * 自动判定再保守也会有漏（比如内容一直在小幅更新、或尝试次数用完了），
     * 这时必须留一条 100% 能自救的路，而不是让人只能新建会话。
     */
    fun forceResetConversation(id: String) {
        scope.launch {
            val ok = healStaleRun(id)
            if (ok) {
                inFlightTurns.remove(id)
                // 本地这条连接上的旧状态也一并作废，下次打开就是干净的。
                connections.remove(id)?.close()
                applyIfActive(id) { copy(state = TurnState.STOPPED) }
                _events.tryEmit("已请求云端结束该会话的运行状态")
            } else {
                _events.tryEmit("清理失败：云端没有响应，可稍后再试")
            }
            runCatching { refreshConversations() }
        }
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
        acpCurrentModels.clear()
        settings.signOut()
        // 折叠开关是界面偏好，不随登录态清空——重置成全量默认值会和偏好对不上。
        _ui.value = AppUiState(
            booting = false,
            loggedIn = false,
            stepsCollapsed = settings.collapseSteps,
            foldMode = settings.fold,
            accounts = settings.accounts(),
            activeAccountId = settings.activeAccountId,
            mcpServers = settings.mcp.list(),
            promptPresets = settings.prompts.ordered(),
            localSkills = settings.localSkills.list(),
            customModels = settings.customModels
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

    /** 本机会话是否用这条 MCP（与云端下发是两个独立的开关）。 */
    fun setMcpLocal(id: String, local: Boolean) {
        settings.mcp.setLocal(id, local)
        reloadMcp()
    }

    fun removeMcp(id: String) {
        settings.mcp.remove(id)
        reloadMcp()
    }

    // ---------- 提示词片段 ----------

    /**
     * 片段表变了就同步到界面。
     *
     * 生效时机是**下一轮对话**：每轮的模型配置在发送时装配
     * （[AppSettings.customModel]），所以改完不用重开会话。
     */
    fun reloadPrompts() {
        _ui.value = _ui.value.copy(promptPresets = settings.prompts.ordered())
    }

    // ---------- 本机技能 ----------

    fun reloadLocalSkills() {
        _ui.value = _ui.value.copy(localSkills = settings.localSkills.list())
    }

    fun saveLocalSkill(id: String, content: String): String? {
        val error = settings.localSkills.save(id, content)
        if (error == null) reloadLocalSkills()
        return error
    }

    fun importLocalSkill(fileName: String, content: String): String? {
        val error = settings.localSkills.import(fileName, content)
        if (error == null) reloadLocalSkills()
        return error
    }

    fun setLocalSkillEnabled(id: String, enabled: Boolean) {
        settings.localSkills.setEnabled(id, enabled)
        reloadLocalSkills()
    }

    fun deleteLocalSkill(id: String) {
        settings.localSkills.delete(id)
        reloadLocalSkills()
    }

    fun localSkillBody(id: String): String? = settings.localSkills.body(id)

    fun savePrompt(preset: PromptPreset) {
        settings.prompts.upsert(preset)
        reloadPrompts()
    }

    fun removePrompt(id: String) {
        settings.prompts.remove(id)
        reloadPrompts()
    }

    fun setPromptEnabled(id: String, enabled: Boolean) {
        settings.prompts.setEnabled(id, enabled)
        reloadPrompts()
    }

    fun movePrompt(id: String, up: Boolean) {
        settings.prompts.move(id, up)
        reloadPrompts()
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
            mcpServers = settings.mcp.list(),
            promptPresets = settings.prompts.ordered(),
            localSkills = settings.localSkills.list(),
            customModels = settings.customModels
        )
    }

    private fun resetForAccountSwitch() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        sessions.clear()
        acpModels.clear()
        acpCurrentModels.clear()
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
                when {
                    remote.id == active -> remote.copy(
                        state = _ui.value.state,
                        artifacts = _ui.value.artifacts.ifEmpty { remote.artifacts },
                        ask = _ui.value.ask
                    )
                    // 本机会话的「状态」只在本机：云端那条记录是空壳（从没收到过 prompt），
                    // 它的状态位要么恒为 null，要么是这条会话还是云会话时的历史残留。
                    // 直接采信就会看到「本机」标签旁边挂着「云端执行中」，而且永远不消失。
                    // 真实情况只有一种：本进程里有没有它的 job 在跑（进程一死，agent 也就没了）。
                    isLocalConv(remote.id) -> remote.asLocal()
                    else -> remote
                }
            }
        )
        // 列表里若有别的会话挂着「运行中」而本机根本没在跑，顺手自愈（详见 scheduleHeal）。
        scheduleHeal(list)
    }

    /**
     * 模型三路合并：ACP `session/load` 下发的 availableModels（主）→
     * console `/models`（补充）→ 官方默认模型（兜底）。REST 失败不再静默吞掉。
     */
    suspend fun refreshModels() {
        _ui.value = _ui.value.copy(modelsLoading = true, modelError = null)
        val rest = runCatching { api.listModels() }
        val merged = mergeModels(rest.getOrDefault(emptyList()) + _ui.value.models)
        // 优先级：云端记录的会话模型（ACP 下发）→ 本地缓存的会话模型 → 官方默认。
        // 全局 selectedModel 不再参与：它只是「新会话的默认值」，不该盖住已有会话。
        val active = _ui.value.activeId
        // 本机会话不在这套命名空间里：它的模型 ID 属于用户自己的端点，
        // 拿云端清单里的任何一个去顶替，界面上就会显示一个它根本没用过的模型。
        val localNow = active != null && isLocalConv(active)
        val selected = if (localNow) {
            _ui.value.selectedModel
        } else {
            active?.let { acpCurrentModels[it] ?: settings.modelFor(it) }
                ?.takeIf { it.isNotBlank() }
                ?: merged.firstOrNull { it.isDefault }?.id
                ?: merged.firstOrNull()?.id
                ?: AppSettings.DEFAULT_MODEL
        }
        _ui.value = _ui.value.copy(
            models = merged,
            selectedModel = selected,
            modelsLoading = false,
            modelError = rest.exceptionOrNull()?.message
        )
    }

    /**
     * 拉取自定义端点的模型清单（`GET /models`）。
     *
     * 与 [refreshModels] 是两套：那个问的是云端账号能用哪些官方模型，
     * 这个问的是用户自己的端点上有哪些模型，两者互不相干。
     *
     * **只更新候选池，不动「我的模型」**：拉取是「看看端点上有啥」，
     * 不是「把配置重置成端点说的那套」。用户改过的显示名、思考强度、
     * 系统提示词都在「我的模型」里，被一次刷新冲掉就没法用了。
     */
    suspend fun fetchCustomModels(): CustomModelCatalog.Result {
        _ui.value = _ui.value.copy(modelsLoading = true, modelError = null)
        val r = catalog.fetch(settings.customBaseUrl, settings.customApiKey)
        if (r.ok) {
            _ui.value = _ui.value.copy(
                customCandidates = r.models,
                modelsLoading = false,
                modelError = null
            )
            _events.tryEmit("端点上有 ${r.models.size} 个模型，去设置里挑要用的")
        } else {
            _ui.value = _ui.value.copy(modelsLoading = false, modelError = r.error)
        }
        return r
    }

    /**
     * 把候选池里的模型加进「我的模型」。
     *
     * 能力标签按 ID 猜一个初值（[CustomModel.guessReasoning]），猜错用户能改；
     * 猜的作用只是让思考强度这一栏「一加进来就对着」。
     */
    fun addCustomModels(ids: List<String>) {
        val fresh = ids.filter { it.isNotBlank() }
            .map { CustomModel(id = it, reasoning = CustomModel.guessReasoning(it)) }
        if (settings.addCustomModels(fresh) == 0) return
        // 之前一个模型都没有：顺手把第一个设成当前，省得用户再点一次。
        if (settings.customModelId.isBlank()) settings.customModelId = fresh.first().id
        publishCustomModels()
        _events.tryEmit("已加入 ${fresh.size} 个模型")
    }

    fun removeCustomModel(id: String) {
        settings.removeCustomModel(id)
        publishCustomModels()
    }

    /** 保存某条模型的显示名 / 能力 / 参数覆盖。 */
    fun updateCustomModel(originalId: String, model: CustomModel) {
        settings.updateCustomModel(originalId, model)
        publishCustomModels()
    }

    /** 把「我的模型」与当前选中值同步到界面。改完必须调，否则要等下次重启才看见。 */
    fun publishCustomModels() {
        _ui.value = _ui.value.copy(
            customModels = settings.customModels,
            selectedModel = if (_ui.value.engine == AppSettings.ENGINE_CUSTOM) {
                settings.customModelFor(_ui.value.activeId)
            } else {
                _ui.value.selectedModel
            }
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

    /**
     * ACP 下发模型清单与「该会话当前模型」。
     *
     * 云端值是权威：用户在官方 App / 网页端改过模型，这里读到的就是改后的值，
     * 所以顺手写进本地缓存，下次打开会话首屏（ACP 还没连上时）也能显示对。
     */
    private fun publishModels(conversationId: String, models: List<ModelChoice>, current: String?) {
        // 同 publish()：本机会话的模型来自用户自己的端点（customModelFor），
        // 云端推送的模型 ID 写进会话设置会把本机选择覆盖掉，下次发送就 404。
        if (isLocalConv(conversationId)) return
        if (models.isNotEmpty()) acpModels[conversationId] = models
        val cloud = current?.takeIf { it.isNotBlank() }
        if (cloud != null) {
            acpCurrentModels[conversationId] = cloud
            settings.setModelFor(conversationId, cloud)
        }
        val merged = mergeModels(_ui.value.models)
        // 后台对账连上的会话不要改当前页显示的模型（否则切走的那条会把这条覆盖掉）。
        if (conversationId != _ui.value.activeId) {
            _ui.value = _ui.value.copy(models = merged, modelError = null)
            return
        }
        val effective = cloud ?: settings.modelFor(conversationId)
        _ui.value = _ui.value.copy(models = merged, selectedModel = effective, modelError = null)
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
        val active = _ui.value.activeId
        if (active == null) {
            // 还没有会话（草稿态）：这一选择作为「新会话的默认值」记下来。
            // 草稿用的是哪个引擎，默认值就写进哪套：写进云端那套会让新会话
            // 拿着 deepseek-chat 去请求云端，反过来则让本机会话请求一个云端模型 ID。
            if (_ui.value.engine == AppSettings.ENGINE_CUSTOM) {
                settings.customModelId = clean
                _ui.value = _ui.value.copy(selectedModel = clean, modelError = null)
                _events.tryEmit("新会话将使用本机模型「$clean」")
            } else {
                settings.selectedModel = clean
                _ui.value = _ui.value.copy(
                    selectedModel = clean,
                    modelError = null,
                    models = ensureModelChoice(clean)
                )
                _events.tryEmit("已设为新会话的默认模型「$clean」")
            }
            return
        }
        // 本机会话只认本机模型：它的模型 ID 属于用户自己的端点，云端那套在这里没有意义。
        // 换端点/换 Key 是全局配置，这里只动「这条会话用哪个模型」。
        if (isLocalConv(active)) {
            settings.setModelFor(active, clean)
            _ui.value = _ui.value.copy(selectedModel = clean, modelError = null)
            _events.tryEmit("本会话已改用本机模型「$clean」")
            return
        }
        // 有会话时只改这一条：官方就是每个会话各自选模型，改全局会把别的会话一起带跑。
        // 真正生效的是云端 `session/set_model`（官方 App 与网页端读的是同一份），
        // 所以先乐观更新界面，再以云端结果为准——失败必须回滚，不能让界面显示
        // 一个云端根本没生效的模型。
        _ui.value = _ui.value.copy(
            selectedModel = clean,
            engine = AppSettings.ENGINE_WB,
            modelError = null,
            models = ensureModelChoice(clean)
        )
        scope.launch {
            val result = runCatching { connection(active).setModel(clean) }
            if (result.isSuccess) {
                // 云端已记住，本地只作首屏缓存。
                settings.setModelFor(active, clean)
                acpCurrentModels[active] = clean
                _events.tryEmit("本会话已切换到「$clean」")
            } else {
                val fallback = acpCurrentModels[active] ?: settings.modelFor(active)
                if (_ui.value.activeId == active) {
                    _ui.value = _ui.value.copy(selectedModel = fallback)
                }
                _events.tryEmit(
                    "切换失败：" + readable(result.exceptionOrNull()!!) + "，已恢复为该会话原来的模型"
                )
            }
        }
    }

    /**
     * 把「即将新建的会话」设成本机自定义 API。
     *
     * 只对草稿生效：会话一旦建出来，类型就固定了。1.5.4 之前允许把已有云端会话
     * 现场切成自定义，结果是一条会话里混着两种引擎的历史——本地 memory 里只有
     * 后半段、云端记录里只有前半段，两边都对不上号，删会话时还会剩一半。
     * 现在改成彻底分离：想用另一种引擎，就新建一条。
     */
    suspend fun useCustomEngine() {
        val active = _ui.value.activeId
        if (active != null) {
            _events.tryEmit("本机会话与云端会话是两套：想用自定义 API，请新建一个会话")
            return
        }
        // 草稿态：只改新会话的默认引擎，真正建会话时按它走。
        settings.engineMode = AppSettings.ENGINE_CUSTOM
        _ui.value = _ui.value.copy(
            engine = AppSettings.ENGINE_CUSTOM,
            selectedModel = settings.customModelFor(null)
        )
        _events.tryEmit("新会话将使用自定义 API")
    }

    /** 把「即将新建的会话」设回云端模型。同样只对草稿生效。 */
    fun useCloudEngine() {
        if (_ui.value.activeId != null) {
            _events.tryEmit("会话类型建出来就固定了：想用云端模型，请新建一个会话")
            return
        }
        settings.engineMode = AppSettings.ENGINE_WB
        _ui.value = _ui.value.copy(
            engine = AppSettings.ENGINE_WB,
            selectedModel = settings.modelFor(null),
            modelError = null
        )
        _events.tryEmit("新会话将使用云端模型")
    }

    private fun ensureModelChoice(id: String): List<ModelChoice> =
        if (_ui.value.models.any { it.id == id }) _ui.value.models
        else _ui.value.models + ModelChoice(id, id)

    /**
     * 把云端 ACP 历史一次性导入本地 memory，返回导入条数。
     *
     * 只在本地为空时执行：本地已经有历史说明这条会话本来就是自定义模式下跑起来的，
     * 覆盖会把自定义阶段的消息抹掉。
     */
    private suspend fun seedCustomHistory(conversationId: String): Int {
        if (settings.agentMemory.load(conversationId).isNotEmpty()) return 0
        // 用**临时**连接搬历史，用完就关：不能走 [connection()]——那条连接会带着
        // onUpdate 进缓存，之后云端任何一次事件推送都会用空快照把本机消息流冲掉。
        val snap = withContext(Dispatchers.IO) {
            val conn = AcpConnection(http, settings, sessionOf(conversationId))
            try {
                conn.open()
                conn.initializeAndLoad(settings.historyTimeoutMs)
                conn.snapshot()
            } finally {
                runCatching { conn.close() }
            }
        }
        // 只搬「用户提问」和「最终回答」：思考与工具调用是过程，
        // 搬进 memory 会白占 token，模型也用不上。
        val turns = snap.messages
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
            .filter { it.text.isNotBlank() && !it.streaming }
            .map { AgentTurn(fromUser = it.role == ChatRole.USER, text = it.text) }
        if (turns.isEmpty()) return 0
        settings.agentMemory.save(conversationId, turns)
        settings.markCustomConversation(conversationId)
        return turns.size
    }

    // ---------- 会话 ----------

    /** 新建草稿会话：真正创建发生在第一次发送时，避免留下空会话。 */
    fun startNew() {
        // 草稿按设置页里的「新会话默认引擎」开局；建出来之后类型就固定了。
        val kind = settings.kindOf(null)
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
            selectedModel = if (kind == AppSettings.ENGINE_CUSTOM) {
                settings.customModelFor(null)
            } else {
                settings.modelFor(null)
            },
            engine = kind,
            stepsExpanded = emptyMap()
        )
    }

    suspend fun openConversation(id: String) {
        // 会话类型（云端 / 本机）建出来就固定了，之后只读不改：`kindOf` 对没有归属
        // 记录的老会话一律按云端算，避免用户把默认引擎改成自定义后，几十条云端老会话
        // 被集体误判成本地。
        val convKind = settings.kindOf(id)
        // 会话自己用哪个引擎必须本地记住：云端没有「本机推理」这个概念。
        if (!settings.hasEngineFor(id)) settings.setEngineFor(id, convKind)
        // 自愈闸：只要本机消息流里有内容（或在自定义标记表里），就按本机会话处理，
        // 无论归属记录怎么说——引擎记录按账号前缀存，换账号后 kindOf 会回落成
        // 「云端」，走 ACP 回放只能得到空快照，用户看到的就是「消息全丢了」。
        val local = isLocalConv(id)
        if (local && convKind != AppSettings.ENGINE_CUSTOM) {
            settings.setEngineFor(id, AppSettings.ENGINE_CUSTOM)
        }
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
            activeTitle = _ui.value.conversations.firstOrNull { it.id == id }?.title ?: "会话",
            // 两套模型的 ID 不通用：本机会话只能显示本机模型，
            // 拿云端模型 ID 去请求第三方端点只会得到一句 404。
            selectedModel = if (local) settings.customModelFor(id) else settings.modelFor(id),
            engine = convKind,
            stepsExpanded = emptyMap()
        )
        settings.lastConversation = id
        // 本机会话：推理跑在本机，云端那条会话是空的，靠 ACP 回放只会得到一片空白。
        // 直接读本地历史渲染，也省掉一次长连接。
        if (local) {
            // 1.5.4 里被现场切成本地的那批会话，历史还在云端：本地为空时补搬一次。
            if (settings.agentMemory.load(id).isEmpty()) {
                runCatching { seedCustomHistory(id) }
            }
            restoreCustomConversation(id)
            return
        }
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
        // 分流兜底：本机会话绝不能走 ACP。界面上已经按会话分好流，但「重新加载」
        // 之类入口会绕过界面直接调 send，漏一次就会把本机历史推进云端会话。
        // 判定与 openConversation 的自愈闸同一口径（见 [isLocalConv]）。
        val kindId = _ui.value.activeId
        if (isLocalConv(kindId)) {
            sendCustom(prompt, attachments)
            return
        }
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
            var turnConn: AcpConnection? = null
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
                turnConn = conn
                // 这一轮是本客户端发起的：允许它的 SSE 事件推进度/状态。
                conn.setLocalTurnActive(true)
                // 这里**故意不调 setModel**：模型是跟着会话存在云端的，用户可能在
                // 官方 App / 网页端刚改过。每轮都按本地值重设一次会把云端选择改回去。
                // 本轮要用的模型随 prompt 一起下发（下面那个参数），不动会话级设置。
                val (blocks, sourceBlocks) = buildPrompt(conn.session, prompt, attachments)
                conn.prompt(prompt, currentModel(id), blocks, sourceBlocks, PROMPT_TIMEOUT)
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
                turnConn?.setLocalTurnActive(false)
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

    /**
     * 用自定义模型（本机 agent）发一轮。
     *
     * 与 [send] 并列而不是替换：[send] 走云端 ACP（扣积分、有服务端对话记录），
     * 这条走本机 Koog agent + 用户自己的 API（不扣积分、不产生云端对话记录）。
     * 两者共用同一个会话的沙箱——沙箱是跟随会话分配的，所以仍然先用云端建会话拿到
     * sandbox，只是不再调用 `session/prompt`。
     */
    fun sendCustom(prompt: String, attachments: List<PendingAttachment> = emptyList()) {
        // 允许"只发附件"：用户拖进来一个文件、什么也不写，是很自然的用法。
        if (prompt.isBlank() && attachments.isEmpty()) return
        // 云会话走 sendCustom 是错配（会往云端会话里塞只有本机才有的历史），
        // 反过来本机会话走 send 也一样。两边都按会话类型硬拦一次。
        val kindId = _ui.value.activeId
        if (kindId != null && !isLocalConv(kindId)) {
            send(prompt, attachments)
            return
        }
        val cfg = settings.customModel(kindId)
        if (!cfg.usable) {
            _events.tryEmit("请先在「设置 → 自定义 API」里填好接口地址、API Key 与模型 ID")
            return
        }
        val currentId = _ui.value.activeId
        // 先看自定义轮次：它不产生 ACP 在途记录，只靠 StateReconciler 判断会漏。
        if (currentId != null && customJobs[currentId]?.isActive == true) {
            _events.tryEmit("上一轮还没跑完，消息未发送：可稍候，或点停止后再发")
            return
        }
        if (StateReconciler.isActive(_ui.value.state) &&
            (currentId == null || hasLiveTurn(currentId))
        ) {
            _events.tryEmit("上一轮还没收尾，消息未发送：可稍候，或点停止后再发")
            return
        }
        _ui.value = _ui.value.copy(sending = true, error = null, ask = null)
        val targetId = currentId

        val job = scope.launch {
            var convId: String? = targetId
            try {
                val id = targetId ?: createCustomConversation(prompt)
                convId = id
                // 会话 id 到手后把 job 迁到按会话的表里，[stop] 才能精确取消这一条。
                coroutineContext[Job]?.let { customJobs[id] = it }
                pendingCustomJob = null
                settings.markCustomConversation(id)
                // 引擎绑定到会话：切走再回来仍然是自定义模式，不会莫名其妙跑回云端。
                settings.setEngineFor(id, AppSettings.ENGINE_CUSTOM)
                applyIfActive(id) { copy(state = TurnState.RUNNING, engine = AppSettings.ENGINE_CUSTOM) }
                val askText = prompt.ifBlank {
                    attachments.joinToString("、") { it.name }.ifBlank { "（附件）" }
                }
                pushLocal(id, ChatRole.USER, askText)
                // 上面这一句已经落盘（[pushLocal] 同时写界面与消息流）。
                // 后面拿沙箱、传附件、调模型任何一步失败或被打断，用户已经说出口的
                // 这句话都不能跟着一起消失——重进会话时至少还在。
                // agent 自己发请求前还会再写一次，重复由存储层去重。

                // 拿该会话的沙箱接入点；工具执行全部落在这里面。
                val (session, endpoint) = withSandbox(id) { s, ep -> s to ep }
                // 附件写进沙箱，路径交给模型自己用工具读——自定义端点不一定支持多模态，
                // 但沙箱里的文件它一定能读。
                val staged = stageAttachments(session, attachments)
                if (attachments.isNotEmpty() && staged.isEmpty()) {
                    _events.tryEmit("附件上传到沙箱失败，本轮只处理文字")
                }
                val ask = if (staged.isEmpty()) prompt else prompt + AttachmentPrompt.hint(staged)
                // 会话 id 到这一步才确定，重新取一次配置：模型 ID 是会话级的，
                // 沿用草稿态那份会让新会话永远用「上一个默认模型」。
                val turnCfg = settings.customModel(id)
                if (!turnCfg.usable) {
                    // 标成 failed：这不是模型说的话，回放给模型会被当成它自己的回答。
                    pushLocal(
                        id, ChatRole.ASSISTANT,
                        "自定义模型未配置完整：需要接口地址、API Key 与模型 ID。请到「设置 → 自定义 API」补齐。",
                        failed = true,
                    )
                    applyIfActive(id) { copy(state = TurnState.FAILED) }
                    return@launch
                }
                // 工具进度写进消息流：长任务里用户能看到 agent 正在干什么，
                // 而不是盯着一个转圈图标等几十秒。
                val agent = customAgents.get(
                    conversationId = id,
                    endpoint = endpoint,
                    token = session.token,
                    config = turnCfg,
                    tokenRefresher = { refreshSandboxToken(id) },
                    // 思考内容进消息流：不写进去的话，「思考强度」这个设置就成了
                    // 用户唯一看不见效果的开关——模型想没想，屏幕上完全没差别。
                    onThought = { thought -> pushLocal(id, ChatRole.THOUGHT, thought) },
                    onToolEvent = { ev -> onToolProgress(id, ev) },
                    // agent 跑的每条命令都进命令日志：用户点开终端能看到
                    // 它到底执行了什么、输出了什么，而不是只有一句"已完成"。
                    onExecuted = { cmd, at, result ->
                        recordCommand(id, ensureDefaultTab(id), cmd, at, result, CmdSource.AGENT)
                    },
                )
                val reply = agent.send(ask)
                finishToolPlaceholder(id)
                pushLocal(id, ChatRole.ASSISTANT, reply.answer)
                applyIfActive(id) { copy(state = TurnState.IDLE) }
            } catch (e: CancellationException) {
                // 用户点了停止：不是错误，安静收尾。工具占位标成失败，
                // 否则重进会话会看到一条永远转圈的调用。
                finishToolPlaceholder(convId, ToolState.FAILED)
                applyIfActive(convId) { copy(state = TurnState.STOPPED) }
                throw e
            } catch (e: Exception) {
                finishToolPlaceholder(convId, ToolState.FAILED)
                val reason = readable(e)
                // 失败原因同样落盘：重进会话时能看到这轮为什么没成，
                // 而不是只剩一句提问、后面空空如也。带 failed 标记——界面上照常显示，
                // 回放给模型时跳过（模型不该把报错当成自己说过的话）。
                convId?.let { cid ->
                    runCatching {
                        settings.agentMemory.append(cid, AgentTurn(false, reason, error = true))
                    }
                }
                applyIfActive(convId) { copy(error = reason, state = TurnState.FAILED) }
            } finally {
                convId?.let { customJobs.remove(it) }
                if (pendingCustomJob === coroutineContext[Job]) pendingCustomJob = null
                applyIfActive(convId) { copy(sending = false) }
                // 自定义模式不产生云端记录，但有产物落进沙箱时工作区仍然要刷新。
                runCatching { refreshWorkspaceQuietly(convId) }
            }
        }
        // 会话还没建出来时也要能停：先挂在 pending 上，建完再迁走。
        if (targetId == null) pendingCustomJob = job else customJobs[targetId] = job
    }

    /**
     * 建一个「空」会话给自定义模式用。
     *
     * 关键是**不要把用户这句话传给云端**：自定义模式的推理在本机跑，
     * 云端只需要提供一个沙箱。传 prompt 会让云端建出一条带用户提问的记录
     * （会话列表里能看到、也可能触发一次云端计费），与"不消耗积分"的前提冲突。
     *
     * 少数情况下服务端不接受空 prompt，此时退回用标题占位——它只当会话名，
     * 不参与本地推理。
     *
     * 实测（真账号）：空 prompt + 完全不传 model 也能建成功，回包内联了
     * `link/token/cwd/e2bDataEndpoint`，沙箱立即可用，`model` 为 null。
     * 所以这里连模型都不传——会话只当沙箱载体，不在云端挂用不到的模型。
     */
    private suspend fun createCustomConversation(title: String): String {
        val name = title.trim().take(24).ifBlank { "自定义会话" }
        val created = runCatching { api.createConversation("") }
            .recoverCatching { api.createConversation(name) }
            .getOrThrow()
        val (id, session) = created
        session?.let { sessions[id] = it }
        settings.setEngineFor(id, AppSettings.ENGINE_CUSTOM)
        // 顺带把云端名字写成这句话的抬头。不写的话会话列表里会堆一串空标题，
        // 几十条之后根本分不清哪条是哪条（自定义模式的会话在云端本来就是空的）。
        runCatching { api.rename(id, name) }
        _ui.value = _ui.value.copy(
            activeId = id,
            draft = false,
            activeTitle = name,
            engine = AppSettings.ENGINE_CUSTOM
        )
        settings.lastConversation = id

        // 兜一次底：空 prompt 建会话在部分服务端版本上仍会挂起一个没模型的轮次，
        // 表现是列表里永久「云端执行中」。cancel 是安全回收（这一轮没人在等它），
        // 挂后台跑，不挡第一条消息。
        scope.launch { runCatching { healStaleRun(id) } }

        // 确认这个会话真的拿到了沙箱。自定义模式的工具全跑在里面，
        // 拿不到就没法工作——早一步报清楚，别让用户跑到一半看到一句"没有文件网关"。
        val ready = runCatching { sessionOf(id) }.getOrNull()
        if (ready?.dataEndpoint.isNullOrBlank()) {
            throw CloudException(
                "会话已创建（$id），但没有拿到沙箱地址，无法在里面执行命令。" +
                    "请先用「云端模型」发一条消息把沙箱建起来，然后再切到自定义模型。"
            )
        }
        return id
    }

    /**
     * 重新打开一个自定义会话时，用本地消息流还原整段对话。
     *
     * 云端那条会话是空的，ACP 回放不出任何东西，所以走这条分支而不是 [openConversation] 的常规路径。
     * 还原的是**完整消息流**：提问、回答、思考、工具调用、失败记录，界面按原样重建，
     * 折叠条里那些「N 个步骤 · X 次工具调用 · Y 段思考」也一并回来。
     */
    private suspend fun restoreCustomConversation(id: String) {
        val running = customJobs[id]?.isActive == true || pendingCustomJob?.isActive == true
        // 上一个进程可能是在工具执行到一半时被杀掉的。先收尾再读：把挂着「运行中」的
        // 工具标成中断、给没有下文的提问补一句说明，否则界面上就是一条永远转圈的调用
        // 加一个等不到回答的提问。
        val stored = withContext(Dispatchers.IO) {
            settings.sessionStore.sealInterrupted(id)
            settings.sessionStore.messages(id)
        }
        // 轮次编号按消息流重算（见 [Transcript.replay]），不信任存储里的值：
        // 老版本写进去的编号是错的（提问与回答各差一轮），直接拿来分组会让上一轮的
        // 过程消息挂到下一轮上。
        val msgs = Transcript.replay(stored)
        // 用户在恢复期间切到了别的会话：整份覆盖会把 A 的消息写进 B 的页面。
        // 与云端路径的 `if (_ui.value.activeId != id) return` 同一防线，本地也要有。
        if (_ui.value.activeId != id) return
        _ui.value = _ui.value.copy(
            loadingConversation = false,
            historyProgress = 0,
            historyNotice = null,
            messages = msgs,
            artifacts = emptyList(),
            error = null,
            ask = null,
            state = if (running) TurnState.RUNNING else TurnState.IDLE,
            sending = running,
            activeTitle = _ui.value.conversations.firstOrNull { it.id == id }?.title ?: _ui.value.activeTitle,
            stepsExpanded = emptyMap()
        )
    }

    /**
     * 往消息流追加一条本地消息（自定义模型用，不经过 ACP 的 reducer）。
     *
     * **两处都要写**：界面状态与落盘的完整消息流。以前只写界面，退出会话后
     * 只剩「提问 + 回答」两句话——思考、工具调用、失败记录全没了，看起来就像
     * 消息被人删过一半。落盘的是完整流，重进时原样放回去。
     *
     * 返回消息 id：工具调用结束时要靠它把「运行中」改成「已完成」，落盘那条也要跟着改。
     */
    private fun pushLocal(
        id: String,
        role: ChatRole,
        text: String,
        toolState: ToolState = ToolState.NONE,
        failed: Boolean = false,
    ): String {
        val msgId = "local-" + System.nanoTime() + "-" + localMsgSeq.incrementAndGet()
        val msg = ChatMessage(
            id = msgId,
            role = role,
            text = text,
            toolState = toolState,
            streaming = false,
            turn = turnOf(id, role),
            failed = failed,
        )
        applyIfActive(id) { copy(messages = messages + msg) }
        runCatching { settings.sessionStore.append(id, StoredMessage.of(msg)) }
        return msgId
    }

    /**
     * 这条消息属于第几轮。
     *
     * 同一轮的所有消息必须拿到同一个编号，界面靠它把思考 / 工具收进一个折叠组；
     * 编号错位会让上一轮的过程消息挂到下一轮上去（表现为折叠条内容对不上）。
     */
    private fun turnOf(id: String, role: ChatRole): Int {
        val messages = settings.sessionStore.messages(id)
        val users = messages.count { it.role == ChatRole.USER }
        return if (role == ChatRole.USER) users else (users - 1).coerceAtLeast(0)
    }

    /**
     * 把工具调用显示成一条 TOOL 消息。
     *
     * 以前只发一个 toast，长任务里用户完全不知道 agent 卡在哪一步；
     * 写进消息流就能看到"正在跑什么、跑完了没"。
     */
    private fun onToolProgress(convId: String, ev: SandboxToolEvent) {
        if (ev.running) {
            toolMsgIds[convId] =
                pushLocal(convId, ChatRole.TOOL, ev.tool + " · " + ev.detail, ToolState.RUNNING)
        } else {
            finishToolPlaceholder(convId)
        }
    }

    /**
     * 收尾该会话最后一条 TOOL 占位。
     *
     * 默认算「已完成」；这一轮失败或被中断时传 [ToolState.FAILED]——否则重进会话
     * 会看到一条永远转圈的工具调用，用户以为它还在跑。
     */
    private fun finishToolPlaceholder(convId: String?, state: ToolState = ToolState.DONE) {
        val id = convId ?: return
        val msgId = toolMsgIds.remove(id) ?: return
        applyIfActive(id) {
            copy(
                messages = messages.map {
                    if (it.id == msgId) it.copy(toolState = state) else it
                }
            )
        }
        runCatching { settings.sessionStore.update(id, msgId) { it.copy(toolState = state) } }
    }

    // ---------- 命令行窗口 ----------

    /**
     * 打开/切换终端。传 null 表示当前还没进会话（终端只读，不能执行）。
     *
     * 进来时把该会话开过的标签铺开：agent 之前跑过的命令也应该看得到，
     * 否则用户会以为终端是空的、agent 什么都没做过。
     */
    fun openTerminal(conversationId: String?) {
        // 切会话前先把在跑的收尾：不然它跑完会往旧会话里写，屏幕上却已经是新会话了。
        stopAllShells()
        if (conversationId == null) {
            _terminal.value = TerminalState()
            return
        }
        val tabs = tabsOf(conversationId)
        _terminal.value = TerminalState(
            conversationId = conversationId,
            tabs = tabs,
            activeTabId = activeTabOf[conversationId]?.takeIf { id -> tabs.any { it.id == id } }
                ?: tabs.first().id,
            history = cmdHistory.toList(),
            notice = null,
        )
    }

    /** 把某个会话的标签铺出来：标题来自 [tabMeta]，内容来自命令日志。 */
    private fun tabsOf(convId: String): List<TerminalTab> {
        val meta = tabMeta[convId] ?: run {
            val one = listOf(newTabId() to DEFAULT_TAB_TITLE)
            tabMeta[convId] = one
            one
        }
        return meta.map { (id, title) ->
            TerminalTab(
                id = id,
                title = title,
                entries = commandLog.of(CommandLog.keyOf(convId, id)),
            )
        }
    }

    private fun newTabId(): Long = ++nextTabSeq

    /**
     * 会话的默认标签（第一个）。
     *
     * agent 跑的命令不指定标签，但记录必须有归属——统一写进第一个标签，
     * 用户进终端在最左边就能看到「模型到底执行了什么」。
     */
    private fun ensureDefaultTab(convId: String): Long {
        val meta = tabMeta[convId]
        if (!meta.isNullOrEmpty()) return meta.first().first
        val id = newTabId()
        tabMeta[convId] = listOf(id to DEFAULT_TAB_TITLE)
        return id
    }

    /** 新建一个标签并切过去。到上限就明说，不静默失败。 */
    fun newTerminalTab() {
        val st = _terminal.value
        val convId = st.conversationId ?: _ui.value.activeId
        if (convId == null) {
            _terminal.value = st.copy(notice = "先进入一个会话：沙箱是跟着会话分配的")
            return
        }
        val tabs = if (st.tabs.isEmpty()) tabsOf(convId) else st.tabs
        if (tabs.size >= MAX_TERMINAL_TABS) {
            _terminal.value = st.copy(tabs = tabs, notice = "最多 $MAX_TERMINAL_TABS 个终端，先关掉一个")
            return
        }
        val id = newTabId()
        // 编号取「现有最大 + 1」而不是「数量 + 1」：关掉中间的标签后不会和历史重名。
        val nextNo = tabs.mapNotNull { it.title.substringAfterLast(' ').toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 1
        val title = "终端 $nextNo"
        tabMeta[convId] = tabs.map { it.id to it.title } + (id to title)
        activeTabOf[convId] = id
        _terminal.value = st.copy(
            conversationId = convId,
            tabs = tabs + TerminalTab(id = id, title = title),
            activeTabId = id,
            notice = null,
        )
    }

    /** 切标签。不打断正在跑的命令——这正是多标签的用处。 */
    fun selectTerminalTab(tabId: Long) {
        val st = _terminal.value
        if (st.activeTabId == tabId || st.tabs.none { it.id == tabId }) return
        st.conversationId?.let { activeTabOf[it] = tabId }
        // 后台标签的输出只存在执行槽里，切回来要接上，否则进度是断的。
        val run = shellRuns[tabId]
        val tabs = st.tabs.map { tab ->
            if (tab.id == tabId && run != null) {
                tab.copy(liveOutput = synchronized(run.live) { run.live.toString() })
            } else {
                tab
            }
        }
        _terminal.value = st.copy(tabs = tabs, activeTabId = tabId, notice = null)
    }

    /** 重命名。空标题回落到默认名，否则标签会变成一条看不见的缝。 */
    fun renameTerminalTab(tabId: Long, name: String) {
        val st = _terminal.value
        val title = name.trim().take(16).ifBlank { DEFAULT_TAB_TITLE }
        val tabs = st.tabs.map { if (it.id == tabId) it.copy(title = title) else it }
        st.conversationId?.let { convId -> tabMeta[convId] = tabs.map { it.id to it.title } }
        _terminal.value = st.copy(tabs = tabs)
    }

    /**
     * 关掉一个标签。
     *
     * 在跑的那条直接取消且不落盘：用户关的是整个标签，留着记录也没地方看。
     * 关到最后一个时不让关——整页只剩输入框比多一个空标签更让人迷惑。
     */
    fun closeTerminalTab(tabId: Long) {
        val st = _terminal.value
        if (st.tabs.size <= 1) {
            _terminal.value = st.copy(notice = "至少留一个终端")
            return
        }
        if (st.tabs.none { it.id == tabId }) return
        stopShell(tabId, keepOutput = false)
        st.conversationId?.let { commandLog.clear(CommandLog.keyOf(it, tabId)) }
        val tabs = st.tabs.filterNot { it.id == tabId }
        val nextActive = if (st.activeTabId == tabId) tabs.first().id else st.activeTabId
        st.conversationId?.let { convId ->
            tabMeta[convId] = tabs.map { it.id to it.title }
            activeTabOf[convId] = nextActive
        }
        _terminal.value = st.copy(tabs = tabs, activeTabId = nextActive)
    }

    /** 清空**当前标签**的记录（存储一起清，否则重开又回来了）。 */
    fun clearTerminal() {
        val st = _terminal.value
        val tabId = st.activeTabId
        // 正在跑的那条不写日志：用户点的是「清屏」，不是「停下来并保留」。
        stopShell(tabId, keepOutput = false)
        val convId = st.conversationId ?: return
        commandLog.clear(CommandLog.keyOf(convId, tabId))
        publish(tabId) { it.copy(entries = emptyList(), liveOutput = "") }
    }

    /**
     * 在当前标签里手敲一条命令。
     *
     * 这条路**完全绕开模型**：不建 ACP 轮次、不消耗积分，就是一条 envd 命令。
     * 用途是排查——模型说"我已经装好了"，你自己 `ls` 一眼就知道真假。
     */
    fun runShell(raw: String) {
        val command = raw.trim()
        if (command.isEmpty()) return
        val st = _terminal.value
        val convId = st.conversationId ?: _ui.value.activeId
        if (convId == null) {
            _terminal.value = st.copy(notice = "先进入一个会话：沙箱是跟着会话分配的")
            return
        }
        // 会话可能是先敲命令、后开页面进来的，标签补上。
        val tabs = if (st.tabs.isEmpty()) tabsOf(convId) else st.tabs
        val tabId = if (tabs.any { it.id == st.activeTabId }) st.activeTabId else tabs.first().id
        if (shellRuns[tabId] != null) {
            _terminal.value = st.copy(
                tabs = tabs,
                activeTabId = tabId,
                notice = "这个终端里上一条还在跑，先等它结束或点中断",
            )
            return
        }
        rememberCommand(convId, command)

        val run = ShellRun(convId, tabId, command, System.currentTimeMillis())
        shellRuns[tabId] = run

        _terminal.value = _terminal.value.copy(
            conversationId = convId,
            tabs = tabs.map {
                if (it.id == tabId) {
                    it.copy(running = true, runningCommand = oneLine(command), liveOutput = "")
                } else {
                    it
                }
            },
            activeTabId = tabId,
            notice = null,
        )

        run.job = scope.launch {
            val result = runCatching {
                withSandbox(convId) { session, endpoint ->
                    sandboxExec.run(
                        endpoint = endpoint,
                        token = session.token,
                        command = command,
                        cwd = session.cwd ?: "/workspace",
                        timeoutSec = SHELL_TIMEOUT,
                        onUnauthorized = { refreshSandboxToken(convId) },
                        // 边跑边显示：装依赖这种要几十秒的命令，没有实时输出
                        // 就是个黑屏，用户会以为卡死了。
                        onChunk = { text, _ ->
                            synchronized(run.live) {
                                if (run.live.length < LIVE_MAX_CHARS) run.live.append(text)
                                // 停止之后这个协程还可能在吐最后一帧，别往 UI 上糊。
                                if (!run.cancelled) {
                                    publish(tabId) { it.copy(liveOutput = run.live.toString()) }
                                }
                            }
                        },
                    )
                }
            }.getOrElse { e ->
                SandboxExec.Result("", "", null, readable(e))
            }

            // 已经停止过：那个时候就落盘了，这里再记一条会变成两条重复命令。
            if (run.cancelled) return@launch

            shellRuns.remove(tabId)
            recordCommand(convId, tabId, command, run.startedAt, result, CmdSource.MANUAL)
            publish(tabId) { it.copy(running = false, runningCommand = null, liveOutput = "") }
        }
    }

    /**
     * 中断某个标签里正在跑的命令。
     *
     * 关键点：**已经收到的输出必须落盘**。取消协程只是断开 HTTP 流，
     * 而输出是边跑边收的——`ping` 跑了十秒，用户按停，那十秒的回显就是结果，
     * 不能因为"没跑完"就整段丢掉（1.6.1 之前就是这个 bug，按停等于清屏）。
     *
     * [keepOutput] 为 false 时不写日志（清屏、关标签用），正常停止都保留。
     */
    fun stopShell(tabId: Long = _terminal.value.activeTabId, keepOutput: Boolean = true) {
        val run = shellRuns.remove(tabId) ?: return
        run.cancelled = true
        val partial = synchronized(run.live) { run.live.toString() }
        // 取消协程 → SandboxExec 里的 watchdog 会 cancel 掉 call，沙箱侧进程随之结束。
        run.job?.cancel()

        if (keepOutput) {
            recordCommand(
                convId = run.convId,
                tabId = tabId,
                command = run.command,
                startedAt = run.startedAt,
                // 中断不是错误：给的是已收到的 stdout，标记另走 aborted。
                result = SandboxExec.Result(partial, "", null, null),
                source = CmdSource.MANUAL,
                aborted = true,
            )
        }
        publish(tabId) { it.copy(running = false, runningCommand = null, liveOutput = "") }
    }

    /** 停掉所有标签里在跑的命令（离开终端、切会话时用）。 */
    fun stopAllShells() {
        shellRuns.keys.toList().forEach { stopShell(it, keepOutput = true) }
    }

    /**
     * 只改屏幕上那个标签。
     *
     * 后台标签的输出照常收（在 [ShellRun.live] 里），但不刷 UI——切回来时
     * [selectTerminalTab] 会把已收的部分接上。
     */
    private fun publish(tabId: Long, edit: (TerminalTab) -> TerminalTab) {
        val st = _terminal.value
        if (st.tabs.none { it.id == tabId }) return
        _terminal.value = st.copy(tabs = st.tabs.map { if (it.id == tabId) edit(it) else it })
    }

    /** 多行脚本压成一行显示，否则输入框上方的提示会被撑开两三行。 */
    private fun oneLine(command: String): String =
        command.replace(Regex("\\s+"), " ").trim()
            .let { if (it.length <= 90) it else it.take(90) + "…" }

    /** 记一条历史命令：连续的重复不重复记，最多留 [CMD_HISTORY_MAX] 条。 */
    private fun rememberCommand(convId: String, command: String) {
        if (cmdHistory.firstOrNull() != command) {
            cmdHistory.addFirst(command)
            while (cmdHistory.size > CMD_HISTORY_MAX) cmdHistory.removeLast()
        }
        _terminal.value = _terminal.value.copy(history = cmdHistory.toList())
    }

    /**
     * 一条命令跑完：写日志 + 刷新终端。
     *
     * 只有终端正开着的那个会话才刷 UI——别的会话的记录留在存储里，
     * 切过去时 [openTerminal] 会一次性铺开。
     */
    private fun recordCommand(
        convId: String,
        tabId: Long,
        command: String,
        startedAt: Long,
        result: SandboxExec.Result,
        source: CmdSource,
        aborted: Boolean = false,
    ) {
        val entry = CmdEntry(
            id = commandLog.nextId(),
            command = command,
            source = source,
            startedAt = startedAt,
            durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            error = result.error,
            aborted = aborted,
        )
        val key = CommandLog.keyOf(convId, tabId)
        commandLog.append(key, entry)
        publish(tabId) { it.copy(entries = commandLog.of(key)) }
    }

    /**
     * 用最小请求验证自定义模型配置是否可用。
     *
     * 单独做这个入口的理由：用户填完 key 只能靠"发一条消息试试"，失败了你也不知道
     * 是 key 错、地址错还是模型名错。这里直接要一句最短回复，把具体错误原样带回来。
     */
    suspend fun testCustomModel(cfg: CustomModelConfig = settings.customModel()): Result<String> =
        customAgents.testConnection(cfg)

    /** 后台刷新工作区，失败不打扰用户（自定义模式常在跑完后才产生文件）。 */
    private suspend fun refreshWorkspaceQuietly(convId: String?) {
        val id = convId ?: return
        runCatching { loadWorkspace(id) }
    }

    fun stop() {
        val id = _ui.value.activeId

        // 自定义模式必须走这条：它没有 ACP 在途轮次，云端也没有 session 可以 cancel。
        // 光改 UI 状态的话，协程仍在跑、模型仍在推理、工具仍在沙箱里执行，
        // 用户点了停止却照样在花钱。cancel() 会让 SandboxExec 同步中止底层 HTTP 调用。
        val custom = id?.let { customJobs[it] } ?: pendingCustomJob
        if (custom != null && custom.isActive) {
            custom.cancel()
            id?.let { customJobs.remove(it) }
            pendingCustomJob = null
            finishToolPlaceholder(id)
            applyIfActive(id) { copy(state = TurnState.STOPPED, sending = false) }
            _events.tryEmit("已停止本轮自定义模型任务")
            return
        }

        if (id == null) return
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
            var turnConn: AcpConnection? = null
            try {
                val conn = withContext(Dispatchers.IO) { connection(id) }
                turnConn = conn
                conn.setLocalTurnActive(true)
                if (viaResolve) conn.resolve(ask, answer)
                else {
                    val (blocks, source) = buildPrompt(conn.session, text, emptyList())
                    conn.prompt(text, currentModel(id), blocks, source, PROMPT_TIMEOUT)
                }
            } catch (e: PromptWaitTimeout) {
                _events.tryEmit("云端任务仍在运行，可继续等待或点停止")
            } catch (_: TurnCancelled) {
                // 同上：停止不是错误。
            } catch (e: Exception) {
                applyIfActive(id) { copy(error = readable(e)) }
            } finally {
                turnConn?.setLocalTurnActive(false)
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
                    // 会话级选择（模型/引擎）与本地历史一起清掉，别留下孤儿记录。
                    settings.clearConversationPrefs(id)
                    // 本机会话的对话内容只在本机，不跟着清就会一直躺在 prefs 里，
                    // 谁也读不到、谁也删不掉。
                    settings.agentMemory.clear(id)
                    customAgents.drop(id)
                    // 命令日志同样按会话存，不跟着清就是几十份死沙箱的输出常驻内存。
                    commandLog.forget(id)
                    if (_terminal.value.conversationId == id) {
                        _terminal.value = TerminalState()
                    }
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

    suspend fun readWorkspaceFile(
        conversationId: String,
        path: String,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Result<ByteArray> = runCatching {
        withContext(Dispatchers.IO) {
            withSandbox(conversationId) { session, endpoint ->
                sandbox.read(endpoint, session.token, path, onProgress, isCancelled)
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
     * 把附件写进会话沙箱的 `uploads/` 目录。
     *
     * 落点与云端链路一致（`<cwd>/uploads/cb-<ts>/`）：两种引擎共用同一个沙箱，
     * 用户在工作区里看到的路径也应该一样。单条失败就跳过，由调用方提示。
     */
    private suspend fun stageAttachments(
        session: CloudSession,
        attachments: List<PendingAttachment>,
    ): List<AttachmentPrompt.Staged> {
        if (attachments.isEmpty()) return emptyList()
        val endpoint = session.dataEndpoint ?: return emptyList()
        val dir = (session.cwd ?: "/workspace").trimEnd('/') + "/uploads/cb-" + System.currentTimeMillis()
        return attachments.mapNotNull { a ->
            val path = runCatching {
                sandbox.write(endpoint, session.token, "$dir/${a.name}", a.bytes)
            }.getOrNull() ?: return@mapNotNull null
            AttachmentPrompt.Staged(
                name = a.name,
                path = path,
                size = a.bytes.size,
                preview = AttachmentPrompt.previewOf(a.name, a.mimeType, a.bytes),
            )
        }
    }

    /**
     * 换一张新的沙箱 token 并刷新缓存。
     *
     * 与 [withSandbox] 的区别：那条路靠抛异常触发，只管文件网关；
     * 自定义引擎的工具调用走 envd，401 不抛异常，只能靠回调显式续期。
     * 失败的返回值是 null（而不是抛），由调用方决定怎么向用户解释。
     */
    private suspend fun refreshSandboxToken(conversationId: String): String? = runCatching {
        val fresh = api.fetchSession(conversationId)
        sessions[conversationId] = fresh
        fresh.token
    }.getOrNull()

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

    /** 某个会话该用的云端模型；不传会话就是「新会话的默认模型」。 */
    private fun currentModel(conversationId: String? = null): String =
        settings.modelFor(conversationId ?: _ui.value.activeId).ifBlank { AppSettings.DEFAULT_MODEL }

    private suspend fun createConversation(prompt: String): String {
        val model = currentModel(null)
        val (id, session) = api.createConversation(prompt, model)
        session?.let { sessions[id] = it }
        // 新会话把开局用的模型固化到它自己名下，之后改「默认模型」不影响它。
        settings.setModelFor(id, model)
        settings.setEngineFor(id, AppSettings.ENGINE_WB)
        _ui.value = _ui.value.copy(
            activeId = id,
            draft = false,
            activeTitle = prompt.take(24).ifBlank { "新会话" },
            selectedModel = model,
            engine = AppSettings.ENGINE_WB
        )
        settings.lastConversation = id
        return id
    }

    /**
     * 「这条会话归本机」的**统一判定**，全仓储分流都走它，别再各写各的。
     *
     * 三个条件满足其一就算本机：
     * 1. 引擎归属记录是 CUSTOM（正常路径）；
     * 2. 在自定义会话标记表里（sendCustom / 历史迁移都登记过）；
     * 3. 本机消息流里有它的内容（SessionStore 有文件）。
     *
     * 为什么不止看第 1 条：归属记录按账号前缀存在 SharedPreferences 里，换账号、
     * 记录被清都会让 kindOf 回落成「云端」——然后这条会话就走了 ACP 回放，
     * 云端侧本来是空的，界面被空快照盖掉，用户看到的就是「历史消息消失了」。
     * 消息流里有内容的会话在云端不可能有内容，按本机处理永远是对的。
     */
    private fun isLocalConv(id: String?): Boolean =
        id != null &&
            (settings.isLocal(id) ||
                settings.isCustomConversation(id) ||
                settings.sessionStore.has(id))

    private suspend fun sessionOf(id: String): CloudSession {
        sessions[id]?.let { return it }
        return api.fetchSession(id).also { sessions[id] = it }
    }

    private suspend fun connection(id: String): AcpConnection {
        // 硬闸：本机会话不走云端 ACP。推理与历史全在本机，这条会话在云端是空的；
        // 建连只会带来两种伤害——回放空历史覆盖界面（publish 现在已经拦了，
        // 这是第二道闸）、以及留下一条永不收尾的长连接。宁可当场报错暴露调用方，
        // 也不允许静默地拿空快照冲掉用户的消息。
        check(!isLocalConv(id)) { "本机会话不走云端 ACP 连接（$id）" }
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
        // 本机会话的界面真相是本地消息流（SessionStore）：推理在本机，云端那条会话
        // 从来没收到过 prompt，快照必然是空的。谁在这条会话上留下了活连接
        // （历史迁移、引擎切换的残留），它 onUpdate 一推就把本机历史整份覆盖成
        // 空白——「消息消失」的第二个入口，和落盘竞态是两回事。在这里一刀切：
        // 本机会话永远不吃云端快照。
        if (isLocalConv(id)) return@synchronized
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

    /** 把本地附件送进沙箱，让云端 agent 直接用路径读写；失败时退回内联内容块。 */
    private suspend fun buildPrompt(
        session: CloudSession,
        text: String,
        attachments: List<PendingAttachment>
    ): Pair<JSONArray, JSONArray> {
        val blocks = JSONArray()
        val sourceBlocks = JSONArray()
        val sandboxPaths = mutableListOf<Pair<String, String>>()
        val model = currentModel(session.conversationId)

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

        /** 手动命令的超时（秒）。装依赖、跑测试这类动作比工具调用更慢。 */
        const val SHELL_TIMEOUT = 300

        /** 实时输出在内存里最多留这么多字符，防止 `yes` 一类的命令刷爆堆。 */
        const val LIVE_MAX_CHARS = 32_000

        /** 终端命令历史条数。 */
        const val CMD_HISTORY_MAX = 50

        /** 一个会话最多开几个终端标签。手机上再多也没有可读性。 */
        const val MAX_TERMINAL_TABS = 5

        /** 第一个标签的默认名。agent 跑的命令固定写进它。 */
        const val DEFAULT_TAB_TITLE = "终端 1"

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

        /** 同一条会话两次自愈之间的冷却：收敛需要时间，连戳没有意义。 */
        const val HEAL_GAP_MS = 60_000L

        /** 自愈用的 load 等待上限：只求「挂上」，不追求把历史放完。 */
        const val HEAL_LOAD_TIMEOUT = 15_000L
    }
}
