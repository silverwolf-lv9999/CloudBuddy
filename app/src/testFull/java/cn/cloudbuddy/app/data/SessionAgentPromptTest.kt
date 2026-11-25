package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词的拼装。
 *
 * 提示词写错不会抛异常，只会让模型"变笨"或者突然开始瞎猜路径——
 * 这种问题没法靠跑一次真模型来回归，只能把拼装规则钉在单测里。
 */
class SessionAgentPromptTest {

    @Test
    fun `留空用内置默认`() {
        assertEquals(CustomModelConfig.DEFAULT_SYSTEM_PROMPT, CustomModelConfig.composeSystemPrompt(""))
        assertEquals(
            CustomModelConfig.DEFAULT_SYSTEM_PROMPT,
            CustomModelConfig.composeSystemPrompt("   \n  ")
        )
    }

    @Test
    fun `自定义在前 沙箱规则在后`() {
        val text = CustomModelConfig.composeSystemPrompt("你是一个只会说粤语的助手。")
        assertTrue(text.startsWith("你是一个只会说粤语的助手。"))
        assertTrue(text.contains("沙箱工作目录是 /workspace"))
        // 用户接管风格后，内置的「使用简体中文」不该再压着他的设定。
        assertFalse(text.contains("使用简体中文"))
    }

    @Test
    fun `自己贴了沙箱规则时不重复追加`() {
        val custom = "你是助手。\n\n" + CustomModelConfig.SANDBOX_BRIEF
        val text = CustomModelConfig.composeSystemPrompt(custom)
        assertEquals(1, Regex("沙箱工作目录是 /workspace").findAll(text).count())
    }

    @Test
    fun `配置对象本身给出同一份结果`() {
        val cfg = CustomModelConfig(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-x",
            modelId = "deepseek-chat",
            systemPrompt = "只回答一句话。"
        )
        assertEquals("只回答一句话。\n\n" + CustomModelConfig.SANDBOX_BRIEF, cfg.effectiveSystemPrompt)
        assertTrue(cfg.usable)
    }

    @Test
    fun `配置不全时 usable 为 false`() {
        val cfg = CustomModelConfig(baseUrl = "", apiKey = "sk-x", modelId = "m")
        assertFalse(cfg.usable)
    }
}
