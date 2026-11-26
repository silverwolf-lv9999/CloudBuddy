package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject

/** ACP 事件归约后的快照（不可变，供 UI 直接消费）。 */
data class AcpSnapshot(
    val messages: List<ChatMessage> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val state: TurnState = TurnState.IDLE,
    val error: String? = null,
    val ask: AskQuestion? = null
)

/**
 * 单个工具输出最多留这么多字符：日志型工具（构建、装包）能吐几万字，
 * 全量落盘会让会话文件膨胀，而界面上也只是个可滚动的预览框。
 */
private const val TOOL_DETAIL_LIMIT = 2000

/**
 * 把 ACP 的 `session/update` 事件流归约成消息列表。
 *
 * 规则（与官方 web 端行为对齐）：
 * - `user_message_chunk` / `agent_message_chunk` / `agent_thought_chunk` 增量拼接，
 *   空白分片不能丢；
 * - `tool_call` / `tool_call_update` 只保留标题与状态，内容里的文件链接同样收集；
 *   输出正文另存一份到 [ChatMessage.detail]，供界面点开工具行查看（只做展示，不回放给模型）；
 * - 完成信号来自 prompt RPC 的 `stopReason`、`session/endTurn`、`state_update` 或
 *   `session_info_update` 里的 `codebuddy.ai/status`；
 * - 历史回放（`mode=history` / snapshot）不能把新一轮对话提前判成完成。
 */
class AcpReducer(private val sessionId: String, private val conversationId: String = sessionId) {

    /**
     * 是否采信 SSE 上报的「运行中」。
     *
     * 语义是「回放已收尾、接下来收到的是实时事件」，**不是**「这一轮是不是本客户端发起的」。
     * 1.3.7 曾按后者实现：大退重进后云端任务明明在跑，但因为不是本客户端发起的，
     * 实时 `working` 全被挡掉——消息分片照常上屏，状态角标和输入栏却一直显示空闲。
     *
     * 历史状态的拦截已经由 [accept] 的 `!history` 完成（回放期的 working 根本不会走到
     * [updateState]），所以实时阶段必须放行，不管是谁发起的那一轮。
     */
    @Volatile var allowRemoteRunning: Boolean = true

    private val messages = mutableListOf<ChatMessage>()
    private val artifacts = linkedMapOf<String, Artifact>()
    private var state: TurnState = TurnState.IDLE
    private var error: String? = null
    private var ask: AskQuestion? = null
    private var boundary = true

    /** 本轮 prompt 已由 [beginTurn] 写进本地消息，云端回显只需吞掉、不必再渲染。 */
    private var localEcho = false

    /** 本轮是否仍在进行中（`beginTurn` 到 `finishTurn`）。用于挡住中途的「空闲」上报。 */
    private var turnActive = false

    /**
     * 回放收尾时「这一轮其实还没收尾」——历史最后停在未完成的工具调用上。
     *
     * 用户实测：工具调用进行中刷新 / 重进会话，界面直接跳成「已完成」，后续消息也不再出现。
     * 成因是 [finishReplay] 为了让旧会话不转圈，把历史里的 `running` 工具擦成 NONE，
     * 于是 `turnActive` 与 `hasRunningTool()` 双双为假；紧接着一条 `completed` 状态上报
     * （服务端此刻并没有 prompt 在途，随手就报终态）就把角标写成「已完成」。
     *
     * 所以回放结束时要把这个事实单独记下来，直到收到正式收尾信号
     * （`session/endTurn` / prompt 响应 / 用户主动停止）才清。
     */
    private var openTurn = false

    private var seq = 0
    private var streamingIndex = -1
    private val toolIndex = mutableMapOf<String, Int>()

    /**
     * 轮次编号。用户每提一次问就 +1，这一轮里的思考 / 工具调用 / 过程叙述都归入同一 turn，
     * 界面据此把它们折叠成一组，只留用户提问与最终回答。
     *
     * 从 -1 开始：`beginTurn()` 或历史回放里第一条用户消息把它推到 0。
     */
    private var turnSeq = -1

    @Synchronized
    fun snapshot(): AcpSnapshot = AcpSnapshot(messages.toList(), artifacts.values.toList(), state, error, ask)

    @Synchronized
    fun beginTurn(text: String) {
        closeStream()
        state = TurnState.RUNNING
        error = null
        ask = null
        turnActive = true
        // 新一轮开始：上一轮遗留的「未收尾」判定作废，收尾由本轮自己的信号决定。
        openTurn = false
        // 无条件开启新的一轮：即使这次只发附件（正文为空），过程步骤也要归到本轮。
        turnSeq++
        if (text.isNotBlank()) {
            messages += ChatMessage("m${seq++}", ChatRole.USER, text, turn = turnSeq)
            localEcho = true
        }
        boundary = true
        streamingIndex = -1
    }

    @Synchronized
    fun finishTurn(result: JSONObject?, failure: JSONObject? = null) {
        closeStream()
        turnActive = false
        localEcho = false
        // 正式收尾信号到了：回放期留下的「未收尾」判定可以放下了。
        openTurn = false
        if (failure != null) {
            state = TurnState.FAILED
            error = failure.optString("message").ifBlank { "云端执行失败" }
        } else {
            val stop = result?.optString("stopReason").orEmpty()
            if (stop.isNotBlank()) {
                state = when (stop) {
                    "cancelled", "canceled" -> TurnState.STOPPED
                    "refusal" -> TurnState.FAILED
                    else -> TurnState.DONE
                }
                if (state == TurnState.FAILED) error = "模型拒绝了这次请求"
            }
        }
        boundary = true
        streamingIndex = -1
    }

    @Synchronized
    fun markStopped() {
        closeStream()
        state = TurnState.STOPPED
        turnActive = false
        localEcho = false
        openTurn = false
        boundary = true
    }

    /**
     * 用户回答了提问：把选择作为一条用户消息留痕，并让本轮继续。
     *
     * 回答走的是 `resolveInterruption`，不进 prompt 流程，云端不会把它回显成消息；
     * 不主动记一笔的话，界面上用户刚做的选择会凭空消失，事后翻记录也看不到依据。
     */
    @Synchronized
    fun recordAnswer(text: String) {
        closeStream()
        ask = null
        if (text.isNotBlank()) {
            if (turnSeq < 0) turnSeq = 0
            messages += ChatMessage("m${seq++}", ChatRole.USER, text, turn = turnSeq)
        }
        state = TurnState.RUNNING
        turnActive = true
        localEcho = true
        boundary = true
        streamingIndex = -1
    }

    /** 已归约的消息条数，供回放进度使用（不复制列表）。 */
    @Synchronized
    fun messageCount(): Int = messages.size

    /**
     * 历史回放收尾：把历史收敛成「终态」，界面拿到的就是最新样子。
     *
     * 回放期间每条分片都被标成 `streaming`、工具调用停在 `running`，这些只是
     * 当时的中间态痕迹。不清掉的话，打开旧会话会看到进度动画在重放一遍。
     */
    @Synchronized
    fun finishReplay() {
        closeStream()
        // 先记「这一轮当时还没收尾」，再擦工具卡：顺序反过来就永远看不出它还在跑了。
        // 只认「历史最后停在一条还在转圈的工具调用上」——工具之后模型又说过话，
        // 说明这一轮早就往下走了，不能算悬在这里。
        val tail = messages.lastOrNull()
        openTurn = tail != null && tail.role == ChatRole.TOOL && tail.toolState == ToolState.RUNNING
        for (i in messages.indices) {
            val message = messages[i]
            if (message.role == ChatRole.TOOL && message.toolState == ToolState.RUNNING) {
                messages[i] = message.copy(toolState = ToolState.NONE)
            }
        }
        // 停在未完成工具上的那一轮，云端很可能还在跑（用户就是在这时候刷新/重进的）：
        // 保留「运行中」而不是收敛成空闲，否则角标会显示成已经结束。
        if (openTurn) {
            state = TurnState.RUNNING
        } else if (state == TurnState.RUNNING) {
            // 历史里「没等到收尾信号」的其余轮次不是在执行，回到空闲。
            state = TurnState.IDLE
        }
        turnActive = false
        localEcho = false
        boundary = true
    }

    /**
     * 回放后是否还挂着一轮没收尾（历史停在未完成的工具调用）。
     *
     * 对账层据此拒绝采纳服务端的终态：那一刻的 `completed` 多半是「没有 prompt 在途」
     * 的默认上报，不是真的跑完了。
     */
    @Synchronized
    fun hasOpenTurn(): Boolean = openTurn

    /** @return 是否消费了这个事件（true 表示快照有变化，需要推给 UI）。 */
    @Synchronized
    fun accept(root: JSONObject, replaying: Boolean): Boolean {
        var method = root.optString("method")
        var params = root.optJSONObject("params") ?: return false
        if (method == "extNotification") {
            method = params.optString("method")
            params = params.optJSONObject("params") ?: return false
        }
        if (method == "extRequest" || method == "extMethod") {
            // 扩展请求（如 askuser）在部分网关版本里通过 extRequest 下发。
            val inner = params.optJSONObject("params") ?: params
            if (handleAsk(inner)) return true
            return false
        }
        val target = params.optString("sessionId")
        if (target.isNotBlank() && target != sessionId && target != conversationId) return false

        when (method) {
            "session/endTurn" -> {
                if (!replaying) {
                    closeStream()
                    turnActive = false
                    localEcho = false
                    openTurn = false
                    state = when (params.optString("stopReason")) {
                        "cancelled", "canceled" -> TurnState.STOPPED
                        "refusal" -> TurnState.FAILED
                        else -> TurnState.DONE
                    }
                    // 与 finishReplay 同款清理：收尾时还没等到 tool_call_update 的工具
                    // 不该继续转圈，否则会出现「角标已完成、步骤还在转」的错位。
                    for (i in messages.indices) {
                        val message = messages[i]
                        if (message.role == ChatRole.TOOL && message.toolState == ToolState.RUNNING) {
                            messages[i] = message.copy(toolState = ToolState.NONE)
                        }
                    }
                    boundary = true
                }
                return true
            }

            "_codebuddy.ai/artifact" -> {
                val artifact = params.optJSONObject("artifact") ?: return false
                val uri = artifact.optString("uri")
                if (uri.isBlank()) return false
                if (params.optString("event") == "deleted") artifacts.remove(uri)
                else {
                    val name = sequenceOf(artifact.optString("title"), artifact.optString("name"), SandboxPath.fileName(uri))
                        .firstOrNull { it.isNotBlank() } ?: "产物文件"
                    artifacts[uri] = Artifact(
                        name = name,
                        uri = uri,
                        mimeType = artifact.optString("mimeType").ifBlank { artifact.optString("mime_type") }
                            .takeIf { it.isNotBlank() },
                        downloadUrl = params.optString("url").takeIf { it.isNotBlank() }
                            ?: artifact.optString("url").takeIf { it.isNotBlank() }
                    )
                }
                return true
            }

            "session/request_permission", "session/requestPermission", "_codebuddy.ai/askuser" -> {
                if (handleAsk(params)) return true
                return false
            }
        }

        if (method != "session/update" && method != "sessionUpdate") return false
        val update = params.optJSONObject("update") ?: return false
        val kind = update.optString("sessionUpdate")
        val meta = update.optJSONObject("_meta")
        val cb = meta?.optJSONObject("codebuddy.ai")
        val history = replaying || meta?.optString("codebuddy.ai/mode") == "history" || cb?.optString("mode") == "history"
        if (meta?.optBoolean("codebuddy.ai/isSubAgent") == true || cb?.optBoolean("isSubAgent") == true) return false

        val role = when (kind) {
            "user_message_chunk" -> ChatRole.USER
            "agent_message_chunk", "assistant_message_chunk" -> ChatRole.ASSISTANT
            "agent_thought_chunk" -> ChatRole.THOUGHT
            else -> null
        }
        if (role != null) {
            val content = update.opt("content")
            collectLinks(content)
            val text = textOf(content)
            if (role == ChatRole.USER && !history && localEcho) return false
            if (text.isNotEmpty()) {
                append(role, text)
                return true
            }
            return false
        }

        if (kind == "session_info_update") {
            val next = statusOf(update, meta, cb)
            val snapshotFlag = meta?.optBoolean("codebuddy.ai/isSnapshot") == true || cb?.optBoolean("isSnapshot") == true
            if (!history && !snapshotFlag) {
                TurnStateParser.parse(next)?.let { updateState(it) }
            }
            if (TurnStateParser.parse(next) == TurnState.DONE) boundary = true
            return true
        }

        if (kind == "state_update") {
            if (!history) {
                val next = update.optString("currentState").takeIf { it.isNotBlank() }
                    ?: statusOf(update, meta, cb)
                TurnStateParser.parse(next)?.let { updateState(it) }
            }
            // 只有真的收尾才切断文本流。原实现无条件 `boundary = true`，
            // 会让流式中间的状态更新把一段回答切成多条消息（界面表现为文字跳动）。
            if (state == TurnState.DONE || state == TurnState.FAILED || state == TurnState.STOPPED) {
                boundary = true
            }
            return true
        }

        if (kind == "tool_call" || kind == "tool_call_update") {
            collectLinks(update.opt("content"))
            val id = update.optString("toolCallId")
            val title = update.optString("title").ifBlank { update.optString("toolName") }
            val status = update.optString("status")
            val finished = status in setOf("completed", "failed", "executed")

            // AskUserQuestion 是以**工具调用**的形态下发的：问题在 rawInput.questions 里，
            // 状态停在 pending/executing 等回答。以前这里只把它当普通工具，画一条转圈的步骤行，
            // 卡片永远不弹——用户那边就是「云端停了、界面一片安静」。
            val toolName = update.optString("toolName").ifBlank { update.optString("name") }
                .ifBlank { title }
            if (isAskUserQuestionTool(toolName)) {
                if (!history && !finished) {
                    val raw = update.optJSONObject("rawInput") ?: update.optJSONObject("args")
                    val items = raw?.let { parseAskItems(it) }.orEmpty()
                    if (items.isNotEmpty()) {
                        val first = items.first()
                        return showAsk(
                            AskQuestion(
                                question = first.question.ifBlank { "云端需要你确认后继续" },
                                header = first.header,
                                options = first.options,
                                requestId = id.takeIf { it.isNotBlank() },
                                items = items
                            )
                        )
                    }
                }
                // 答完（或跳过）后卡片要收起来，否则会一直挂着一个已经过期的问题。
                if (finished && ask?.requestId != null && ask?.requestId == id) ask = null
            }

            val index = toolIndex[id]
            val previous = index?.let { messages.getOrNull(it)?.text?.substringBefore('\n') }
            if (title.isNotBlank() || previous != null) {
                val label = title.ifBlank { previous.orEmpty() }
                val toolState = when {
                    status == "completed" || status == "executed" -> ToolState.DONE
                    status == "failed" -> ToolState.FAILED
                    status == "pending" || status == "in_progress" -> ToolState.RUNNING
                    else -> ToolState.NONE
                }
                // 工具输出：ACP 的 content 是内容块数组，取其中文本（图片/资源链接跳过）。
                // tool_call_update 的 content 通常是**全量**更新，有新内容就替换；取不到时
                // （或只有链接）保留上一次的——否则一次状态更新就会把已经拿到的输出擦成空。
                val output = textOf(update.opt("content")).trim()
                val previousDetail = index?.let { messages.getOrNull(it)?.detail }.orEmpty()
                closeStream()
                val message = ChatMessage(
                    id = index?.let { messages[it].id } ?: "m${seq++}",
                    role = ChatRole.TOOL,
                    text = label,
                    toolState = toolState,
                    turn = turnSeq.coerceAtLeast(0),
                    detail = output.ifBlank { previousDetail }.take(TOOL_DETAIL_LIMIT),
                )
                if (index != null && index < messages.size) messages[index] = message
                else {
                    if (id.isNotBlank()) toolIndex[id] = messages.size
                    messages += message
                }
                boundary = true
            }
            return true
        }
        return false
    }

    @Synchronized
    fun currentState(): TurnState = state

    private fun updateState(next: TurnState) {
        if (!allowRemoteRunning && (next == TurnState.RUNNING || next == TurnState.WAITING)) return
        if (next == TurnState.RUNNING && state == TurnState.STOPPED) return
        // 终态上报（DONE/IDLE）在三种情况下都不采信：
        // 1. 本客户端这一轮还在跑（turnActive）：真收尾只认 session/endTurn 与
        //    prompt 响应（finishTurn），它们不走这里；静默间隙（思考几分钟、
        //    工具间隔）里来的 completed 上报一律是杂音。
        // 2. 还有工具在转圈：与谁发起无关（旁观/另一台设备驱动的轮次），
        //    用户实测 agent 等后台任务几分钟时，一条中途上报把角标顶成「已完成」，
        //    工具卡却还在转。
        // 3. 回放结束时就挂着一轮没收尾（历史停在未完成工具）：用户正是在工具调用
        //    中途刷新/重进的，此时服务端没有 prompt 在途，随手报的 completed 不是
        //    真收尾；采信它会让角标跳「已完成」并且不再补后续消息。
        if (next == TurnState.DONE || next == TurnState.IDLE) {
            if (turnActive || hasRunningTool() || openTurn) return
        }
        state = next
        if (next == TurnState.DONE || next == TurnState.FAILED || next == TurnState.STOPPED) {
            turnActive = false
            closeStream()
        }
    }

    private fun hasRunningTool(): Boolean =
        messages.any { it.role == ChatRole.TOOL && it.toolState == ToolState.RUNNING }

    /**
     * 官方 `UKr()`：状态优先取 **`update.sessionStatus` / `update.status`**，
     * 其后才是 `_meta["codebuddy.ai"]` 里的同名字段。
     *
     * 只读 `_meta` 时两层取值不同步（`update` 是 `working`、`_meta` 里是 `idle`），
     * 状态就会在两者之间抖动。
     */
    private fun statusOf(update: JSONObject, meta: JSONObject?, cb: JSONObject?): String? {
        return sequenceOf(
            update.optString("sessionStatus"),
            update.optString("status"),
            cb?.optString("sessionStatus"),
            cb?.optString("status"),
            meta?.optString("sessionStatus"),
            meta?.optString("status"),
            meta?.optString("codebuddy.ai/status")
        ).firstOrNull { it != null && it.isNotBlank() && it != "null" }
    }

    private fun append(role: ChatRole, text: String) {
        val last = messages.lastOrNull()
        val appendable = !boundary && last != null && last.role == role &&
            streamingIndex == messages.lastIndex
        if (appendable) {
            messages[messages.lastIndex] = last!!.copy(text = last.text + text, streaming = true)
        } else {
            closeStream()
            // 历史回放里的用户消息同样开启新的一轮，否则整个历史会挤在同一个折叠组里。
            if (role == ChatRole.USER) turnSeq++
            messages += ChatMessage("m${seq++}", role, text, streaming = true, turn = turnSeq.coerceAtLeast(0))
            streamingIndex = messages.lastIndex
        }
        boundary = false
    }

    private fun closeStream() {
        if (streamingIndex in messages.indices) {
            val message = messages[streamingIndex]
            if (message.streaming) messages[streamingIndex] = message.copy(streaming = false)
        }
        streamingIndex = -1
    }

    /**
     * 接收提问事件并把本轮切到等待状态。解析规则见 [parseAskRequest]：权限确认的
     * `options:[{optionId, name, kind}]`、AskUserQuestion 的 `schema.questions`
     * 都会被收敛成同一组 [AskItem]，界面只需处理一种形状。
     */
    private fun handleAsk(node: JSONObject): Boolean {
        val request = parseAskRequest(node) ?: return false
        return showAsk(request.question)
    }

    /** 外部（连接层）直接送来一个已解析好的提问，比如以 JSON-RPC 请求下发的 `_codebuddy.ai/question`。 */
    @Synchronized
    fun showAsk(question: AskQuestion): Boolean {
        ask = question
        state = TurnState.WAITING
        closeStream()
        return true
    }

    private fun collectLinks(node: Any?) {
        when (node) {
            is JSONObject -> {
                val uri = node.optString("uri")
                val type = node.optString("type")
                if (type == "resource_link" && SandboxPath.of(uri) != null) {
                    artifacts[uri] = Artifact(
                        name = sequenceOf(node.optString("title"), node.optString("name"), SandboxPath.fileName(uri))
                            .firstOrNull { it.isNotBlank() } ?: "产物文件",
                        uri = uri,
                        mimeType = node.optString("mimeType").takeIf { it.isNotBlank() }
                    )
                }
                node.keys().forEach { collectLinks(node.opt(it)) }
            }

            is JSONArray -> (0 until node.length()).forEach { collectLinks(node.opt(it)) }

            is String -> Regex("""agent://(?:files/)?[^\s"'\\()\[\]{}<>]+""").findAll(node).forEach {
                val uri = SandboxPath.trimTail(it.value)
                if (uri !in artifacts) {
                    artifacts[uri] = Artifact(SandboxPath.fileName(uri), uri)
                }
            }
        }
    }

    private fun textOf(node: Any?): String = when (node) {
        is String -> node
        is JSONObject -> when {
            node.optString("type") in setOf("image", "resource_link", "resource") -> ""
            node.has("text") -> node.optString("text")
            else -> textOf(node.opt("content"))
        }

        is JSONArray -> (0 until node.length()).joinToString("") { textOf(node.opt(it)) }
        else -> ""
    }
}
