package cn.cloudbuddy.app.data

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个会话的 agent 运行时。
 *
 * **会话隔离是这个类的核心职责**：沙箱是按会话分配的，所以 endpoint/token、
 * 工具集、对话历史都必须绑定 conversationId。切会话时拿到的必须是另一份实例，
 * 否则命令会跑到别的沙箱里去（对应项目里"坑 17"的同类问题）。
 *
 * 对话引擎是自研的 [OpenAiStreamClient]（2026-11 起，不再借道 Koog 的 agent 框架）：
 * Koog 不解析 `reasoning_content`、思考参数塞不进请求体，还把最终文本之外的一切
 * 都关在框架里。自研循环把思考、工具调用、最终回答全部拿到手，逐段回调给上层。
 */
class SessionAgent(
    val conversationId: String,
    private val exec: SandboxExec,
    /** 该会话沙箱的 envd 网关地址。换了会话就等于换了沙箱，不能复用实例。 */
    val endpoint: String,
    private val token: String,
    val config: CustomModelConfig,
    /** token 过期时向云端换新（72 小时有效期），由上层注入以免这里认识 Repository。 */
    private val tokenRefresher: suspend () -> String? = { null },
    private val memory: AgentMemory = AgentMemory.None,
    /**
     * 一段思考想完时的回调（一次流式请求一段；工具循环里可能有多段）。
     * 上层用它把思考消息定稿。不思考的模型不会触发。
     */
    private val onThought: (String) -> Unit = {},
    private val onToolEvent: (SandboxToolEvent) -> Unit = {},
    /** 每条命令执行完的完整回执，交给上层写命令日志（终端窗口）。 */
    private val onExecuted: (String, Long, SandboxExec.Result) -> Unit = { _, _, _ -> },
    /** 流式增量：THOUGHT / ASSISTANT 逐字回调，上层做实时显示。 */
    private val onDelta: (ChatRole, String) -> Unit = { _, _ -> },
    /** 本机技能库；为空表示这条 agent 不带技能（旧构造点、单测）。 */
    private val skills: LocalSkillStore? = null,
    /** 本机直连的 MCP 服务器；连接在第一次发消息时建立。 */
    private val mcpServers: () -> List<McpServer> = { emptyList() },
) : AgentSession {

    /** 本轮执行过的工具摘要，供 [onToolEvent] 之外的持久化使用。 */
    private val toolNotes = ArrayList<String>()

    /** 懒建的流式客户端（ktor HttpClient 连接池跨轮复用）。 */
    private val llm = LazyClient()

    /** 本轮是否已经动过沙箱。动过就不能自动重试，否则副作用会执行两次。 */
    @Volatile
    private var sandboxTouched = false

    /** MCP 连接在首次使用时建立；失败不阻塞对话，只记一条问题。 */
    private val mcp = LocalMcp()
    private var mcpSession: LocalMcp.Session? = null
    private var mcpSignature: String = ""

    /** 本轮启用的技能；每次 send 时重算，用户刚导入的技能下一轮就能用。 */
    private var activeSkills: List<LocalSkill> = emptyList()

    private val tools = SandboxTools(exec, endpoint, token, tokenRefresher, { ev ->
        if (ev.running) {
            sandboxTouched = true
            toolNotes.add(ev.tool + ": " + ev.detail.take(80))
        }
        onToolEvent(ev)
    }, onExecuted)

    /**
     * 跑一轮对话：流式请求 → 有工具调用就执行 → 带着结果再问 → 直到出最终回答。
     *
     * 与旧 Koog 版的语义对齐：重试、中断留痕、失败落盘都在 [send]；
     * 这里只负责单次（可能多步的）执行。
     */
    override suspend fun send(userText: String): AgentReply {
        if (!config.usable) {
            return AgentReply("自定义模型未配置完整：需要接口地址、API Key 与模型 ID。请到「设置 → 自定义模型」补齐。")
        }

        toolNotes.clear()
        sandboxTouched = false
        // 每轮重算启用的技能：用户刚在设置里加/停的技能，这一轮就该生效。
        // 读目录是阻塞 IO，放到 IO 线程，别让首字延迟里混进文件操作。
        activeSkills = if (skills == null) emptyList() else withContext(Dispatchers.IO) { skills.enabled() }
        // **先把这一问落盘**，再开始跑。
        //
        // 以前是整轮成功结束时才把「提问 + 回答」一起写进历史，后果是：跑长任务
        // （看一个仓库、装个依赖动辄几十秒）时用户退出去再进来，任务还没跑完，
        // 磁盘上什么都没有 —— 会话看起来是空的，用户会以为消息被吞了。
        // 提问是用户已经说过的话，任何中断都不该让它消失。
        remember(AgentTurn(fromUser = true, text = userText))

        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= MAX_RETRIES) {
            try {
                return runOnce(userText)
            } catch (e: CancellationException) {
                // 用户点停止：不是错误，原样往上抛让上层安静收尾。
                // 但要留一条痕迹：重进这个会话时得能看出这轮是被中断的，而不是凭空少一段。
                remember(AgentTurn(fromUser = false, text = INTERRUPTED, error = true))
                throw e
            } catch (e: Exception) {
                lastError = e
                // 只有「还没碰过沙箱」时才敢重试：一旦执行过工具，重跑会把
                // 写文件、删文件这类操作做第二次，比直接失败危险得多。
                if (sandboxTouched || !isRetryable(e)) break
                attempt++
                if (attempt > MAX_RETRIES) break
                delay(RETRY_BASE_MS * attempt)
            }
        }
        val msg = lastError?.message ?: lastError?.let { it::class.simpleName } ?: "未知错误"
        val text = "调用自定义模型失败：$msg"
        // 失败同样要落盘：用户重进时至少能看到「这条发过、当时失败了」，
        // 而不是整轮蒸发。标成 error 之后，下一轮回放会跳过它（模型不该把
        // 报错当成自己说过的话），只作为界面上的记录存在。
        remember(AgentTurn(fromUser = false, text = text, error = true))
        return AgentReply(text)
    }

    /** 真正跑一轮；异常交由 [send] 决定是否重试。 */
    private suspend fun runOnce(userText: String): AgentReply {
        val client = llm.getOrCreate { OpenAiStreamClient() }

        // 工具表 = 沙箱工具 + 技能读取 + MCP 远端工具，全部适配成自研 ChatTool。
        // MCP 是外部依赖，连不上只是少一批工具，不能让整轮对话失败（见 [ensureMcp]）。
        val registry = tools.registry() +
            (if (activeSkills.isEmpty() || skills == null) ToolRegistry.EMPTY else LocalSkillTools(skills).registry()) +
            ensureMcp()
        val chatTools = registry.tools.map { it.asChatTool() }
        val toolMap = chatTools.associateBy { it.name }

        // 消息表：系统提示 + 历史（回放窗口）+ 本轮输入。
        val messages = ArrayList<ChatMsg>()
        messages.add(ChatMsg.System(systemPrompt()))
        val history = memory.load(conversationId).filterNot { it.error }
        val replay = if (history.lastOrNull()?.let { it.fromUser && it.text == userText } == true) {
            history.dropLast(1)
        } else {
            history
        }
        for (t in replayWindow(replay)) {
            if (t.fromUser) {
                messages.add(ChatMsg.User(t.text))
            } else {
                // 工具摘要附在回答前，让模型接着上一轮的进度做事。
                val text = if (t.note.isNullOrBlank()) t.text else "（上轮调用：${t.note}）\n${t.text}"
                messages.add(ChatMsg.Assistant(text))
            }
        }
        // 「每条消息之前」的片段贴在这里：它本来就该紧挨着用户这句话。
        // 塞进系统提示词的话，隔着整段历史，模型很容易当耳旁风。
        val input = config.userPrefix.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n$userText" }
            ?: userText
        messages.add(ChatMsg.User(input))

        val maxIterations = config.maxIterations.coerceIn(1, 200)
        var iterations = 0
        var answer = ""
        while (true) {
            val result = client.stream(
                config, messages, chatTools,
                onReasoningDelta = { seg -> runCatching { onDelta(ChatRole.THOUGHT, seg) } },
                onContentDelta = { seg -> runCatching { onDelta(ChatRole.ASSISTANT, seg) } },
            )
            // 这段思考想完了：定稿上层正在流式显示的思考消息（旧 onThought 契约）。
            if (result.reasoning.isNotBlank()) runCatching { onThought(result.reasoning) }

            if (result.toolCalls.isEmpty()) {
                answer = result.content
                break
            }

            // 协议要求：带工具调用的 assistant 消息要和工具结果一起回传。
            messages.add(ChatMsg.Assistant(result.content, result.toolCalls))
            for (call in result.toolCalls) {
                val out = executeTool(toolMap, call)
                messages.add(ChatMsg.ToolResult(call.id, out))
            }
            if (++iterations >= maxIterations) {
                answer = result.content + "\n（已达最大工具调用次数 $maxIterations，停止执行）"
                break
            }
        }

        // 只写回答：提问已经在 [send] 开头写过了。这一轮真正跑完，才算有了「回复」。
        remember(
            AgentTurn(
                fromUser = false,
                text = answer,
                note = toolNotes.distinct().takeIf { it.isNotEmpty() }?.joinToString("；"),
            )
        )

        return AgentReply(answer, toolNotes.distinct())
    }

    /** 执行一次工具调用。任何失败都变成一段错误文本回给模型，让它自己调整。 */
    private suspend fun executeTool(toolMap: Map<String, ChatTool>, call: ChatToolCall): String {
        val tool = toolMap[call.name]
            ?: return "[error] 未注册的工具 ${call.name}。可用工具：${toolMap.keys.joinToString("、")}"
        val args = runCatching {
            Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        }.getOrElse {
            return "[error] 参数不是合法 JSON 对象：${call.arguments.take(200)}"
        }
        return try {
            tool.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "[error] ${e.message ?: e::class.simpleName}"
        }
    }

    /**
     * 往消息流里追加一条。
     *
     * 只追加、不重写整段：落盘的是完整消息流（思考与工具过程都在里面），
     * 整体覆盖会让那些过程消息在每次写历史时被抹掉——重进会话就只剩问答两行，
     * 正是「会话像是被人删过一半」的来源。上下文长度由回放窗口截取。
     */
    private fun remember(turn: AgentTurn) {
        memory.append(conversationId, turn)
    }

    /**
     * 只回放最近若干轮，避免上下文无限膨胀把模型打爆。
     *
     * 截断必须对齐到用户提问开头：留下一条孤零零的 assistant，模型会以为自己
     * 刚说完话没人接，回放出来就是「自问自答」。
     */
    private fun replayWindow(turns: List<AgentTurn>): List<AgentTurn> {
        val keep = config.contextTurns.coerceIn(1, 50) * 2
        var window = if (turns.size <= keep) turns else turns.takeLast(keep)
        while (window.size > 1 && !window.first().fromUser) window = window.drop(1)
        return window
    }

    /**
     * 建立（或复用）MCP 连接并返回它的工具表。
     *
     * 三条原则：
     * 1. **失败不抛出**——MCP 服务器是别人的服务，挂了不该让整轮对话发不出去；
     * 2. **按配置签名缓存**——每次发消息都重连会把 SSE 长连接反复建断，既慢又费电；
     * 3. **配置变了就重连**——用户改了 url 或勾掉一条，下一轮必须按新的来。
     */
    private suspend fun ensureMcp(): ToolRegistry {
        val wanted = mcpServers().filter { it.enabled && it.local }
        val signature = wanted.joinToString("|") { it.id + it.raw }
        if (signature.isEmpty()) {
            if (mcpSession != null) {
                mcp.close()
                mcpSession = null
                mcpSignature = ""
            }
            return ToolRegistry.EMPTY
        }
        if (mcpSession != null && signature == mcpSignature) return mcpSession!!.registry

        mcp.close()
        val session = try {
            withContext(Dispatchers.IO) { mcp.connect(wanted) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 连都连不上（例如 DNS 挂了）：当作一条问题记下来，工具表为空。
            LocalMcp.Session(ToolRegistry.EMPTY, emptyList(), listOf("MCP 连接失败：" + (e.message ?: "未知错误")))
        }
        mcpSession = session
        mcpSignature = signature
        // 问题写进本轮的工具摘要：用户能在折叠条里看到「为什么这个工具没出现」，
        // 而不用去翻 logcat。
        session.problems.forEach { toolNotes.add("mcp: " + it) }
        return session.registry
    }

    /**
     * 系统提示 = 角色与沙箱规则 + 提示词片段 + 可用技能清单。
     *
     * 技能只放「名字 + 一句话」，正文等模型调 `use_skill` 再读（渐进披露）：
     * 一条技能动辄上万字，十来条全塞进来，每轮都白付这份 token。
     */
    private fun systemPrompt(): String {
        val base = config.effectiveSystemPrompt
        val brief = SkillPrompt.brief(activeSkills)
        return if (brief.isBlank()) base else base + "\n\n" + brief
    }

    /**
     * 判断异常值不值得重试。
     *
     * 鉴权失败、模型不存在、参数非法这类重试多少次都一样，只会浪费用户的钱和时间；
     * 网络抖动、超时、5xx 才是重试能救的。
     */
    private fun isRetryable(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase()
        if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized") ||
            msg.contains("invalid api key") || msg.contains("invalid_api_key") ||
            msg.contains("404") || msg.contains("model not found") ||
            msg.contains("400") || msg.contains("invalid_request")
        ) {
            return false
        }
        return msg.contains("timeout") || msg.contains("timed out") ||
            msg.contains("connection") || msg.contains("socket") ||
            msg.contains("unreachable") || msg.contains("502") ||
            msg.contains("503") || msg.contains("504") || msg.contains("429") ||
            e is java.io.IOException
    }

    /**
     * 换会话或退出时释放。
     *
     * **不能在这里清历史**：消息流是用户唯一的会话记录（云端那条是空的），
     * 清掉就再也找不回来。这里只放内存里的 HTTP 连接。
     */
    fun close() {
        llm.close()
        mcp.close()
        mcpSession = null
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_BASE_MS = 800L

        /** 被用户停止时留在历史里的那句话；重进会话能看出这轮没跑完。 */
        const val INTERRUPTED = "（本轮已中断，可重新发送）"
    }
}

/**
 * 懒建的流式客户端。
 *
 * 不用 `by lazy` 是为了能在 [SessionAgent.close] 里主动关掉：`by lazy` 分不清
 * 「还没建」和「已经关了」，强行取值反而会白建一个连接池出来。
 */
private class LazyClient {
    @Volatile
    private var client: OpenAiStreamClient? = null

    fun getOrCreate(factory: () -> OpenAiStreamClient): OpenAiStreamClient =
        client ?: synchronized(this) { client ?: factory().also { client = it } }

    fun close() {
        val c = synchronized(this) { client.also { client = null } } ?: return
        runCatching { c.close() }
    }
}

class SessionAgentHub(
    private val exec: SandboxExec,
    private val settings: AppSettings,
) : AgentRuntime {

    private val agents = ConcurrentHashMap<String, SessionAgent>()

    override fun get(
        conversationId: String,
        endpoint: String,
        token: String,
        config: CustomModelConfig,
        tokenRefresher: suspend () -> String?,
        onThought: (String) -> Unit,
        onToolEvent: (SandboxToolEvent) -> Unit,
        onExecuted: (String, Long, SandboxExec.Result) -> Unit,
        onDelta: (ChatRole, String) -> Unit,
    ): SessionAgent {
        val existing = agents[conversationId]
        // 换会话（endpoint 变了）或改了模型配置，都必须重建：
        // 复用旧实例会让用户改了 key/模型却仍走老配置，且旧 endpoint 指向另一个沙箱。
        // token 刷新不触发重建：SandboxTools 内部会就地换新 token。
        if (existing != null) {
            if (existing.endpoint == endpoint && existing.config == config) return existing
            agents.remove(conversationId)
        }
        val created = SessionAgent(
            conversationId, exec, endpoint, token, config,
            tokenRefresher, settings.agentMemory, onThought, onToolEvent, onExecuted, onDelta,
            // 技能与 MCP 都在「本机模型」那一栏配置，只喂本机会话。
            // 传 getter 而不是当时的快照：用户在设置里改了配置，下一条消息就该按新的来。
            skills = settings.localSkills,
            mcpServers = { settings.mcp.localServers() },
        )
        agents[conversationId] = created
        return created
    }

    override fun drop(conversationId: String) {
        agents.remove(conversationId)
    }

    override fun clear() = agents.clear()

    /**
     * 用最小请求验证自定义模型配置是否可用。
     *
     * 放在实现侧而不是仓储里：core 版没有本机引擎，这段代码连编译都不该发生。
     */
    override suspend fun testConnection(cfg: CustomModelConfig): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!cfg.usable) throw CloudException("地址、API Key、模型 ID 需要全部填写")
                val client = OpenAiStreamClient()
                try {
                    val started = System.currentTimeMillis()
                    val result = client.stream(cfg, listOf(ChatMsg.User("只回复两个字：可用")))
                    val cost = System.currentTimeMillis() - started
                    val reply = result.content.trim().ifEmpty { "（模型未返回文本，但请求成功）" }
                    "连接成功（${cost}ms）· 回复：${reply.take(60)}"
                } finally {
                    client.close()
                }
            }
        }
}
