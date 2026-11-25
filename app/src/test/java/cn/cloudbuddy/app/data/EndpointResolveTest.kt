package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 端点地址的拆分。
 *
 * 这条逻辑看着只是字符串处理，实际上决定「填了地址以后能不能连上」：
 * 之前把 `/v1` 留在 baseUrl 里，ktor 解析相对路径时会把 base 的最后一段替换掉，
 * 于是请求打到 `/chat/completions`（`/v1` 没了），端点回 404。
 * 所以这里断言的是**base 只留 origin**，路径前缀必须整段进 chatCompletionsPath。
 */
class EndpointResolveTest {

    @Test
    fun `裸域名补 v1 路径`() {
        val r = EndpointResolver.resolve("https://api.deepseek.com")
        assertEquals("https://api.deepseek.com", r.baseUrl)
        assertEquals("v1/chat/completions", r.chatCompletionsPath)
    }

    @Test
    fun `填 v1 时 v1 进路径而不是留在 base`() {
        val r = EndpointResolver.resolve("https://ai-api.minekuai.cn/v1")
        assertEquals("https://ai-api.minekuai.cn", r.baseUrl)
        assertEquals("v1/chat/completions", r.chatCompletionsPath)
    }

    @Test
    fun `深层前缀原样保留`() {
        // 智谱 / 火山这类端点的版本号前面还有一层固定路径。
        val r = EndpointResolver.resolve("https://open.bigmodel.cn/api/paas/v4")
        assertEquals("https://open.bigmodel.cn", r.baseUrl)
        assertEquals("api/paas/v4/chat/completions", r.chatCompletionsPath)
    }

    @Test
    fun `贴完整端点时只剥掉 chat completions`() {
        val r = EndpointResolver.resolve("https://api.deepseek.com/v1/chat/completions")
        assertEquals("https://api.deepseek.com", r.baseUrl)
        assertEquals("v1/chat/completions", r.chatCompletionsPath)
    }

    @Test
    fun `带端口与大小写 scheme 都能拆`() {
        val r = EndpointResolver.resolve("HTTP://127.0.0.1:8000/v1")
        assertEquals("HTTP://127.0.0.1:8000", r.baseUrl)
        assertEquals("v1/chat/completions", r.chatCompletionsPath)
    }

    @Test
    fun `不填协议时整体透传`() {
        // 不在这里猜 scheme：改成 https 只会让用户以为自己填错了域名。
        val r = EndpointResolver.resolve("api.deepseek.com/v1")
        assertEquals("api.deepseek.com/v1", r.baseUrl)
        assertEquals("v1/chat/completions", r.chatCompletionsPath)
    }

    @Test
    fun `空地址不炸`() {
        val r = EndpointResolver.resolve("  ")
        assertEquals("", r.baseUrl)
        assertEquals("v1/chat/completions", r.chatCompletionsPath)
    }

    @Test
    fun `预览地址与实际请求一致`() {
        assertEquals(
            "https://ai-api.minekuai.cn/v1/chat/completions",
            EndpointResolver.previewPath("https://ai-api.minekuai.cn/v1/")
        )
    }
}
