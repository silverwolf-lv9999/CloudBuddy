package cn.cloudbuddy.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 品牌图标匹配测试：模型 ID / 提供商名 / 域名 → 品牌键。 */
class BrandIconsTest {

    @Test
    fun `常见模型 ID 命中品牌`() {
        assertEquals("openai", BrandIcons.key("gpt-4o"))
        assertEquals("openai", BrandIcons.key("gpt-5-mini"))
        assertEquals("openai", BrandIcons.key("openai/o3"))
        assertEquals("deepseek", BrandIcons.key("deepseek-chat"))
        assertEquals("deepseek", BrandIcons.key("deepseek-reasoner"))
        assertEquals("claude", BrandIcons.key("claude-sonnet-4-5"))
        assertEquals("gemini", BrandIcons.key("gemini-2.5-flash"))
        assertEquals("qwen", BrandIcons.key("qwen-vl-max"))
        assertEquals("qwen", BrandIcons.key("qwq-32b"))
        assertEquals("kimi", BrandIcons.key("kimi-k2"))
        assertEquals("doubao", BrandIcons.key("doubao-pro-32k"))
        assertEquals("zhipu", BrandIcons.key("glm-4.6"))
        assertEquals("hunyuan", BrandIcons.key("hunyuan-turbo"))
        assertEquals("minimax", BrandIcons.key("MiniMax-M2"))
        assertEquals("mistral", BrandIcons.key("mistral-large"))
        assertEquals("xai", BrandIcons.key("grok-4"))
        assertEquals("meta", BrandIcons.key("meta-llama/Llama-3.1-70B"))
        assertEquals("cohere", BrandIcons.key("command-r-plus"))
        // 托管平台上的模型按模型本身的品牌显示（llama 是 Meta 的、qwen 是通义的）
        assertEquals("meta", BrandIcons.key("groq/llama3"))
        assertEquals("qwen", BrandIcons.key("ollama/qwen3"))
        assertEquals("openrouter", BrandIcons.key("openrouter/auto"))
        assertEquals("siliconflow", BrandIcons.key("硅基流动"))
        assertEquals("mimo", BrandIcons.key("MiMo-VL-7B"))
    }

    @Test
    fun `提供商名与域名命中`() {
        assertEquals("deepseek", BrandIcons.key("deepseek 官方 api.deepseek.com"))
        assertEquals("siliconflow", BrandIcons.key("硅基流动 siliconflow.cn"))
        assertEquals("alibabacloud", BrandIcons.key("阿里云百炼 dashscope.aliyuncs.com"))
        assertEquals("moonshot", BrandIcons.key("月之暗面 api.moonshot.cn"))
    }

    @Test
    fun `匹配不到返回 null`() {
        assertNull(BrandIcons.key(""))
        assertNull(BrandIcons.key("unknown-llm-xyz"))
        assertNull(BrandIcons.key("my-relay-station"))
    }

    @Test
    fun `具体品牌优先于泛匹配`() {
        // gemini 不能被 google 抢走；claude 不能被 anthropic 抢走
        assertEquals("gemini", BrandIcons.key("google/gemini-2.5-pro"))
        assertEquals("claude", BrandIcons.key("anthropic claude"))
    }
}
