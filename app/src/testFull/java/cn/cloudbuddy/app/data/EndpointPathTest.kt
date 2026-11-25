package cn.cloudbuddy.app.data

import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 真正发出去的那条请求落在哪个路径上。
 *
 * [EndpointResolveTest] 只能保证拆出来的两段字符串是对的；能不能连上还取决于
 * Koog / ktor 怎么把这两段拼起来——那里是**相对路径解析**，base 的最后一段会被
 * 请求 path 替换掉。所以这里起 MockWebServer 把实际 path 抓下来，
 * 锁住「用户填 `.../v1` 时请求打到 `/v1/chat/completions`」这件事。
 */
class EndpointPathTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"x","object":"chat.completion","created":0,"model":"m",
                   "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                   "finish_reason":"stop"}]}"""
            )
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `填 v1 的地址打到 v1 下面`() = runBlocking {
        assertEquals("/v1/chat/completions", send("v1"))
    }

    @Test
    fun `裸域名补上 v1`() = runBlocking {
        assertEquals("/v1/chat/completions", send(""))
    }

    @Test
    fun `贴完整端点不会把路径拼两遍`() = runBlocking {
        assertEquals("/v1/chat/completions", send("v1/chat/completions"))
    }

    private suspend fun send(suffix: String): String {
        val root = server.url("/").toString().trimEnd('/')
        val cfg = CustomModelConfig(
            baseUrl = if (suffix.isEmpty()) root else "$root/$suffix",
            apiKey = "sk-test",
            modelId = "m",
        )
        val client = CustomLlmClient.build(cfg)
        try {
            val p = prompt("t") { user("hi") }.withParams(CustomLlmClient.params(cfg))
            client.execute(p, CustomLlmClient.model(cfg), emptyList())
        } finally {
            runCatching { client.close() }
        }
        return server.takeRequest().path.orEmpty()
    }
}
