package cn.cloudbuddy.app.data

/**
 * 基础版的装配：没有任何本机 agent。
 *
 * 这一份的意义不是"运行时禁用"，而是**让 core 包的依赖图里不出现 Koog**：
 * 同一个文件名在 `full` 源集里有另一份实现，构建时按变体各取一份，
 * 所以 core 包里既不会链到 Koog，也不会因为"找不到类"在运行期崩。
 *
 * 用户能走到这里只有一种情况：装的是基础版却触发了自定义模型（旧数据、
 * 或从完整版导过来的设置）。此时给出的是可读的说明，不是异常。
 */
internal object AgentSupport {
    fun create(exec: SandboxExec, settings: AppSettings): AgentRuntime = CoreAgentRuntime
}

/**
 * 一句话说明为什么用不了：说清楚「哪个包才行」，而不是只说「不支持」。
 */
private const val CORE_NOTICE =
    "当前是基础版，不含本机 agent 与第三方 API。装上同版本的完整版即可覆盖升级，" +
        "登录态与设置都会保留；云端模型在基础版里也能正常使用。"

/**
 * 基础版的空运行时：所有入口都能调用，但只回一句话。
 *
 * 不做成抛异常：这一层之上的 UI 已经在入口处隐藏了，走到这里多半是旧配置残留，
 * 用异常收场会把一次"点错了"放大成崩溃。
 */
private object CoreAgentRuntime : AgentRuntime {

    private val unavailable = object : AgentSession {
        override suspend fun send(userText: String): AgentReply = AgentReply(CORE_NOTICE)
    }

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
    ): AgentSession = unavailable

    override fun drop(conversationId: String) = Unit

    override fun clear() = Unit

    override suspend fun testConnection(cfg: CustomModelConfig): Result<String> =
        Result.failure(CloudException(CORE_NOTICE))
}
