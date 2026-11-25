package cn.cloudbuddy.app.data

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
 * 抽成接口而不是直接读 SharedPreferences，一是让实现能在 JVM 单测里跑，
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
 * 一次工具调用的进度事件。UI 靠它把「正在跑什么」显示出来，
 * 而不是只能读一句拼好的字符串。
 */
data class SandboxToolEvent(
    /** 工具名，如 run_shell / edit_file。 */
    val tool: String,
    /** 人类可读的详情，如命令前 60 字、被编辑的文件路径。 */
    val detail: String,
    /** true 表示开始执行，false 表示已结束。 */
    val running: Boolean,
)

/**
 * 一个会话的 agent 运行时。
 *
 * 上层（仓储）只需要 [send]：切会话意味着换沙箱，实例由装配层按会话缓存，
 * 拿到的必须已经是另一份——这里不再暴露 endpoint/token，避免上层拿错沙箱。
 */
interface AgentSession {
    suspend fun send(userText: String): AgentReply
}

/**
 * 本机 agent 的装配口。
 *
 * 为什么是接口：发行版分两种，`core` 不含 Koog、不含 OpenAI 兼容端点，
 * 依赖图里根本没有这些库。两种包共用同一份仓储代码，靠 `AgentSupport`
 * （由各自源集提供实现）在编译期换掉，而不是在运行时 `if (版本)` 判来判去——
 * 那样 core 包里仍然会链接到不存在的类。
 *
 * 做法参考开源阅读 MD3 版：`core` 用 `UnavailableMangaLocalEngine` 装配，
 * 增强版才把 ONNX / ML Kit 放进依赖图。
 */
interface AgentRuntime {
    fun get(
        conversationId: String,
        endpoint: String,
        token: String,
        config: CustomModelConfig,
        tokenRefresher: suspend () -> String? = { null },
        onToolEvent: (SandboxToolEvent) -> Unit = {},
        onExecuted: (String, Long, SandboxExec.Result) -> Unit = { _, _, _ -> },
    ): AgentSession

    fun drop(conversationId: String)

    fun clear()

    /**
     * 用最小请求验证自定义模型配置是否可用。
     *
     * 单独做这个入口的理由：用户填完 key 只能靠"发一条消息试试"，失败了你也不知道
     * 是 key 错、地址错还是模型名错。这里直接要一句最短回复，把具体错误原样带回来。
     */
    suspend fun testConnection(cfg: CustomModelConfig): Result<String>
}
