package cn.cloudbuddy.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自研流式客户端（[OpenAiStreamClient]）的解析行为。
 *
 * 它取代了 Koog 的 OpenAI 客户端，原因是 Koog 0.6.0 不解析
 * `reasoning_content`——思考内容（DeepSeek 及大量中转站的写法）在框架里就丢了，
 * 表现是「思考强度调成中，屏幕上什么也没有」。这里的测试卡住自研实现必须满足的
 * 全部形状：
 * 1. `reasoning_content` 增量逐段回调并累积（DeepSeek / 多数中转站）；
 * 2. `reasoning` 字段兜底（OpenRouter 写法）；
 * 3. content 增量累积；
 * 4. 工具调用按 index 分片拼装（name 和 arguments 分散在多帧里）；
 * 5. finish_reason、[DONE] 终止；
 * 6. HTTP 错误要把服务端的 message 带回来。
 */
class ChatStreamTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun cfg() = CustomModelConfig(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        modelId = "deepseek-chat",
    )

    private fun sse(vararg frames: String): MockResponse {
        val body = frames.joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Test
    fun `reasoning_content 逐段回调并累积`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"","reasoning_content":"先想"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"","reasoning_content":"一下"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"答案"}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            )
        )
        val reasoningDeltas = mutableListOf<String>()
        val contentDeltas = mutableListOf<String>()
        val result = OpenAiStreamClient().use {
            it.stream(cfg(), listOf(ChatMsg.User("hi")), emptyList(), { reasoningDeltas += it }, { contentDeltas += it })
        }
        assertEquals("先想一下", result.reasoning)
        assertEquals(listOf("先想", "一下"), reasoningDeltas)
        assertEquals("答案", result.content)
        assertEquals(listOf("答案"), contentDeltas)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `reasoning 字段兜底 OpenRouter 写法`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"reasoning":"raw 思考"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}]}""",
            )
        )
        val result = OpenAiStreamClient().use {
            it.stream(cfg(), listOf(ChatMsg.User("hi")), emptyList())
        }
        assertEquals("raw 思考", result.reasoning)
        assertEquals("好", result.content)
    }

    @Test
    fun `工具调用按 index 拼装分片`() = runBlocking {
        server.enqueue(
            sse(
                // 第一个调用：name 与 arguments 分两帧；第二个调用交错出现。
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"read_file","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call-2","function":{"name":"run_shell","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"a.md\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\"command\":\"ls\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            )
        )
        val result = OpenAiStreamClient().use {
            it.stream(cfg(), listOf(ChatMsg.User("hi")), emptyList())
        }
        assertEquals("tool_calls", result.finishReason)
        assertEquals(2, result.toolCalls.size)
        assertEquals(ChatToolCall("call-1", "read_file", "{\"path\":\"a.md\"}"), result.toolCalls[0])
        assertEquals(ChatToolCall("call-2", "run_shell", "{\"command\":\"ls\"}"), result.toolCalls[1])
    }

    @Test
    fun `HTTP 错误带回服务端 message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"message":"Incorrect API key","type":"invalid_request_error"}}"""
            )
        )
        val e = runCatching {
            OpenAiStreamClient().use { it.stream(cfg(), listOf(ChatMsg.User("hi")), emptyList()) }
        }.exceptionOrNull()
        assertTrue("应当抛 ChatStreamException", e is ChatStreamException)
        assertTrue("要带回服务端的 message，实际：${e?.message}", e!!.message!!.contains("Incorrect API key"))
        assertTrue("要带回状态码", e.message!!.contains("401"))
    }

    @Test
    fun `请求体里模型与消息齐全`() = runBlocking {
        server.enqueue(sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        OpenAiStreamClient().use {
            it.stream(
                cfg(),
                listOf(ChatMsg.System("sys"), ChatMsg.User("hi")),
                emptyList(),
            )
        }
        val body = server.takeRequest().body.readUtf8()
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("deepseek-chat", obj["model"]!!.jsonPrimitive.content)
        assertEquals(true, obj["stream"]!!.jsonPrimitive.content.toBoolean())
        val messages = obj["messages"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(2, messages.size)
    }
}
