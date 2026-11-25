package cn.cloudbuddy.app.data

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.message.Message
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 思考过程的抓取（[ReasoningTee]）。
 *
 * 这块此前是空白：完整版接第三方端点时，`reasoning_content` 确实回来了，
 * 但 `AIAgent.run()` 只回最终答案，思考在框架内部就被丢掉——表现是
 * 「思考强度调成中，屏幕上什么也没有」。所以测试要卡住两件事：
 * 1. 有 `reasoning_content` 时必须抄出来；
 * 2. 没有的时候不许瞎回调（不思考的模型不能凭空多出一条空思考）。
 */
class ReasoningTeeTest {

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

    @Test
    fun `reasoning_content 会被抄成一段思考`() = runBlocking {
        enqueue(
            """{"id":"x","object":"chat.completion","created":0,"model":"m",
               "choices":[{"index":0,"message":{"role":"assistant","content":"2",
               "reasoning_content":"先看题目：9.9 比 9.11 大。"},"finish_reason":"stop"}]}"""
        )
        val thoughts = mutableListOf<String>()
        val responses = run(thoughts)
        assertEquals(listOf("先看题目：9.9 比 9.11 大。"), thoughts)
        // 原样返回：思考只是旁路，agent 该看到什么还是什么。
        assertTrue(responses.any { it is Message.Assistant })
    }

    @Test
    fun `没有思考内容时不回调`() = runBlocking {
        enqueue(
            """{"id":"x","object":"chat.completion","created":0,"model":"m",
               "choices":[{"index":0,"message":{"role":"assistant","content":"2"},
               "finish_reason":"stop"}]}"""
        )
        val thoughts = mutableListOf<String>()
        val responses = run(thoughts)
        assertTrue("空思考不该产生消息", thoughts.isEmpty())
        assertTrue(responses.any { it is Message.Assistant })
    }

    private suspend fun run(thoughts: MutableList<String>): List<Message.Response> {
        val cfg = CustomModelConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            modelId = "deepseek-chat",
            thinkingEffort = ThinkingEffort.MEDIUM.key,
        )
        val client = CustomLlmClient.build(cfg)
        try {
            val tee = ReasoningTee(SingleLLMPromptExecutor(client)) { thoughts += it }
            val p = prompt("t") {
                system("sys")
                user("hi")
            }.withParams(CustomLlmClient.params(cfg))
            return tee.execute(p, CustomLlmClient.model(cfg), emptyList())
        } finally {
            runCatching { client.close() }
        }
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }
}
