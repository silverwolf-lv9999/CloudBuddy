package cn.cloudbuddy.app.data

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.ToolCalls
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义模型（OpenAI 兼容端点）的接入配置。
 *
 * 覆盖 DeepSeek / 通义 / Moonshot / 本地 vLLM 等所有 OpenAI 兼容服务，
 * 只要填对地址与模型 ID。密钥只存在本机设置里，不入库、不进日志、不上传。
 */
data class CustomModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    /** 采样温度。编码类任务偏低更稳，默认 0.3。 */
    val temperature: Double = 0.3,
    /** agent 单轮最多迭代多少次（推理 + 工具调用算一轮）。 */
    val maxIterations: Int = 40,
    /** 回放给模型的历史轮数上限（一往一返算一轮）。 */
    val contextTurns: Int = 10,
    /** 用户自定义的系统提示词；空表示用内置默认（见 [DEFAULT_SYSTEM_PROMPT]）。 */
    val systemPrompt: String = "",
    /** 思考参数写法，见 [ThinkingStyle]（`auto` 表示按接口地址猜）。 */
    val thinkingStyle: String = ThinkingStyle.AUTO.key,
    /** 思考强度，见 [ThinkingEffort]。 */
    val thinkingEffort: String = ThinkingEffort.MEDIUM.key,
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()

    /** 归一化后的端点，见 [EndpointResolver]。 */
    val endpoint: EndpointResolver.Resolved get() = EndpointResolver.resolve(baseUrl)

    /** 实际下发给模型的系统提示词。 */
    val effectiveSystemPrompt: String get() = composeSystemPrompt(systemPrompt)

    companion object {
        /** DeepSeek 官方开放平台的默认值，作为表单预填。 */
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEEPSEEK_MODEL = "deepseek-chat"

        /**
         * 运行环境与工具规则。
         *
         * 这一段**永远**会出现在系统提示词里，用户自定义的内容只负责替换它前面的
         * 角色与风格部分：沙箱路径、工具名、edit_file 的匹配要求这些都是硬事实，
         * 让用户把整段提示词覆盖掉，模型就会开始瞎猜路径、用不存在的工具名，
         * 表现成「自定义提示词一填 agent 就废了」。
         */
        val SANDBOX_BRIEF = """
            你是一个运行在云端 Linux 沙箱中的编程助手。沙箱工作目录是 /workspace，拥有 root 权限，
            可以联网。用户看不到你的工具调用过程，只会看到最终回答。

            工具使用规则：
            1. 需要了解环境或文件时，先调用工具查看，不要凭空猜测。
            2. 找文件用 find，找内容用 grep，不要用 run_shell 拼 ls/cat 满目录翻。
            3. 修改已有文件用 edit_file（精确替换）；只有新建文件或整篇重写才用 write_file。
            4. edit_file 的 oldText 必须与原文逐字符一致（含缩进），默认要求唯一匹配。
            5. 执行命令后根据退出码与 stderr 判断成败，失败时先诊断再换方法，不要重复同样的失败命令。
            6. 长任务（编译、装依赖）给 run_shell 传更大的 timeoutSec。
        """.trimIndent()

        /** 回答风格规则；用户自定义系统提示词时由用户接管，这一段不再追加。 */
        val DEFAULT_STYLE = """
            回答规则：
            1. 使用简体中文，简洁直接，不寒暄。
            2. 涉及代码改动时说明改了哪个文件、改了什么。
            3. 做完就给出结论，不要把中间过程复述一遍。
        """.trimIndent()

        val DEFAULT_SYSTEM_PROMPT: String = SANDBOX_BRIEF + "\n\n" + DEFAULT_STYLE

        /**
         * 拼出最终系统提示词：自定义在前、沙箱规则在后。
         *
         * 抽成纯函数是为了能单测——提示词错了只会表现为「模型变笨」，不会抛异常，
         * 光靠跑一次真模型很难定位。
         */
        fun composeSystemPrompt(custom: String): String {
            val trimmed = custom.trim()
            if (trimmed.isEmpty()) return DEFAULT_SYSTEM_PROMPT
            // 用户自己贴了沙箱规则就别追加两遍（重复会白烧 token，也让模型注意力分散）。
            if (trimmed.contains("沙箱工作目录是 /workspace")) return trimmed
            return trimmed + "\n\n" + SANDBOX_BRIEF
        }
    }
}

/**
 * 一轮对话的持久化形态。
 *
 * 只存 user / assistant 文本。[note] 记录该轮调用过的工具（如 `run_shell: ls -la`），
 * 回放时附在回答前面——不然模型换了一轮就"忘记"自己刚才读过哪些文件，
 * 会反复读同一个文件、重复执行同样的命令。
 */
data class AgentTurn(
    val fromUser: Boolean,
    val text: String,
    val note: String? = null,
)

/**
 * agent 历史的存取。
 *
 * 抽成接口而不是直接读 SharedPreferences，一是让 [SessionAgent] 能在 JVM 单测里跑，
 * 二是不把 Android 依赖泄进数据层。
 */
interface AgentMemory {
    fun load(conversationId: String): List<AgentTurn>
    fun save(conversationId: String, turns: List<AgentTurn>)
    fun clear(conversationId: String)

    /** 不落盘的实现，供单测与"不记忆"场景使用。 */
    object None : AgentMemory {
        override fun load(conversationId: String): List<AgentTurn> = emptyList()
        override fun save(conversationId: String, turns: List<AgentTurn>) = Unit
        override fun clear(conversationId: String) = Unit
    }
}

/**
 * 一轮对话的结果。
 *
 * [answer] 是给用户看的回答；[toolNotes] 是本轮执行过的工具摘要，用于持久化，
 * 让下一轮的模型知道自己此前做过什么。
 */
data class AgentReply(
    val answer: String,
    val toolNotes: List<String> = emptyList(),
)

/**
 * 一个会话的 agent 运行时。
 *
 * **会话隔离是这个类的核心职责**：沙箱是按会话分配的，所以 endpoint/token、
 * 工具集、对话历史都必须绑定 conversationId。切会话时拿到的必须是另一份实例，
 * 否则命令会跑到别的沙箱里去（对应项目里"坑 17"的同类问题）。
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
    private val onToolEvent: (SandboxToolEvent) -> Unit = {},
    /** 每条命令执行完的完整回执，交给上层写命令日志（终端窗口）。 */
    private val onExecuted: (String, Long, SandboxExec.Result) -> Unit = { _, _, _ -> },
) {

    /** 本轮执行过的工具摘要，供 [onToolEvent] 之外的持久化使用。 */
    private val toolNotes = ArrayList<String>()

    /** 懒建的 LLM 客户端（思考参数钩子挂在它的 ktor 客户端上）。 */
    private val llm = LazyClient()

    /** 本轮是否已经动过沙箱。动过就不能自动重试，否则副作用会执行两次。 */
    @Volatile
    private var sandboxTouched = false

    private val tools = SandboxTools(exec, endpoint, token, tokenRefresher, { ev ->
        if (ev.running) {
            sandboxTouched = true
            toolNotes.add(ev.tool + ": " + ev.detail.take(80))
        }
        onToolEvent(ev)
    }, onExecuted)

    /**
     * 跑一轮对话。
     *
     * 每轮都新建 Koog agent（框架的 `run` 只有单次语义，没有多轮会话对象），
     * 把历史重新灌进 prompt。代价是每轮重建一次 agent 对象，收益是不用操心
     * 框架内部会话状态的生命周期，也不会因为切会话残留上下文。
     */
    suspend fun send(userText: String): AgentReply {
        if (!config.usable) {
            return AgentReply("自定义模型未配置完整：需要接口地址、API Key 与模型 ID。请到「设置 → 自定义模型」补齐。")
        }

        toolNotes.clear()
        sandboxTouched = false

        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= MAX_RETRIES) {
            try {
                return runOnce(userText)
            } catch (e: CancellationException) {
                // 用户点停止：不是错误，原样往上抛让上层安静收尾。
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
        return AgentReply("调用自定义模型失败：$msg")
    }

    /** 真正跑一轮；异常交由 [send] 决定是否重试。 */
    private suspend fun runOnce(userText: String): AgentReply {
        val p = buildPrompt(userText)
        val model = CustomLlmClient.model(config)

        // 每个会话一个客户端，跨轮复用：ktor 的 HttpClient 自带连接池与协程调度器，
        // 每轮新建一个不关会把它们一起攒下来。
        val client = llm.getOrCreate { CustomLlmClient.build(config) }
        // OpenAILLMClient 只是 LLMClient，agent 需要 PromptExecutor；
        // SingleLLMPromptExecutor 是框架提供的官方适配层。
        val executor = SingleLLMPromptExecutor(client)
        val agentConfig = AIAgentConfig(
            prompt = p,
            model = model,
            maxAgentIterations = config.maxIterations.coerceIn(1, 200),
        )

        val agent = AIAgent(
            promptExecutor = executor,
            agentConfig = agentConfig,
            strategy = singleRunStrategy(ToolCalls.SEQUENTIAL),
            toolRegistry = tools.registry(),
            id = "cb-" + conversationId,
        )

        val answer = try {
            agent.run(userText)
        } finally {
            runCatching { agent.close() }
        }

        val history = memory.load(conversationId).toMutableList()
        history.add(AgentTurn(true, userText))
        history.add(
            AgentTurn(
                fromUser = false,
                text = answer,
                note = toolNotes.distinct().takeIf { it.isNotEmpty() }?.joinToString("；"),
            )
        )
        // 只保留最近 N 轮，避免上下文无限膨胀把模型打爆。
        val keep = config.contextTurns.coerceIn(1, 50) * 2
        while (history.size > keep) history.removeAt(0)
        memory.save(conversationId, history)

        return AgentReply(answer, toolNotes.distinct())
    }

    /** 把持久化的历史灌进 prompt。 */
    private fun buildPrompt(userText: String): ai.koog.prompt.dsl.Prompt {
        val history = memory.load(conversationId)
        val base = prompt("cb-" + conversationId) {
            system(systemPrompt())
            for (t in history) {
                if (t.fromUser) {
                    user(t.text)
                } else {
                    // 工具摘要附在回答前，让模型接着上一轮的进度做事。
                    val text = if (t.note.isNullOrBlank()) t.text else "（上轮调用：${t.note}）\n${t.text}"
                    assistant(text)
                }
            }
            user(userText)
        }
        return base.withParams(CustomLlmClient.params(config))
    }

    private fun systemPrompt(): String = config.effectiveSystemPrompt

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

    /** 换会话或退出时释放。历史已经落盘，这里清内存引用与 HTTP 连接。 */
    fun close() {
        memory.clear(conversationId)
        llm.close()
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_BASE_MS = 800L
    }
}

/**
 * 会话 → agent 的映射表。
 *
 * 沙箱跟随会话，所以这里也按会话缓存；会话结束时用 [drop] 释放，
 * 而不是让它无限增长。
 */
/**
 * 懒建的 LLM 客户端。
 *
 * 不用 `by lazy` 是为了能在 [SessionAgent.close] 里主动关掉：`by lazy` 分不清
 * 「还没建」和「已经关了」，强行取值反而会白建一个连接池出来。
 */
private class LazyClient {
    @Volatile
    private var client: OpenAILLMClient? = null

    fun getOrCreate(factory: () -> OpenAILLMClient): OpenAILLMClient =
        client ?: synchronized(this) { client ?: factory().also { client = it } }

    fun close() {
        val c = synchronized(this) { client.also { client = null } } ?: return
        runCatching { c.close() }
    }
}

class SessionAgentHub(
    private val exec: SandboxExec,
    private val memory: AgentMemory = AgentMemory.None,
) {

    private val agents = ConcurrentHashMap<String, SessionAgent>()

    fun get(
        conversationId: String,
        endpoint: String,
        token: String,
        config: CustomModelConfig,
        tokenRefresher: suspend () -> String? = { null },
        onToolEvent: (SandboxToolEvent) -> Unit = {},
        onExecuted: (String, Long, SandboxExec.Result) -> Unit = { _, _, _ -> },
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
            tokenRefresher, memory, onToolEvent, onExecuted,
        )
        agents[conversationId] = created
        return created
    }

    fun drop(conversationId: String) {
        agents.remove(conversationId)
    }

    fun clear() = agents.clear()
}
