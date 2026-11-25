package cn.cloudbuddy.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自定义会话的历史落盘时机。
 *
 * 这里锁的是「消息凭空消失」那个 bug：以前只在整轮成功结束时才把
 * 「提问 + 回答」一起写进历史，于是跑长任务（看仓库、装依赖）时用户
 * 退出去再进来，任务还没跑完、磁盘上什么都没有，会话看起来是空的。
 *
 * 现在的约定：
 * 1. 提问在发请求**之前**就落盘，任何中断都不会让它消失；
 * 2. 失败 / 被停止也要留一条记录，标成 error（显示给人看，不回放给模型）；
 * 3. 成功一轮恰好一条提问 + 一条回答，且回放时不会把同一句提问发两遍
 *    （提问已经进历史了，buildPrompt 要把它摘掉再单独追加）。
 */
class SessionAgentPersistenceTest {

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
    fun `失败时提问与失败记录都留在历史里`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val memory = FakeMemory()

        val reply = agent(memory).send("帮我看看这个仓库")

        assertTrue("应当把失败原因带给用户", reply.answer.contains("失败"))
        assertEquals(2, memory.saved.size)
        assertTrue(memory.saved[0].fromUser)
        assertEquals("帮我看看这个仓库", memory.saved[0].text)
        assertFalse(memory.saved[1].fromUser)
        assertTrue("失败记录要标记成 error，回放时跳过", memory.saved[1].error)
    }

    @Test
    fun `成功一轮只有一条提问和一条回答`() = runBlocking {
        server.enqueue(answer("看完了，README 一共 120 行"))
        val memory = FakeMemory()

        agent(memory).send("看下 README 多少行")

        assertEquals(2, memory.saved.size)
        assertEquals(listOf(true, false), memory.saved.map { it.fromUser })
        assertEquals("看完了，README 一共 120 行", memory.saved[1].text)
        assertFalse(memory.saved[1].error)
    }

    @Test
    fun `回放时同一句提问不会发两遍`() = runBlocking {
        server.enqueue(answer("第一轮回答"))
        server.enqueue(answer("第二轮回答"))
        val memory = FakeMemory()
        val agent = agent(memory)

        agent.send("第一问")
        agent.send("第二问")

        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertEquals("首轮提问只能出现一次", 1, countOf(first, "第一问"))
        // 第二轮要带上历史，但「第二问」自己也只能出现一次。
        assertEquals(1, countOf(second, "第一问"))
        assertEquals(1, countOf(second, "第二问"))
    }

    @Test
    fun `上层已写过提问时不会再写第二条`() = runBlocking {
        // 仓储在发消息前先落盘（拿不到沙箱也不丢提问），agent 进来又写一次，
        // 这里验证去重：两条同样的 user 会让模型以为用户催了一遍。
        server.enqueue(answer("好的"))
        val memory = FakeMemory()
        memory.save("conv-persist", listOf(AgentTurn(fromUser = true, text = "看下 README")))

        agent(memory).send("看下 README")

        assertEquals(2, memory.saved.size)
        assertEquals(listOf(true, false), memory.saved.map { it.fromUser })
    }

    @Test
    fun `思考会通过回调冒出来，不只有最终答案`() = runBlocking {
        // 这条锁的是「思考强度调成中却什么都看不到」：`reasoning_content` 确实回来了，
        // 但 `AIAgent.run()` 只给最终答案，思考必须在 executor 那一层抄出来。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"x","object":"chat.completion","created":0,"model":"m",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"120 行",
                       "reasoning_content":"先看目录，再数 README 的行数"},"finish_reason":"stop"}]}"""
                )
        )
        val thoughts = mutableListOf<String>()
        val memory = FakeMemory()

        val reply = agent(memory, onThought = { thoughts += it }).send("README 多少行")

        assertEquals(listOf("先看目录，再数 README 的行数"), thoughts)
        assertEquals("120 行", reply.answer)
        // 思考不该混进落盘的回答里：它是过程，展开折叠条时才看。
        assertEquals(2, memory.saved.size)
        assertEquals("120 行", memory.saved[1].text)
    }

    private fun countOf(body: String, word: String): Int =
        Regex(Regex.escape(word)).findAll(body).count()

    private fun answer(text: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"x","object":"chat.completion","created":0,"model":"m",
               "choices":[{"index":0,"message":{"role":"assistant","content":"$text"},
               "finish_reason":"stop"}]}"""
        )

    private fun agent(
        memory: AgentMemory,
        onThought: (String) -> Unit = {},
    ): SessionAgent {
        val base = server.url("/").toString().trimEnd('/')
        return SessionAgent(
            conversationId = "conv-persist",
            exec = SandboxExec(OkHttpClient(), ""),
            endpoint = base,
            token = "fake-token",
            config = CustomModelConfig(
                baseUrl = base,
                apiKey = "sk-test",
                modelId = "deepseek-chat",
            ),
            memory = memory,
            onThought = onThought,
        )
    }

    /** 只存在于内存里的历史，方便断言落盘时机。 */
    private class FakeMemory : AgentMemory {
        var saved: List<AgentTurn> = emptyList()

        override fun load(conversationId: String): List<AgentTurn> = saved

        override fun save(conversationId: String, turns: List<AgentTurn>) {
            saved = turns.toList()
        }

        override fun clear(conversationId: String) {
            saved = emptyList()
        }
    }
}
