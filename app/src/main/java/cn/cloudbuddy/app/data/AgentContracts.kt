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
    /**
     * 已启用的提示词片段（见 [PromptPreset]），按注入位置插进系统提示词两侧。
     *
     * 放在配置里而不是每次现读设置：一轮对话里系统提示词会被取用多次
     * （拼 prompt、重试），中途用户改了片段不该让同一轮前后两次请求的提示词不一样。
     */
    val presets: List<PromptPreset> = emptyList(),
    /**
     * 自定义请求路径。空 = 按 [EndpointResolver] 的统一规则拼；
     * 非空时：http(s) 开头当完整 URL，否则当相对 origin 的路径。
     */
    val chatPathOverride: String = "",
    /** 提供商的自定义请求头，逐条加在 Authorization 之外。 */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 该模型能否工具调用（见 [CustomModel.tools]）。false 时引擎不下发 tools。 */
    val enableTools: Boolean = true,
    /** 该模型能否读图（见 [CustomModel.vision]）。true 时图片以 image_url 直发。 */
    val enableVision: Boolean = false,
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()

    /** 归一化后的端点，见 [EndpointResolver]。 */
    val endpoint: EndpointResolver.Resolved get() = EndpointResolver.resolve(baseUrl)

    /** 流式请求的最终 URL：chatPathOverride 优先，其余走统一拼接规则。 */
    val streamUrl: String
        get() {
            val override = chatPathOverride.trim()
            if (override.isNotEmpty()) {
                if (override.startsWith("http://") || override.startsWith("https://")) return override
                val origin = EndpointResolver.resolve(baseUrl).baseUrl
                if (origin.isNotBlank()) return origin.trimEnd('/') + "/" + override.trimStart('/')
            }
            val resolved = endpoint
            return resolved.baseUrl.trimEnd('/') + "/" + resolved.chatCompletionsPath.trimStart('/')
        }

    /** 实际下发给模型的系统提示词：片段 → 全局提示词（含沙箱规则）→ 片段。 */
    val effectiveSystemPrompt: String get() = PromptComposer.system(systemPrompt, presets)

    /** 贴在每条用户消息前面的片段（`每条消息之前` 那一档），空表示没有。 */
    val userPrefix: String get() = PromptComposer.userPrefix(presets)

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
 *
 * [error] 标的是「失败或中断留下的记录」：要显示给用户看（否则重进会话时
 * 这一轮凭空消失），但**不回放给模型**——模型不该把一句报错当成自己说过的话。
 */
data class AgentTurn(
    val fromUser: Boolean,
    val text: String,
    val note: String? = null,
    val error: Boolean = false,
)

/**
 * agent 历史对外的读写口。
 *
 * 抽成接口而不是直接读文件，一是让实现能在 JVM 单测里跑，
 * 二是不把 Android 依赖泄进数据层。
 *
 * 真正的实现在 [TranscriptMemory]：它从会话的完整消息流里派生「给模型看的那部分」。
 */
interface AgentMemory {
    fun load(conversationId: String): List<AgentTurn>

    /**
     * 整份替换。
     *
     * **只给一次性导入用**（从云端 ACP 历史搬过来、旧格式迁移）。
     * 别拿它做「裁剪历史」：那会把思考与工具过程一起抹掉，重进会话就只剩干巴巴的问答。
     * 上下文长度由 `SessionAgent` 在拼 prompt 时截取。
     */
    fun save(conversationId: String, turns: List<AgentTurn>)
    fun clear(conversationId: String)

    /**
     * 追加一条。
     *
     * 默认实现就是「读-判重-写」。判重放在这里而不是各个调用方：提问有两个入口
     * （发消息的仓储还没拿到沙箱就先记一笔、agent 真正发请求前再记一笔），
     * 两边都写再由实现去重，比规定"只能谁写"要稳——漏一个入口就是一轮消息凭空消失，
     * 而两条一样的提问会让模型以为用户催了一遍。
     */
    fun append(conversationId: String, turn: AgentTurn) {
        val current = load(conversationId)
        val last = current.lastOrNull()
        if (last != null && last.fromUser == turn.fromUser && last.text == turn.text) return
        save(conversationId, current + turn)
    }

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
    /**
     * 跑一轮对话。
     *
     * [imageUrls] 是随这轮问题直发给模型的图片（data URL 或 http 直链），
     * 只有「读图」开着（[CustomModel.vision]）才非空；历史轮不回放图片，
     * 图片证据需要长期保留时走沙箱文件路径。
     */
    suspend fun send(userText: String, imageUrls: List<String> = emptyList()): AgentReply
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
    /**
     * [onThought] 是模型的思考文本（OpenAI 兼容端点的 `reasoning_content`），
     * 一段思考想完时回调一次（一次流式请求一段）。
     *
     * [onDelta] 是逐字流式增量（THOUGHT / ASSISTANT），上层实时显示用。
     * 单独回调而不是塞进 [AgentReply]：一轮里模型可能想好几次（每次工具调用前
     * 各想一段），只有回调才能按真实时间顺序和工具调用交错显示。不思考的模型
     * （或思考强度为「关闭」）永远不触发，上层不必判断。
     */
    fun get(
        conversationId: String,
        endpoint: String,
        token: String,
        config: CustomModelConfig,
        tokenRefresher: suspend () -> String? = { null },
        onThought: (String) -> Unit = {},
        onToolEvent: (SandboxToolEvent) -> Unit = {},
        onExecuted: (String, Long, SandboxExec.Result) -> Unit = { _, _, _ -> },
        onDelta: (ChatRole, String) -> Unit = { _, _ -> },
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
