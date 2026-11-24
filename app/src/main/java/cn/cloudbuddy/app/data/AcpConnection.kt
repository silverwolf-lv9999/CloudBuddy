package cn.cloudbuddy.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 云端任务还在跑、只是本地等待窗口到了。
 *
 * 与 [SocketTimeoutException] 区分开：后者对 initialize/set_model 这类「必须立刻有响应」
 * 的调用成立，但 `session/prompt` 的响应要等整轮跑完才回，长任务天然会超过本地窗口。
 * 上层收到这个异常不能把轮次标成失败，否则就会出现「任务还在转、界面却报超时」。
 */
class PromptWaitTimeout : IOException("云端任务仍在运行")

/** 快照推送的最小间隔：把 SSE 的高密度分片合并成约 16 次/秒的界面刷新。 */
private const val PUSH_INTERVAL_MS = 60L

/** 回放进度上报间隔：只更新一行「已收到 N 条」，不触发消息列表重建。 */
private const val REPLAY_PROGRESS_INTERVAL_MS = 300L

/**
 * 回放静默窗口：只用于提示「载入较慢」，不再据此结束回放。
 *
 * 1.3.3：此前用它提前 endReplay，历史长、工具调用慢时事件间隔会超过 1.5s，
 * 放行后剩余历史就逐条上屏（回放观感）。现在回放结束只认 loadDone。
 */
private const val REPLAY_IDLE_MS = 1_500L

/** 一条历史事件都没收到时，多久提示一次「仍在载入」。 */
private const val REPLAY_HARD_LIMIT_MS = 8_000L

/** prompt SSE 流的兜底上限：长任务可能跑几十分钟，远大于普通 RPC 的窗口。 */
private const val PROMPT_CALL_TIMEOUT_MS = 2 * 60 * 60_000L

/** 等 load 收尾时，用户抢先发消息最多让 prompt 等这么久。 */
private const val LOAD_GATE_MS = 10_000L

/** `initialize` 的正常耗时；真正慢的是后面的历史回放。 */
private const val INIT_TIMEOUT_MS = 30_000L

/**
 * 一条已 load 的 ACP 连接。
 *
 * 传输方式与官方 web 端一致：先 `GET link`（`Accept: text/event-stream`）拿
 * `Acp-Connection-Id`，再 `POST` JSON-RPC 帧。历史回放与实时事件共用这一条流，
 * 因此不能用「每次刷新历史都新开一条连接」的做法，否则实时进度会和界面脱钩。
 */
class AcpConnection(
    private val http: CloudHttp,
    private val settings: AppSettings,
    val session: CloudSession
) : AutoCloseable {

    private val reducer = AcpReducer(session.acpSessionId, session.conversationId)
    private val pending = ConcurrentHashMap<Int, PendingRpc>()
    private val seq = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 服务端**反向请求**（JSON-RPC request，带 id）里待回答的提问。
     *
     * 提问的正式通道是服务端调用客户端方法 `_codebuddy.ai/question`：拿到请求后必须
     * 用同一个 id 回一个 result，否则服务端会一直挂着（官方客户端等 5 分钟）。
     * 权限确认走 `session/request_permission`，回包格式不同，所以要记类型。
     */
    private val pendingAsks = ConcurrentHashMap<String, PendingAsk>()

    /** 回包不能用 SSE 那条读线程发（会自己等自己），单独一个池。 */
    private val replyExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "acp-reply").apply { isDaemon = true }
    }

    private class PendingAsk(val rpcId: Any, val permission: Boolean)

    @Volatile private var transport: okhttp3.Call? = null
    @Volatile private var connectionId: String = ""
    @Volatile private var acpToken: String = ""
    @Volatile private var closed = false

    @Volatile var replaying: Boolean = true
        private set

    /** `session/load` 是否已收尾（正常返回或超时）。 */
    @Volatile var loadDone: Boolean = false
        private set

    /** `session/load` 等到超时：历史可能没放完，但不代表连接不可用。 */
    @Volatile var loadTimedOut: Boolean = false
        private set

    /** 最后一个业务事件的到达时间，供回放静默判定使用。 */
    @Volatile private var lastEventAt: Long = 0L

    @Volatile var lastUsedAt: Long = System.currentTimeMillis()
        private set

    /** 快照回调：回放结束后才推全量，避免界面看到历史一条条冒出来。 */
    @Volatile var onUpdate: ((AcpSnapshot) -> Unit)? = null

    /** 回放进度回调：只报「已收到多少条」，不推消息内容。 */
    @Volatile var onReplayProgress: ((Int) -> Unit)? = null

    /** session/load 响应里下发的可用模型（官方客户端同款来源）。 */
    @Volatile var onModels: ((List<ModelChoice>, String?) -> Unit)? = null

    val alive: Boolean get() = !closed

    /** 回放期间不推快照：历史逐条出现会像在重放当时的步骤，加载完一次性给终态。 */
    fun snapshot(): AcpSnapshot = reducer.snapshot()

    fun open() {
        val url = session.link.replace(Regex("^http://"), "https://")
        val request = http.headers(Request.Builder().url(url).get())
            .header("Authorization", "Bearer ${session.token}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Origin", settings.baseUrl.trimEnd('/'))
            .build()
        val call = http.stream.newCall(request)
        transport = call
        val response: Response = try {
            call.execute()
        } catch (e: Exception) {
            throw CloudException("ACP 连接失败：" + (e.message ?: e.javaClass.simpleName))
        }
        if (!response.isSuccessful) {
            response.close()
            throw CloudException("ACP 接入点返回 HTTP ${response.code}", response.code)
        }
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/event-stream", true)) {
            response.close()
            throw CloudException("ACP 接入点没有返回事件流")
        }
        connectionId = response.header("Acp-Connection-Id")?.trim().orEmpty()
        if (connectionId.isEmpty()) {
            response.close()
            throw CloudException("ACP 服务未返回连接标识")
        }
        acpToken = response.header("acp-session-token")?.trim().orEmpty()

        scope.launch {
            try {
                val source = response.body?.source()
                if (source != null) readSse(source) { data -> receive(data) }
            } catch (_: Throwable) {
                // 流断开由 close() 收尾，重连逻辑在 Repository。
            } finally {
                runCatching { response.close() }
                close()
            }
        }
        scope.launch { watchReplay() }
    }

    /**
     * 回放看门狗：事件流一旦静默超过 [REPLAY_IDLE_MS]，就先把界面放出来。
     *
     * `session/load` 的响应要等整段历史放完才回，长会话下这一步最容易被当成「卡住」。
     * 这里不改变语义——[loadDone] 仍然等真正的响应——只是提前让已收到的消息可见。
     */
    private suspend fun watchReplay() {
        val startedAt = System.currentTimeMillis()
        var slowNoted = false
        // 1.3.3：不再因为「事件静默 1.5s」就 endReplay。回放是否结束只由 loadDone
        // 决定（load 响应到达或等待超时）。历史长、工具调用慢时事件间隔超过 1.5s 很常见，
        // 提前放行会让后续历史事件在 replaying=false 下逐条上屏，正是用户说的「回放」。
        while (!closed && !loadDone) {
            delay(300)
            val now = System.currentTimeMillis()
            val at = lastEventAt
            val idle = if (at > 0) now - at else now - startedAt
            val limit = if (at > 0) REPLAY_IDLE_MS else REPLAY_HARD_LIMIT_MS
            if (idle >= limit && !slowNoted) {
                slowNoted = true
                // 只把进度数字刷新一次，让界面知道「还在载入」；不推消息、不结束回放。
                onReplayProgress?.invoke(reducer.messageCount())
            }
        }
    }

    /**
     * 回放收尾：把历史收敛成「终态」后一次性推给界面。
     *
     * 用户要的是「加载完就是最新消息」，所以这里不做增量呈现——历史里的流式中、
     * 运行中标记都是回放痕迹，必须先擦掉，否则打开旧会话会看到一串假的进度动画。
     */
    private fun endReplay() {
        if (!replaying) return
        reducer.finishReplay()
        replaying = false
        publishCoalesced(force = true)
    }

    /**
     * initialize + session/load。[timeoutMs] 是等 load 响应的上限。
     *
     * 关键取舍：**超时不等于失败**。历史事件是边放边到的，超时只说明「可能还有没放完的
     * 历史」，连接本身仍然可用；把这种情形判成失败，长会话就永远打不开。
     *
     * @return true 表示 load 正常返回；false 表示等到超时（历史可能不完整）。
     */
    fun initializeAndLoad(timeoutMs: Long = 90_000L): Boolean {
        rpc(
            "initialize",
            JSONObject().put("protocolVersion", 1)
                .put("clientCapabilities", JSONObject().put("fs", JSONObject()
                    .put("readTextFile", false)
                    .put("writeTextFile", false))),
            INIT_TIMEOUT_MS
        )
        val complete = try {
            rpc(
                "session/load",
                JSONObject()
                    .put("sessionId", session.acpSessionId)
                    .put("cwd", session.cwd ?: "/workspace")
                    .put("mcpServers", settings.mcp.payload()),
                timeoutMs
            )
            true
        } catch (_: SocketTimeoutException) {
            loadTimedOut = true
            false
        }
        loadDone = true
        endReplay()
        publishCoalesced(force = true)
        return complete
    }

    /** 等 load 收尾，最多 [LOAD_GATE_MS]：让抢先发出的 prompt 不插到回放中间。 */
    private fun awaitLoad() {
        val deadline = System.currentTimeMillis() + LOAD_GATE_MS
        while (!loadDone && !closed && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(80) }
        }
    }

    /**
     * 下发一轮对话；返回时本轮已结束，或本地等待窗口到了（抛 [PromptWaitTimeout]）。
     *
     * 长任务下 prompt 的响应要等云端整轮跑完才回，本地窗口到了不代表失败：
     * SSE 仍在持续推工具调用/正文，reducer 会自己收敛到终态。
     */
    fun prompt(text: String, model: String, blocks: JSONArray, sourceBlocks: JSONArray, timeoutMs: Long) {
        awaitLoad()
        val params = JSONObject()
            .put("sessionId", session.acpSessionId)
            .put("cwd", session.cwd.orEmpty())
            .put("mcpServers", settings.mcp.payload())
            .put("prompt", blocks)
            .put(
                "_meta",
                JSONObject().put(
                    "codebuddy.ai",
                    JSONObject()
                        .put("conversationId", session.conversationId)
                        .put("model", model)
                        .put("extra", JSONObject().put("sourceContentBlocks", sourceBlocks))
                )
            )
        reducer.beginTurn(text)
        publishCoalesced(force = true)
        try {
            rpc("session/prompt", params, timeoutMs, waitKind = WaitKind.PROMPT)
        } catch (_: SocketTimeoutException) {
            // 只表示本地等够了；任务可能仍在云端跑，界面状态交给 SSE 事件收敛。
            throw PromptWaitTimeout()
        }
    }

    fun setModel(modelId: String) {
        rpc("session/set_model", JSONObject().put("sessionId", session.acpSessionId).put("modelId", modelId), 20_000)
    }

    fun cancelTurn() {
        cancelPendingAsk()
        runCatching {
            rpc("session/cancel", JSONObject().put("sessionId", session.acpSessionId), 8_000, notification = true)
        }
        reducer.markStopped()
        publishCoalesced(force = true)
    }

    /** 用户回答提问后，把这句话记进本轮，避免选择在界面上凭空消失。 */
    fun recordAnswer(text: String) {
        reducer.recordAnswer(text)
        publishCoalesced(force = true)
    }

    /**
     * 回传提问的回答。两条通道，按提问是怎么来的选：
     *
     * 1. 服务端以请求下发（`_codebuddy.ai/question` / `request_permission`）→ 用原请求 id
     *    回 result：普通提问 `{outcome:{outcome:"submitted",data:{answers:{...}}}}`，
     *    权限确认 `{outcome:{outcome:"selected",optionId}}`。
     * 2. 以 `tool_call` 形态下发（AskUserQuestion 工具）→ 走 `_codebuddy.ai/resolveInterruption`，
     *    带 `decision:"allow"` 与 `answers`。
     *
     * `answers` 是 `{问题id: 单选给字符串 / 多选给数组}`，官方界面就是这么构造的；
     * 早期版本塞的是一个自定义数组，云端认不出选择。
     */
    fun resolve(question: AskQuestion, answer: AskAnswer) {
        val toolCallId = question.requestId
            ?: throw CloudException("这个问题没有可回传的 toolCallId")
        val answers = buildAnswers(question.allItems, answer)
        val text = question.answerText(answer)

        val pendingAsk = pendingAsks.remove(toolCallId)
        if (pendingAsk != null) {
            if (pendingAsk.permission) {
                val optionId = answer.picks.firstOrNull()?.values?.firstOrNull()
                    ?: text.ifBlank { "allow" }
                reply(
                    pendingAsk.rpcId,
                    JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", optionId))
                )
            } else {
                reply(
                    pendingAsk.rpcId,
                    JSONObject().put(
                        "outcome",
                        JSONObject().put("outcome", "submitted")
                            .put("data", JSONObject().put("answers", answers))
                    )
                )
            }
            return
        }

        val params = JSONObject()
            .put("sessionId", session.acpSessionId)
            .put("toolCallId", toolCallId)
            .put("decision", "allow")
            .put("answers", answers)
        rpc("_codebuddy.ai/resolveInterruption", params, 30_000)
    }

    /** 用户中途取消（点停止）：把还挂着的提问回成 cancelled，别让服务端等满超时。 */
    fun cancelPendingAsk() {
        val requestId = reducer.snapshot().ask?.requestId ?: return
        val pendingAsk = pendingAsks.remove(requestId) ?: return
        reply(pendingAsk.rpcId, cancelledOutcome("cancelled"))
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.forEach { it.latch.countDown() }
        runCatching { transport?.cancel() }
        runCatching { pushExecutor.shutdownNow() }
        runCatching { replyExecutor.shutdownNow() }
        scope.cancel()
    }

    // ---------- 内部 ----------

    private class PendingRpc(val method: String) {
        val latch = CountDownLatch(1)
        @Volatile var response: JSONObject? = null
    }

    private val pushLock = Any()
    private val pushExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "acp-push").apply { isDaemon = true }
    }
    @Volatile private var lastPushAt = 0L
    private var trailingPush: ScheduledFuture<*>? = null

    /**
     * 合并推送：SSE 的分片密度可达每秒上百条，逐条推等于「每片一次全量重组」。
     * 这里按时间窗合并，并保留一次 trailing 补发，保证末片不丢。
     * [force] 用于状态终态与本地乐观更新，必须立刻可见。
     *
     * 回放期**不推消息**：历史一条条冒出来会像在重放当时的步骤，
     * 只上报一个「已收到 N 条」的进度，回放收尾时一次性给终态。
     */
    private fun publishCoalesced(force: Boolean = false) {
        if (closed) return
        // 用 !loadDone 而不是 replaying 作为闸门：replaying 可能被看门狗提前清掉，
        // 而 session/load 还没真正收尾。只看 replaying 会让剩余历史逐条上屏。
        if (!loadDone) {
            publishReplayProgress()
            return
        }
        synchronized(pushLock) {
            val now = System.currentTimeMillis()
            val gap = now - lastPushAt
            if (!force && gap < PUSH_INTERVAL_MS) {
                if (trailingPush?.isDone != false) {
                    trailingPush = pushExecutor.schedule(
                        { flushPush() },
                        PUSH_INTERVAL_MS - gap,
                        TimeUnit.MILLISECONDS
                    )
                }
                return
            }
            emitSnapshotLocked()
        }
    }

    private fun publishReplayProgress() {
        val now = System.currentTimeMillis()
        if (now - lastPushAt < REPLAY_PROGRESS_INTERVAL_MS) return
        lastPushAt = now
        onReplayProgress?.invoke(reducer.messageCount())
    }

    private fun flushPush() {
        synchronized(pushLock) {
            if (closed) return
            emitSnapshotLocked()
        }
    }

    private fun emitSnapshotLocked() {
        lastPushAt = System.currentTimeMillis()
        onUpdate?.invoke(snapshot())
    }

    private fun receive(data: String) {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (root.has("method") && root.has("id")) {
            // 服务端发来的请求（提问、权限确认）。必须回包，否则对面会一直等。
            onServerRequest(root)
            return
        }
        val id = root.optInt("id", -1)
        if (!root.has("method")) {
            val request = pending[id] ?: return
            request.response = root
            if (request.method == "session/prompt") {
                reducer.finishTurn(root.optJSONObject("result"), root.optJSONObject("error"))
            }
            if (request.method == "session/load") {
                if (!root.has("error")) {
                    endReplay()
                    val (models, current) = AcpModels.parse(root.optJSONObject("result"))
                    if (models.isNotEmpty() || current != null) onModels?.invoke(models, current)
                }
            }
            publishCoalesced(force = true)
            request.latch.countDown()
            return
        }
        // 任何一条方法级事件都算「回放还在走」，供静默判定使用。
        lastEventAt = System.currentTimeMillis()
        if (reducer.accept(root, replaying)) publishCoalesced()
        lastUsedAt = lastEventAt
    }

    /**
     * 处理服务端反向请求。
     *
     * 提问（`_codebuddy.ai/question`）与权限确认（`session/request_permission`）都是
     * 「服务端问、客户端答」：不回包服务端就一直挂着。未知方法也要回一个空 result——
     * 宁可让对面早点报错，也不要静默等满 5 分钟。
     */
    private fun onServerRequest(root: JSONObject) {
        val rpcId = root.opt("id") ?: return
        val method = root.optString("method")
        val params = root.optJSONObject("params") ?: JSONObject()
        lastEventAt = System.currentTimeMillis()
        when (method) {
            "_codebuddy.ai/question", "session/request_permission", "session/requestPermission" -> {
                val request = parseAskRequest(params)
                if (request == null) {
                    reply(rpcId, cancelledOutcome("unsupported"))
                    return
                }
                val toolCallId = request.toolCallId
                if (toolCallId != null) {
                    pendingAsks[toolCallId] = PendingAsk(rpcId, method != "_codebuddy.ai/question")
                }
                if (reducer.showAsk(request.question)) publishCoalesced(force = true)
            }

            else -> reply(rpcId, JSONObject())
        }
    }

    private fun cancelledOutcome(reason: String): JSONObject = JSONObject()
        .put("outcome", JSONObject().put("outcome", "cancelled").put("reason", reason))

    /** 回一个 JSON-RPC result。必须在别的线程发，否则 SSE 读线程会等自己的回包。 */
    private fun reply(rpcId: Any, result: JSONObject) {
        if (closed) return
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", rpcId).put("result", result)
        replyExecutor.execute { runCatching { postRpc(payload, 20_000, fatal = false) } }
    }

    private enum class WaitKind { NORMAL, PROMPT }

    private fun rpc(
        method: String,
        params: JSONObject,
        timeoutMs: Long = 30_000,
        notification: Boolean = false,
        waitKind: WaitKind = WaitKind.NORMAL
    ) {
        if (closed) throw IOException("ACP 连接已关闭")
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        val id = if (notification) null else seq.incrementAndGet()
        val request = if (id != null) PendingRpc(method).also { pending[id] = it } else null
        id?.let { payload.put("id", it) }
        try {
            postRpc(payload, timeoutMs, waitKind = waitKind)
            if (request != null) {
                if (!request.latch.await(timeoutMs, TimeUnit.MILLISECONDS) && request.response == null) {
                    throw SocketTimeoutException("ACP $method 等待响应超时")
                }
                request.response?.optJSONObject("error")?.let {
                    throw CloudException(it.optString("message").ifBlank { "ACP $method 失败" })
                }
            }
            lastUsedAt = System.currentTimeMillis()
        } finally {
            if (id != null) pending.remove(id)
        }
    }

    /**
     * [fatal] 为 false 时不因传输错误关闭整条连接：回包失败只会让这一问超时，
     * 不该顺带把正在跑的实时流一起掐掉。
     */
    private fun postRpc(
        payload: JSONObject,
        timeoutMs: Long,
        fatal: Boolean = true,
        waitKind: WaitKind = WaitKind.NORMAL
    ) {
        val request = http.headers(
            Request.Builder().url(session.link.replace(Regex("^http://"), "https://"))
                .post(payload.toString().toRequestBody(CloudHttp.JSON))
        )
            .header("Authorization", "Bearer ${session.token}")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json, text/event-stream")
            .header("Acp-Connection-Id", connectionId)
            .apply { if (acpToken.isNotBlank()) header("acp-session-token", acpToken) }
            .build()
        val call = http.stream.newCall(request)
        // prompt 的 SSE 流会一直读到任务跑完才结束，等待时间实际由这里的 call timeout
        // 决定（不是 rpc 里的 latch.await——流一关响应早处理完了）。普通 RPC 用窗口+30s；
        // prompt 给一个很长的兜底，避免「任务还在转、10 分钟就被客户端掐断报超时」。
        // 真到兜底时抛出的超时会在 prompt() 里转成 PromptWaitTimeout，不标失败。
        val callTimeout = if (waitKind == WaitKind.PROMPT) {
            maxOf(timeoutMs + 30_000, PROMPT_CALL_TIMEOUT_MS)
        } else {
            timeoutMs + 30_000
        }
        call.timeout().timeout(callTimeout, TimeUnit.MILLISECONDS)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw CloudException("ACP 请求失败：HTTP ${response.code}", response.code)
                val source = response.body?.source() ?: return
                val type = response.header("Content-Type").orEmpty()
                if (type.contains("text/event-stream", true)) {
                    readSse(source) { receive(it) }
                } else {
                    val text = source.readUtf8()
                    if (text.isNotBlank()) receive(text)
                }
            }
        } catch (e: InterruptedIOException) {
            throw SocketTimeoutException("ACP 请求超时")
        } catch (e: IOException) {
            // 服务端可能已经接收了 prompt，只丢弃这条传输，交给上层重连。
            if (fatal) close()
            throw e
        }
    }

    private fun readSse(source: BufferedSource, receive: (String) -> Unit) {
        val data = StringBuilder()
        fun flush() {
            if (data.isNotEmpty()) {
                receive(data.toString())
                data.setLength(0)
            }
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                }
                line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") -> Unit
            }
        }
        flush()
    }
}
