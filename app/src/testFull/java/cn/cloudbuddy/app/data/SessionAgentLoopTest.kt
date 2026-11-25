package cn.cloudbuddy.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * agent 循环的端到端验证。
 *
 * 用一个假的 OpenAI 兼容端点跑通完整链路：
 * 请求 → 解析 tool_call → 执行工具 → 把工具结果回喂 → 拿到最终回答。
 *
 * 这一步比"编译通过"重要得多：ktor 引擎缺失、tool_call 字段名不匹配、
 * 消息角色不被接受这类问题，只有真正发一次请求才会暴露。
 */
class SessionAgentLoopTest {

    private lateinit var server: MockWebServer
    private val chatHits = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("chat/completions") -> {
                        val n = chatHits.incrementAndGet()
                        if (n == 1) toolCallResponse() else finalAnswerResponse()
                    }
                    // 工具执行故意失败：验证工具报错不会让整轮崩掉，
                    // 模型仍能拿到错误信息并给出回答。
                    path.contains("process.Process/Start") -> MockResponse().setResponseCode(500).setBody("boom")
                    path.contains("filesystem") -> MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun toolCallResponse(): MockResponse {
        val body = """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "deepseek-chat",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": { "name": "run_shell", "arguments": "{\"command\":\"echo hi\"}" }
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
            }
        """.trimIndent()
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun finalAnswerResponse(): MockResponse {
        val body = """
            {
              "id": "chatcmpl-2",
              "object": "chat.completion",
              "created": 1700000001,
              "model": "deepseek-chat",
              "choices": [{
                "index": 0,
                "message": { "role": "assistant", "content": "命令执行环境不可用，请检查沙箱连接。" },
                "finish_reason": "stop"
              }],
              "usage": { "prompt_tokens": 20, "completion_tokens": 8, "total_tokens": 28 }
            }
        """.trimIndent()
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun agent(): SessionAgent {
        val base = server.url("/").toString().trimEnd('/')
        val exec = SandboxExec(OkHttpClient(), "")
        return SessionAgent(
            conversationId = "conv-1",
            exec = exec,
            endpoint = base,
            token = "fake-token",
            config = CustomModelConfig(
                baseUrl = base,
                apiKey = "sk-test",
                modelId = "deepseek-chat",
            ),
        )
    }

    @Test
    fun `tool_call 被解析并执行后收敛到最终回答`() = runBlocking {
        val answer = agent().send("帮我看下环境")

        assertEquals("命令执行环境不可用，请检查沙箱连接。", answer.answer)
        // 两次模型调用：一次出 tool_call，一次出最终回答
        assertEquals(2, chatHits.get())

        // 两次模型调用：一次出 tool_call，一次出最终回答；
        // 中间还会夹着工具打到同一台 mock 上的 envd 请求，所以要按路径筛。
        val chatRequests = mutableListOf<RecordedRequest>()
        repeat(8) {
            val r = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) ?: return@repeat
            if (r.path.orEmpty().contains("chat/completions")) chatRequests.add(r)
        }
        assertEquals(2, chatRequests.size)

        val secondBody = chatRequests[1].body.readUtf8()
        assertTrue("请求体应包含工具返回的角色，实际=$secondBody", secondBody.contains("\"tool\""))
        assertTrue("请求体应包含工具名", secondBody.contains("run_shell"))
    }

    @Test
    fun `配置不完整时不发请求直接提示`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val exec = SandboxExec(OkHttpClient(), "")
        val incomplete = SessionAgent(
            conversationId = "conv-2",
            exec = exec,
            endpoint = base,
            token = "t",
            config = CustomModelConfig(baseUrl = "", apiKey = "", modelId = ""),
        )

        val answer = incomplete.send("hi")
        assertTrue(answer.answer.contains("未配置完整"))
        assertEquals(0, chatHits.get())
    }
}
