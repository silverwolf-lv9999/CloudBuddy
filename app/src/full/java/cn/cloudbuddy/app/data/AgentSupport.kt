package cn.cloudbuddy.app.data

/**
 * 完整版的装配：真实的本机 agent 运行时（Koog + OpenAI 兼容端点 + 沙箱工具）。
 *
 * 与 `core` 源集里的同名 object 二选一参与编译，仓储只认这个入口，
 * 两种包的仓储代码完全一致。见 [AgentRuntime] 的说明。
 */
internal object AgentSupport {
    fun create(exec: SandboxExec, memory: AgentMemory): AgentRuntime =
        SessionAgentHub(exec, memory)
}
