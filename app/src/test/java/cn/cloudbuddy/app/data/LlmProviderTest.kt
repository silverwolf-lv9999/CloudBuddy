package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提供商实体与请求 URL 拼装的测试。
 *
 * 覆盖的是最容易静默出错的部分：encode/decode 往返丢字段（旧版升级后配置
 * 变成默认值，用户只会觉得「设置丢了」）、streamUrl 的覆盖优先级
 * （override 完整 URL / 相对路径 / 留空走统一规则）。
 */
class LlmProviderTest {

    // ---- encode/decode 往返 ----

    @Test
    fun `encode-decode 往返保留全部字段`() {
        val p = LlmProvider(
            id = "p-abc",
            name = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-test",
            enabled = false,
            chatPath = "v1/chat/completions",
            headers = linkedMapOf("HTTP-Referer" to "https://yoursite.com", "X-Title" to "CloudBuddy"),
        )
        val back = LlmProvider.decodeList(LlmProvider.encodeList(listOf(p))).single()
        assertEquals(p, back)
    }

    @Test
    fun `旧数据（无新字段）decode 不炸且取默认值`() {
        // 1.6.0 之前落盘的形状：只有 5 个字段
        val legacy = """[{"id":"p-1","name":"n","baseUrl":"https://x.com","apiKey":"k","enabled":true}]"""
        val p = LlmProvider.decodeList(legacy).single()
        assertEquals("", p.chatPath)
        assertTrue(p.headers.isEmpty())
    }

    @Test
    fun `解析请求头 - 每行一条 Key Value`() {
        val headers = LlmProvider.parseHeaders(
            """
            HTTP-Referer: https://yoursite.com
            X-Title: CloudBuddy
            这行没有冒号应被忽略
            Empty: 
            """.trimIndent()
        )
        assertEquals(2, headers.size)
        assertEquals("https://yoursite.com", headers["HTTP-Referer"])
        assertEquals("CloudBuddy", headers["X-Title"])
    }

    @Test
    fun `请求头文本化再解析保持一致`() {
        val headers = linkedMapOf("A" to "1", "B" to "2")
        assertEquals(headers, LlmProvider.parseHeaders(LlmProvider.headersToText(headers)))
    }

    // ---- CustomModelConfig.streamUrl ----

    private fun config(baseUrl: String, override: String = "") = CustomModelConfig(
        baseUrl = baseUrl,
        apiKey = "k",
        modelId = "m",
        chatPathOverride = override,
    )

    @Test
    fun `留空时按统一规则拼完整路径`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            config("https://api.deepseek.com").streamUrl
        )
    }

    @Test
    fun `相对路径覆盖拼在 origin 上`() {
        assertEquals(
            "https://x.com/api/v2/messages",
            config("https://x.com", override = "api/v2/messages").streamUrl
        )
    }

    @Test
    fun `完整 URL 覆盖原样使用`() {
        assertEquals(
            "https://gateway.example.com/openai/chat",
            config("https://x.com", override = "https://gateway.example.com/openai/chat").streamUrl
        )
    }

    @Test
    fun `覆盖不影响 modelsUrl 的归一化规则`() {
        // 模型列表始终走统一规则（/models 与 chat 同源前缀），不受 override 干扰
        assertEquals(
            "https://api.deepseek.com/v1/models",
            EndpointResolver.modelsUrl("https://api.deepseek.com")
        )
    }
}
