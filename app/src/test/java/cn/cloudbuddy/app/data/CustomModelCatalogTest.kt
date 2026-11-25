package cn.cloudbuddy.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自定义端点模型列表的解析与请求构造。
 *
 * 这段逻辑只在"真拿一个第三方 key 打一次"时才走到，出错的代价是用户看到
 * 一句像模像样的 HTTP 401（实际可能只是地址拼错了 /v1/v1）。所以：
 * 解析要对三种形状都认，请求地址必须用归一化后的 base。
 */
class CustomModelCatalogTest {

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
    fun `openai 标准形状`() {
        val catalog = CustomModelCatalog()
        val models = catalog.parse(
            """{"object":"list","data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}"""
        )
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models)
    }

    @Test
    fun `data 是字符串数组也认`() {
        val catalog = CustomModelCatalog()
        assertEquals(listOf("a", "b"), catalog.parse("""{"data":["a","b"]}"""))
    }

    @Test
    fun `顶层数组与 name model 字段都能兜住`() {
        val catalog = CustomModelCatalog()
        val models = catalog.parse("""[{"name":"qwen-max"},{"model":"glm-4"},"kimi"]""")
        assertEquals(listOf("qwen-max", "glm-4", "kimi"), models)
    }

    @Test
    fun `重复项只留一个`() {
        val catalog = CustomModelCatalog()
        assertEquals(listOf("x"), catalog.parse("""{"data":[{"id":"x"},{"id":"x"}]}"""))
    }

    @Test
    fun `垃圾内容返回空而不是抛异常`() {
        val catalog = CustomModelCatalog()
        assertTrue(catalog.parse("<html>404</html>").isEmpty())
        assertTrue(catalog.parse("").isEmpty())
    }

    @Test
    fun `裸域名按缺省补 v1`() {
        // OpenAI / Moonshot 这类端点只在 /v1 下面有 /models，根上没有。
        assertEquals(
            "https://api.openai.com/v1/models",
            EndpointResolver.modelsUrl("https://api.openai.com")
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            EndpointResolver.modelsUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            EndpointResolver.modelsUrl("https://api.deepseek.com/v1/chat/completions")
        )
    }

    @Test
    fun `地址带 v1 时不会拼出 v1 v1`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m"}]}"""))
        val catalog = CustomModelCatalog()
        val r = catalog.fetch(server.url("/v1").toString(), "sk-test")
        assertTrue(r.ok)
        assertEquals(listOf("m"), r.models)
        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
    }

    @Test
    fun `401 说明是 Key 的问题而不是网络问题`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key"}}""")
        )
        val r = CustomModelCatalog().fetch(server.url("/").toString(), "sk-bad")
        assertTrue(!r.ok)
        // 原文必须留着：不同家的措辞不一样，翻译过的文案会丢掉定位线索。
        assertTrue(r.error!!.contains("Invalid API key"))
        assertTrue(r.error!!.contains("Key"))
    }

    @Test
    fun `没填地址时直接拒绝并发请求`() = runBlocking {
        val r = CustomModelCatalog().fetch("", "sk-test")
        assertNotNull(r.error)
        assertNull(server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS))
    }
}
