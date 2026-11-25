package cn.cloudbuddy.app.data

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.DefaultMcpToolDescriptorParser
import ai.koog.agents.mcp.McpToolRegistryProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.withTimeout

/**
 * 本机会话的 MCP 接入。
 *
 * 和云端那条路的区别：云端是**我们把 mcpServers 交给服务端**（`AcpConnection` 的
 * `session/new`），由服务端去连；本机会话里没有服务端，只能自己当客户端——
 * 用 MCP 官方 Kotlin SDK 连上去，再由 Koog 的 `agents-mcp` 把远端工具转成
 * 我们 agent 能调用的 `Tool`。
 *
 * **只支持 URL 型（streamable HTTP / SSE）**：stdio 型（`command` + `args`，
 * 也就是 `npx -y @modelcontextprotocol/server-xxx`）要在本机 fork 子进程并跑 Node，
 * Android 应用做不到。这不是"暂未实现"，是平台没有这个能力，所以直接跳过并说明。
 */
class LocalMcp {

    /** 一条服务器连上之后的结果。 */
    data class Session(
        val registry: ToolRegistry,
        /** 连上的服务器名，用于给用户/模型交代来源。 */
        val connected: List<String>,
        /** 连不上的原因（一条服务器一句），会作为提示展示，不阻塞本轮对话。 */
        val problems: List<String>,
    ) {
        val toolCount: Int get() = registry.tools.size
    }

    private val closables = mutableListOf<() -> Unit>()

    /**
     * 依次连接每条启用的服务器。单条失败不影响其他条：MCP 服务器是外部依赖，
     * 挂掉一个就让整轮对话不可用是不合理的。
     */
    suspend fun connect(servers: List<McpServer>): Session {
        if (servers.isEmpty()) return Session(ToolRegistry.EMPTY, emptyList(), emptyList())

        val connected = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var registry = ToolRegistry.EMPTY

        for (server in servers) {
            val cfg = McpEndpoint.parse(server)
            if (cfg == null) {
                problems += "${server.name}：只支持 url 型（streamable HTTP / SSE）的服务器，" +
                    "command 型的 stdio 服务器在手机上跑不了"
                continue
            }
            try {
                val transport = transportOf(cfg)
                val tools = withTimeout(HANDSHAKE_TIMEOUT_MS) {
                    McpToolRegistryProvider.fromTransport(
                        transport,
                        DefaultMcpToolDescriptorParser,
                        "cloudbuddy-mcp",
                        "1.0",
                    )
                }
                if (tools.tools.isEmpty()) {
                    problems += "${cfg.name}：连上了，但没有暴露任何工具"
                    continue
                }
                // 同名工具会让模型只能看到其中一个，且调用落到谁身上不确定。
                // 我们的沙箱工具（run_shell 等）优先——它们是干活的主力。
                val clash = tools.tools.map { it.name }.filter { it in SANDBOX_TOOL_NAMES }
                val usable = if (clash.isEmpty()) {
                    tools
                } else {
                    problems += "${cfg.name}：${clash.joinToString("、")} 与内置工具重名，已跳过"
                    ToolRegistry {
                        tools.tools.filterNot { it.name in clash }.forEach { tool(it) }
                    }
                }
                registry = registry + usable
                connected += cfg.name
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "${cfg.name}：${readable(e)}"
            }
        }
        return Session(registry, connected, problems)
    }

    /** 断开所有连接。agent 关闭时调用，避免长轮询的 SSE 一直挂着耗电。 */
    fun close() {
        closables.forEach { runCatching { it() } }
        closables.clear()
    }

    private fun transportOf(cfg: McpEndpoint.Config): Transport {
        // 每条服务器一个 HttpClient：MCP 的鉴权头是「这条服务器」的属性，
        // 共用客户端就只能把所有头都塞给所有人，串台且泄密。
        val client = HttpClient(OkHttp) {
            install(SSE)
            if (cfg.headers.isNotEmpty()) {
                defaultRequest { cfg.headers.forEach { (k, v) -> headers.append(k, v) } }
            }
        }
        closables += { runCatching { client.close() } }
        return if (cfg.sse) {
            SseClientTransport(client, cfg.url)
        } else {
            StreamableHttpClientTransport(client, cfg.url)
        }
    }

    private fun readable(e: Exception): String {
        val msg = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return (msg ?: e::class.simpleName ?: "连接失败").take(160)
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 25_000L

        /** 与 [SandboxTools.registry] 里的工具名保持一致。 */
        val SANDBOX_TOOL_NAMES = setOf(
            "run_shell", "read_file", "write_file", "edit_file",
            "list_dir", "grep", "find", "use_skill",
        )
    }
}
